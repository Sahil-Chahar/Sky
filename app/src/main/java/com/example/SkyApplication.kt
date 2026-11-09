package com.example

import android.app.Application
import android.content.ComponentCallbacks2
import com.example.data.local.SkyDatabase
import com.example.data.local.SkyEncryptedPrefs
import com.example.data.repository.SkyRepository
import com.example.engine.InferenceBroker
import com.example.hub.HuggingFaceHubClient
import com.example.hub.ModelDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class SkyApplication : Application() {

    lateinit var database: SkyDatabase
        private set

    lateinit var encryptedPrefs: SkyEncryptedPrefs
        private set

    lateinit var repository: SkyRepository
        private set

    lateinit var inferenceBroker: InferenceBroker
        private set

    lateinit var hubClient: HuggingFaceHubClient
        private set

    lateinit var downloadManager: ModelDownloadManager
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        val modelsDir = File(filesDir, "models").apply { mkdirs() }
        database = SkyDatabase.getInstance(this)
        encryptedPrefs = SkyEncryptedPrefs(this)
        repository = SkyRepository(database.skyDao(), modelsDir)
        inferenceBroker = InferenceBroker(
            context = this,
            repository = repository,
            encryptedPrefs = encryptedPrefs,
            modelsDir = modelsDir
        )
        hubClient = HuggingFaceHubClient()
        downloadManager = ModelDownloadManager(
            context = this,
            repository = repository,
            encryptedPrefs = encryptedPrefs,
            modelsDir = modelsDir
        )

        appScope.launch {
            inferenceBroker.ensureDefaultModelsBootstrapped()
            inferenceBroker.warmUpNeedleAndWhistle()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            inferenceBroker.onTrimMemory()
        }
    }

    companion object {
        @Volatile
        private var instance: SkyApplication? = null

        fun from(context: android.content.Context): SkyApplication {
            val app = context.applicationContext
            if (app is SkyApplication) return app
            return instance ?: throw IllegalStateException("SkyApplication not initialized")
        }
    }
}
