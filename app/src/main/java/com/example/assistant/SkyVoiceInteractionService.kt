package com.example.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.SkyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * System-wide Digital Assistant Service (`BIND_VOICE_INTERACTION`).
 * Warm-loads `needle3.cact` + `whistle.cact` in `onReady()` so power-button long-press
 * launches with target < 300 ms to overlay visible when the service is warm.
 */
class SkyVoiceInteractionService : VoiceInteractionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReady() {
        super.onReady()
        serviceScope.launch {
            try {
                SkyApplication.from(this@SkyVoiceInteractionService)
                    .inferenceBroker
                    .warmUpNeedleAndWhistle()
            } catch (_: Throwable) {
            }
        }
    }
}

class SkyVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return SkyVoiceInteractionSession(this)
    }
}

/**
 * `VoiceInteractionSession` hosting a translucent Jetpack Compose overlay directly over the
 * current foreground application or lockscreen.
 *
 * Manually wires `ViewTreeLifecycleOwner`, `ViewTreeViewModelStoreOwner`, and
 * `ViewTreeSavedStateRegistryOwner` on the session window's decor view so Compose works
 * outside `ComponentActivity`.
 *
 * Captures `AssistStructure` in `onHandleAssist` for "Ask about this screen" (which is
 * routed strictly to the Reply GGUF path with all tools disabled).
 */
class SkyVoiceInteractionSession(context: Context) :
    VoiceInteractionSession(context),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private var extractedScreenText: String? = null

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onCreateContentView(): android.view.View {
        val decorView = window?.window?.decorView
        if (decorView != null) {
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@SkyVoiceInteractionSession)
            setViewTreeViewModelStoreOwner(this@SkyVoiceInteractionSession)
            setViewTreeSavedStateRegistryOwner(this@SkyVoiceInteractionSession)
            setContent {
                SkyAssistantOverlayHost(
                    initialScreenContext = extractedScreenText,
                    onDismissOverlay = { hide() }
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onHide() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        extractedScreenText = null
        super.onHide()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        val structure = state.assistStructure
        if (structure != null) {
            extractedScreenText = extractVisibleTextFromAssistStructure(structure)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        super.onHandleAssist(data, structure, content)
        if (structure != null) {
            extractedScreenText = extractVisibleTextFromAssistStructure(structure)
        }
    }

    companion object {
        fun extractVisibleTextFromAssistStructure(structure: AssistStructure): String {
            val sb = StringBuilder()
            val windowCount = structure.windowNodeCount
            for (i in 0 until windowCount) {
                val winNode = structure.getWindowNodeAt(i)
                val title = winNode.title?.toString()
                if (!title.isNullOrBlank()) {
                    sb.append("Window: ").append(title).append('\n')
                }
                traverseViewNode(winNode.rootViewNode, sb, maxChars = 1200)
            }
            return sb.toString().trim()
        }

        private fun traverseViewNode(
            node: AssistStructure.ViewNode?,
            sb: StringBuilder,
            maxChars: Int
        ) {
            if (node == null || sb.length >= maxChars) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            if (!text.isNullOrBlank() && text.length > 1) {
                sb.append(text).append(' ')
            } else if (!desc.isNullOrBlank() && desc.length > 1) {
                sb.append(desc).append(' ')
            }
            for (i in 0 until node.childCount) {
                if (sb.length >= maxChars) break
                traverseViewNode(node.getChildAt(i), sb, maxChars)
            }
        }
    }
}

/**
 * Required by Android's VoiceInteractionService validation on several OEM builds.
 */
class SkyRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {
        listener?.beginningOfSpeech()
    }

    override fun onCancel(listener: Callback?) {
    }

    override fun onStopListening(listener: Callback?) {
        listener?.endOfSpeech()
    }
}
