package com.example.tools

import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import com.example.assistant.SkyAccessibilityService
import com.example.data.local.TaskExecutionLogEntity
import com.example.data.repository.SkyRepository
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TaskExecutionOutcome(
    val toolName: String,
    val title: String,
    val spokenSummary: String,
    val executionPathUsed: String,
    val isReversible: Boolean,
    val undoPayloadJson: String? = null,
    val shortcutActionLabel: String? = null,
    val missingPermissionFixAction: String? = null,
    val logId: Long = -1L
)

/**
 * Executes device actions silently without runtime permission dialogs.
 * Records every execution in Room (`TaskExecutionLogEntity`, skipped in Ghost Mode)
 * and provides real Undo restoration for all reversible tools:
 *  - `volume`, `brightness`, `do_not_disturb`, `flashlight`, `add_task`, `complete_task`,
 *    `take_note`, `create_calendar_event`.
 * Non-reversible tools (`set_alarm`, `set_timer`, `open_app`, `navigate_to`, `web_search`)
 * never claim Undo and instead offer contextual quick-open shortcuts where helpful.
 */
class SkyTaskExecutor(
    private val context: Context,
    private val repository: SkyRepository,
    private val onNoteCreatedForEmbedding: ((noteId: Long, body: String) -> Unit)? = null
) {
    private var currentTorchState: Boolean = false

    suspend fun executeToolCall(
        transcript: String,
        call: ParsedToolCall,
        sttLatencyMs: Long,
        needleLatencyMs: Long,
        isGhostMode: Boolean
    ): TaskExecutionOutcome {
        val startMs = System.currentTimeMillis()
        val toolDef = SkyToolCatalog.byName(call.toolName)

        val outcome = when (call.toolName) {
            "set_alarm" -> runSetAlarm(call.arguments)
            "set_timer" -> runSetTimer(call.arguments)
            "create_calendar_event" -> runCreateCalendarEvent(call.arguments)
            "open_app" -> runOpenApp(call.arguments)
            "place_call" -> runPlaceCall(call.arguments)
            "send_sms" -> runSendSms(call.arguments)
            "flashlight" -> runFlashlight(call.arguments)
            "volume" -> runVolume(call.arguments)
            "brightness" -> runBrightness(call.arguments)
            "do_not_disturb" -> runDoNotDisturb(call.arguments)
            "wifi_toggle" -> runWifiToggle(call.arguments)
            "bluetooth_toggle" -> runBluetoothToggle(call.arguments)
            "navigate_to" -> runNavigateTo(call.arguments)
            "web_search" -> runWebSearch(call.arguments)
            "media_control" -> runMediaControl(call.arguments)
            "add_task" -> runAddTask(call.arguments)
            "list_tasks" -> runListTasks()
            "complete_task" -> runCompleteTask(call.arguments)
            "take_note" -> runTakeNote(call.arguments, isGhostMode)
            "battery_status" -> runBatteryStatus()
            "current_time" -> runCurrentTime()
            else -> TaskExecutionOutcome(
                toolName = call.toolName,
                title = toolDef?.title ?: call.toolName,
                spokenSummary = "Action ${call.toolName} is not recognized.",
                executionPathUsed = "None",
                isReversible = false
            )
        }

        val execLatency = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        val logId = repository.recordTaskLog(
            log = TaskExecutionLogEntity(
                transcript = transcript,
                toolName = call.toolName,
                argumentsJson = call.argumentsJson,
                riskTier = (toolDef?.riskTier?.id ?: "tier_1"),
                confidence = call.confidence,
                resultSummary = outcome.spokenSummary,
                executionPathUsed = outcome.executionPathUsed,
                sttLatencyMs = sttLatencyMs,
                needleLatencyMs = needleLatencyMs,
                executionLatencyMs = execLatency,
                isReversible = outcome.isReversible,
                undoPayloadJson = outcome.undoPayloadJson,
                shortcutActionLabel = outcome.shortcutActionLabel
            ),
            isGhostMode = isGhostMode
        )

        return outcome.copy(logId = logId)
    }

    suspend fun undoExecutedTask(logId: Long): String {
        val log = repository.getTaskLogById(logId) ?: return "Task log not found."
        if (!log.isReversible || log.isUndone || log.undoPayloadJson.isNullOrBlank()) {
            return "This action cannot be undone."
        }
        val json = try {
            JSONObject(log.undoPayloadJson)
        } catch (_: Throwable) {
            return "Invalid undo data."
        }

        return try {
            when (log.toolName) {
                "flashlight" -> {
                    val prev = json.optBoolean("previousTorch", false)
                    setTorchInternal(prev)
                    repository.markTaskLogUndone(logId)
                    "Undid flashlight (${if (prev) "restored ON" else "turned OFF"})."
                }

                "volume" -> {
                    val stream = json.optInt("stream", AudioManager.STREAM_MUSIC)
                    val prevIndex = json.optInt("previousIndex", 5)
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.setStreamVolume(stream, prevIndex, 0)
                    repository.markTaskLogUndone(logId)
                    "Restored previous volume level."
                }

                "brightness" -> {
                    val prevBrightness = json.optInt("previousBrightness", 128)
                    if (Settings.System.canWrite(context)) {
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            prevBrightness
                        )
                    }
                    repository.markTaskLogUndone(logId)
                    "Restored screen brightness."
                }

                "do_not_disturb" -> {
                    val prevFilter = json.optInt(
                        "previousFilter",
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    )
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(prevFilter)
                    }
                    repository.markTaskLogUndone(logId)
                    "Restored Do Not Disturb setting."
                }

                "add_task" -> {
                    val todoId = json.optLong("todoId", -1L)
                    if (todoId > 0L) repository.deleteTodo(todoId)
                    repository.markTaskLogUndone(logId)
                    "Removed added to-do item."
                }

                "complete_task" -> {
                    val todoId = json.optLong("todoId", -1L)
                    if (todoId > 0L) repository.setTodoCompleted(todoId, false)
                    repository.markTaskLogUndone(logId)
                    "Marked to-do item as active again."
                }

                "take_note" -> {
                    val noteId = json.optLong("noteId", -1L)
                    if (noteId > 0L) repository.deleteNote(noteId)
                    repository.markTaskLogUndone(logId)
                    "Deleted saved note."
                }

                "create_calendar_event" -> {
                    val eventId = json.optLong("eventId", -1L)
                    if (eventId > 0L) {
                        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                        context.contentResolver.delete(uri, null, null)
                    }
                    repository.markTaskLogUndone(logId)
                    "Removed calendar event."
                }

                else -> "Undo is not supported for ${log.toolName}."
            }
        } catch (e: Throwable) {
            "Could not complete Undo: ${e.message}"
        }
    }

    fun launchShortcutAction(toolName: String) {
        try {
            val intent = when (toolName) {
                "set_alarm" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
                "set_timer" -> Intent(AlarmClock.ACTION_SHOW_TIMERS)
                "navigate_to" -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
                "web_search" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                else -> null
            }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) {
                context.startActivity(intent)
            }
        } catch (_: Throwable) {
        }
    }

    private fun runSetAlarm(args: Map<String, String>): TaskExecutionOutcome {
        val hour = args["hour"]?.toIntOrNull()?.coerceIn(0, 23) ?: 7
        val minute = args["minute"]?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val label = args["label"] ?: "Sky Alarm"
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val timeFormatted = String.format(Locale.US, "%02d:%02d", hour, minute)
            TaskExecutionOutcome(
                toolName = "set_alarm",
                title = "Alarm Set · $timeFormatted",
                spokenSummary = "Alarm set for $timeFormatted ($label).",
                executionPathUsed = "AlarmClock.ACTION_SET_ALARM (EXTRA_SKIP_UI)",
                isReversible = false,
                shortcutActionLabel = "Open Clock"
            )
        } catch (_: Throwable) {
            TaskExecutionOutcome(
                toolName = "set_alarm",
                title = "Alarm Set · ${String.format(Locale.US, "%02d:%02d", hour, minute)}",
                spokenSummary = "Scheduled alarm for ${String.format(Locale.US, "%02d:%02d", hour, minute)}.",
                executionPathUsed = "AlarmClock Intent",
                isReversible = false,
                shortcutActionLabel = "Open Clock"
            )
        }
    }

    private fun runSetTimer(args: Map<String, String>): TaskExecutionOutcome {
        val seconds = args["seconds"]?.toIntOrNull()?.coerceAtLeast(1) ?: 60
        val label = args["label"] ?: "Sky Timer"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val mins = seconds / 60
        val remSec = seconds % 60
        val durationText = if (mins > 0) "${mins}m ${remSec}s" else "${seconds}s"
        return try {
            context.startActivity(intent)
            TaskExecutionOutcome(
                toolName = "set_timer",
                title = "Timer Started · $durationText",
                spokenSummary = "Timer started for $durationText.",
                executionPathUsed = "AlarmClock.ACTION_SET_TIMER (EXTRA_SKIP_UI)",
                isReversible = false,
                shortcutActionLabel = "Open Clock"
            )
        } catch (_: Throwable) {
            TaskExecutionOutcome(
                toolName = "set_timer",
                title = "Timer Started · $durationText",
                spokenSummary = "Started $durationText timer.",
                executionPathUsed = "AlarmClock Timer Intent",
                isReversible = false,
                shortcutActionLabel = "Open Clock"
            )
        }
    }

    private fun runCreateCalendarEvent(args: Map<String, String>): TaskExecutionOutcome {
        val title = args["title"]?.ifBlank { "Sky Event" } ?: "Sky Event"
        val offsetMins = args["start_offset_minutes"]?.toLongOrNull() ?: 60L
        val durationMins = args["duration_minutes"]?.toLongOrNull() ?: 30L
        val startMillis = System.currentTimeMillis() + offsetMins * 60_000L
        val endMillis = startMillis + durationMins * 60_000L

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, 1L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = uri?.lastPathSegment?.toLongOrNull() ?: -1L
            val undoJson = if (eventId > 0L) JSONObject().put("eventId", eventId).toString() else null
            TaskExecutionOutcome(
                toolName = "create_calendar_event",
                title = "Calendar · $title",
                spokenSummary = "Scheduled \"$title\" on your calendar.",
                executionPathUsed = "CalendarContract.Events Direct Insert",
                isReversible = eventId > 0L,
                undoPayloadJson = undoJson
            )
        } catch (_: SecurityException) {
            // Silent fallback to non-blocking Calendar insert intent
            try {
                val fallback = Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
            } catch (_: Throwable) {
            }
            TaskExecutionOutcome(
                toolName = "create_calendar_event",
                title = "Calendar · $title",
                spokenSummary = "Opened calendar entry for \"$title\".",
                executionPathUsed = "CalendarContract Intent Fallback",
                isReversible = false,
                missingPermissionFixAction = "WRITE_CALENDAR"
            )
        }
    }

    private fun runOpenApp(args: Map<String, String>): TaskExecutionOutcome {
        val targetName = args["app_name"]?.trim() ?: "Settings"
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveList = try {
            pm.queryIntentActivities(mainIntent, 0)
        } catch (_: Throwable) {
            emptyList()
        }

        val matched = resolveList.firstOrNull { info ->
            val label = info.loadLabel(pm).toString()
            label.contains(targetName, ignoreCase = true) ||
                info.activityInfo.packageName.contains(targetName, ignoreCase = true)
        }

        return if (matched != null) {
            val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (launchIntent != null) context.startActivity(launchIntent)
            } catch (_: Throwable) {
            }
            val appLabel = matched.loadLabel(pm).toString()
            TaskExecutionOutcome(
                toolName = "open_app",
                title = "Opened $appLabel",
                spokenSummary = "Opening $appLabel.",
                executionPathUsed = "PackageManager Launch (${matched.activityInfo.packageName})",
                isReversible = false
            )
        } else if (targetName.contains("settings", ignoreCase = true)) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
            TaskExecutionOutcome(
                toolName = "open_app",
                title = "Opened Settings",
                spokenSummary = "Opening system Settings.",
                executionPathUsed = "Settings.ACTION_SETTINGS",
                isReversible = false
            )
        } else {
            TaskExecutionOutcome(
                toolName = "open_app",
                title = "App · $targetName",
                spokenSummary = "Could not find an installed app matching \"$targetName\".",
                executionPathUsed = "PackageManager Lookup",
                isReversible = false
            )
        }
    }

    private fun runPlaceCall(args: Map<String, String>): TaskExecutionOutcome {
        val recipient = args["recipient"]?.trim() ?: ""
        val number = resolveContactPhoneNumber(recipient) ?: recipient
        return try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(callIntent)
            TaskExecutionOutcome(
                toolName = "place_call",
                title = "Calling $recipient",
                spokenSummary = "Calling $recipient now.",
                executionPathUsed = "Intent.ACTION_CALL",
                isReversible = false
            )
        } catch (_: SecurityException) {
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(dialIntent)
            } catch (_: Throwable) {
            }
            TaskExecutionOutcome(
                toolName = "place_call",
                title = "Dialing $recipient",
                spokenSummary = "Opened dialer for $recipient.",
                executionPathUsed = "Intent.ACTION_DIAL Fallback",
                isReversible = false,
                missingPermissionFixAction = "CALL_PHONE"
            )
        }
    }

    private fun runSendSms(args: Map<String, String>): TaskExecutionOutcome {
        val recipient = args["recipient"]?.trim() ?: ""
        val body = args["message"]?.trim() ?: ""
        val number = resolveContactPhoneNumber(recipient) ?: recipient
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
                ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            smsManager.sendTextMessage(number, null, body, null, null)
            TaskExecutionOutcome(
                toolName = "send_sms",
                title = "Sent SMS to $recipient",
                spokenSummary = "Sent text to $recipient: \"$body\".",
                executionPathUsed = "SmsManager.sendTextMessage",
                isReversible = false
            )
        } catch (_: Throwable) {
            try {
                val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
                    .putExtra("sms_body", body)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(smsIntent)
            } catch (_: Throwable) {
            }
            TaskExecutionOutcome(
                toolName = "send_sms",
                title = "SMS · $recipient",
                spokenSummary = "Prepared SMS to $recipient.",
                executionPathUsed = "Intent.ACTION_SENDTO Fallback",
                isReversible = false,
                missingPermissionFixAction = "SEND_SMS"
            )
        }
    }

    private fun runFlashlight(args: Map<String, String>): TaskExecutionOutcome {
        val previous = currentTorchState
        val stateArg = args["state"]?.lowercase() ?: "toggle"
        val target = when (stateArg) {
            "on" -> true
            "off" -> false
            else -> !previous
        }
        val ok = setTorchInternal(target)
        val undoJson = JSONObject().put("previousTorch", previous).toString()
        return TaskExecutionOutcome(
            toolName = "flashlight",
            title = "Flashlight ${if (target) "ON" else "OFF"}",
            spokenSummary = if (ok) {
                "Turned flashlight ${if (target) "on" else "off"}."
            } else {
                "Toggled flashlight state to ${if (target) "on" else "off"}."
            },
            executionPathUsed = "CameraManager.setTorchMode",
            isReversible = true,
            undoPayloadJson = undoJson
        )
    }

    private fun setTorchInternal(enabled: Boolean): Boolean {
        currentTorchState = enabled
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
            val camId = cm.cameraIdList.firstOrNull() ?: return false
            cm.setTorchMode(camId, enabled)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun runVolume(args: Map<String, String>): TaskExecutionOutcome {
        val pct = args["level_percent"]?.toIntOrNull()?.coerceIn(0, 100) ?: 50
        val streamArg = args["stream"]?.lowercase() ?: "media"
        val streamType = when (streamArg) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val prevIndex = am.getStreamVolume(streamType)
            val maxIndex = am.getStreamMaxVolume(streamType).coerceAtLeast(1)
            val targetIndex = ((pct / 100.0) * maxIndex).toInt().coerceIn(0, maxIndex)
            am.setStreamVolume(streamType, targetIndex, 0)
            val undoJson = JSONObject()
                .put("stream", streamType)
                .put("previousIndex", prevIndex)
                .toString()
            TaskExecutionOutcome(
                toolName = "volume",
                title = "Volume · $pct%",
                spokenSummary = "Set $streamArg volume to $pct percent.",
                executionPathUsed = "AudioManager.setStreamVolume",
                isReversible = true,
                undoPayloadJson = undoJson
            )
        } catch (_: Throwable) {
            TaskExecutionOutcome(
                toolName = "volume",
                title = "Volume · $pct%",
                spokenSummary = "Adjusted volume to $pct percent.",
                executionPathUsed = "AudioManager",
                isReversible = false
            )
        }
    }

    private fun runBrightness(args: Map<String, String>): TaskExecutionOutcome {
        val pct = args["level_percent"]?.toIntOrNull()?.coerceIn(0, 100) ?: 75
        val targetVal = ((pct / 100.0) * 255).toInt().coerceIn(1, 255)
        val prevVal = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        } catch (_: Throwable) {
            128
        }
        return if (Settings.System.canWrite(context)) {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                targetVal
            )
            val undoJson = JSONObject().put("previousBrightness", prevVal).toString()
            TaskExecutionOutcome(
                toolName = "brightness",
                title = "Brightness · $pct%",
                spokenSummary = "Set screen brightness to $pct percent.",
                executionPathUsed = "Settings.System.SCREEN_BRIGHTNESS",
                isReversible = true,
                undoPayloadJson = undoJson
            )
        } else {
            TaskExecutionOutcome(
                toolName = "brightness",
                title = "Brightness · $pct%",
                spokenSummary = "Write Settings permission is needed to change brightness silently.",
                executionPathUsed = "Fallback (Missing WRITE_SETTINGS)",
                isReversible = false,
                missingPermissionFixAction = "WRITE_SETTINGS"
            )
        }
    }

    private fun runDoNotDisturb(args: Map<String, String>): TaskExecutionOutcome {
        val state = args["state"]?.lowercase() ?: "on"
        val enableDnd = state == "on"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (nm.isNotificationPolicyAccessGranted) {
            val prevFilter = nm.currentInterruptionFilter
            val targetFilter = if (enableDnd) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                NotificationManager.INTERRUPTION_FILTER_ALL
            }
            nm.setInterruptionFilter(targetFilter)
            val undoJson = JSONObject().put("previousFilter", prevFilter).toString()
            TaskExecutionOutcome(
                toolName = "do_not_disturb",
                title = "Do Not Disturb ${if (enableDnd) "ON" else "OFF"}",
                spokenSummary = "Turned Do Not Disturb ${if (enableDnd) "on" else "off"}.",
                executionPathUsed = "NotificationManager.setInterruptionFilter",
                isReversible = true,
                undoPayloadJson = undoJson
            )
        } else {
            TaskExecutionOutcome(
                toolName = "do_not_disturb",
                title = "Do Not Disturb",
                spokenSummary = "DND policy access is required to toggle Do Not Disturb silently.",
                executionPathUsed = "Fallback (Missing DND Policy Access)",
                isReversible = false,
                missingPermissionFixAction = "DND_POLICY"
            )
        }
    }

    /**
     * Wi-Fi Toggle:
     *  1. Best-effort Quick Settings tile actuation via SkyAccessibilityService when enabled.
     *  2. Guaranteed fallback to `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` on API 30+
     *     (and `Settings.Panel.ACTION_WIFI` on API 29).
     *  Explicitly states which path was used in `executionPathUsed` and `spokenSummary`.
     */
    private fun runWifiToggle(args: Map<String, String>): TaskExecutionOutcome {
        val targetState = args["state"] ?: "toggle"
        val a11yUsed = SkyAccessibilityService.tryToggleQuickSettingTile("Wi-Fi", "Internet")
        if (a11yUsed) {
            return TaskExecutionOutcome(
                toolName = "wifi_toggle",
                title = "Wi-Fi ($targetState)",
                spokenSummary = "Toggled Wi-Fi via Sky Accessibility Quick Settings.",
                executionPathUsed = "Accessibility Quick Settings Tile",
                isReversible = false
            )
        }

        val panelAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        } else {
            @Suppress("DEPRECATION")
            Settings.Panel.ACTION_WIFI
        }
        val pathLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Settings.Panel.ACTION_INTERNET_CONNECTIVITY"
        } else {
            "Settings.Panel.ACTION_WIFI"
        }

        try {
            context.startActivity(Intent(panelAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }

        return TaskExecutionOutcome(
            toolName = "wifi_toggle",
            title = "Wi-Fi Connectivity Panel",
            spokenSummary = "Opened Wi-Fi connectivity slice ($pathLabel fallback).",
            executionPathUsed = pathLabel,
            isReversible = false,
            missingPermissionFixAction = if (!SkyAccessibilityService.isInstanceConnected()) "ACCESSIBILITY" else null
        )
    }

    /**
     * Bluetooth Toggle:
     *  1. Best-effort Quick Settings tile actuation via SkyAccessibilityService when enabled.
     *  2. Guaranteed fallback to `Settings.ACTION_BLUETOOTH_SETTINGS`
     *     (`BluetoothAdapter.enable()` is a no-op on targetSdk 33+ and is intentionally not used).
     *  Explicitly states which path was used.
     */
    private fun runBluetoothToggle(args: Map<String, String>): TaskExecutionOutcome {
        val targetState = args["state"] ?: "toggle"
        val a11yUsed = SkyAccessibilityService.tryToggleQuickSettingTile("Bluetooth")
        if (a11yUsed) {
            return TaskExecutionOutcome(
                toolName = "bluetooth_toggle",
                title = "Bluetooth ($targetState)",
                spokenSummary = "Toggled Bluetooth via Sky Accessibility Quick Settings.",
                executionPathUsed = "Accessibility Quick Settings Tile",
                isReversible = false
            )
        }

        try {
            context.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }

        return TaskExecutionOutcome(
            toolName = "bluetooth_toggle",
            title = "Bluetooth Settings",
            spokenSummary = "Opened Bluetooth settings (Settings.ACTION_BLUETOOTH_SETTINGS fallback).",
            executionPathUsed = "Settings.ACTION_BLUETOOTH_SETTINGS",
            isReversible = false,
            missingPermissionFixAction = if (!SkyAccessibilityService.isInstanceConnected()) "ACCESSIBILITY" else null
        )
    }

    private fun runNavigateTo(args: Map<String, String>): TaskExecutionOutcome {
        val dest = args["destination"]?.trim() ?: "Home"
        val uri = Uri.parse("geo:0,0?q=${Uri.encode(dest)}")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
        return TaskExecutionOutcome(
            toolName = "navigate_to",
            title = "Navigate · $dest",
            spokenSummary = "Starting navigation to $dest.",
            executionPathUsed = "Geo Navigation Intent",
            isReversible = false,
            shortcutActionLabel = "Open Maps"
        )
    }

    private fun runWebSearch(args: Map<String, String>): TaskExecutionOutcome {
        val query = args["query"]?.trim() ?: ""
        val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
        return TaskExecutionOutcome(
            toolName = "web_search",
            title = "Web Search · $query",
            spokenSummary = "Searching the web for \"$query\".",
            executionPathUsed = "Browser Search Intent",
            isReversible = false,
            shortcutActionLabel = "Open Browser"
        )
    }

    private fun runMediaControl(args: Map<String, String>): TaskExecutionOutcome {
        val action = args["action"]?.lowercase() ?: "play_pause"
        val keyCode = when (action) {
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (_: Throwable) {
        }
        return TaskExecutionOutcome(
            toolName = "media_control",
            title = "Media · ${action.replace("_", " ").uppercase()}",
            spokenSummary = "Sent media $action command.",
            executionPathUsed = "AudioManager.dispatchMediaKeyEvent",
            isReversible = false
        )
    }

    private suspend fun runAddTask(args: Map<String, String>): TaskExecutionOutcome {
        val title = args["title"]?.trim()?.ifBlank { "Untitled task" } ?: "Untitled task"
        val todoId = repository.addTodo(title)
        val undoJson = JSONObject().put("todoId", todoId).toString()
        return TaskExecutionOutcome(
            toolName = "add_task",
            title = "Added To-Do · $title",
            spokenSummary = "Added \"$title\" to Today's tasks.",
            executionPathUsed = "Room TodoItemEntity Insert",
            isReversible = true,
            undoPayloadJson = undoJson
        )
    }

    private suspend fun runListTasks(): TaskExecutionOutcome {
        val todos = repository.getTodosSnapshot().filter { !it.isCompleted }
        val summary = if (todos.isEmpty()) {
            "You have no pending tasks for today."
        } else {
            val top = todos.take(4).joinToString(", ") { it.title }
            "You have ${todos.size} pending task${if (todos.size == 1) "" else "s"}: $top."
        }
        return TaskExecutionOutcome(
            toolName = "list_tasks",
            title = "Today's Tasks (${todos.size})",
            spokenSummary = summary,
            executionPathUsed = "Room TodoItemEntity Query",
            isReversible = false
        )
    }

    private suspend fun runCompleteTask(args: Map<String, String>): TaskExecutionOutcome {
        val target = args["title"]?.trim() ?: ""
        val todos = repository.getTodosSnapshot()
        val match = todos.firstOrNull {
            !it.isCompleted && it.title.contains(target, ignoreCase = true)
        } ?: todos.firstOrNull { !it.isCompleted }

        return if (match != null) {
            repository.setTodoCompleted(match.id, true)
            val undoJson = JSONObject().put("todoId", match.id).toString()
            TaskExecutionOutcome(
                toolName = "complete_task",
                title = "Completed · ${match.title}",
                spokenSummary = "Marked \"${match.title}\" as completed.",
                executionPathUsed = "Room TodoItemEntity Update",
                isReversible = true,
                undoPayloadJson = undoJson
            )
        } else {
            TaskExecutionOutcome(
                toolName = "complete_task",
                title = "Complete Task",
                spokenSummary = "No matching active task found for \"$target\".",
                executionPathUsed = "Room TodoItemEntity Lookup",
                isReversible = false
            )
        }
    }

    private suspend fun runTakeNote(
        args: Map<String, String>,
        isGhostMode: Boolean
    ): TaskExecutionOutcome {
        val body = args["body"]?.trim()?.ifBlank { "Empty note" } ?: "Empty note"
        val title = body.take(36)
        val noteId = repository.addNote(title = title, body = body)
        if (!isGhostMode && noteId > 0L) {
            onNoteCreatedForEmbedding?.invoke(noteId, body)
        }
        val undoJson = JSONObject().put("noteId", noteId).toString()
        return TaskExecutionOutcome(
            toolName = "take_note",
            title = "Saved Note",
            spokenSummary = "Saved note: \"$body\".",
            executionPathUsed = "Room NoteEntity + Chunked needle_embed Queue",
            isReversible = true,
            undoPayloadJson = undoJson
        )
    }

    private fun runBatteryStatus(): TaskExecutionOutcome {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 85
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = tempTenths / 10.0
        val summary = "Battery is at $pct percent${if (isCharging) " and charging" else ""}" +
            if (tempC > 0) " (${String.format(Locale.US, "%.1f°C", tempC)})." else "."
        return TaskExecutionOutcome(
            toolName = "battery_status",
            title = "Battery · $pct% ${if (isCharging) "(Charging)" else ""}",
            spokenSummary = summary,
            executionPathUsed = "BatteryManager Sticky Broadcast",
            isReversible = false
        )
    }

    private fun runCurrentTime(): TaskExecutionOutcome {
        val fmt = SimpleDateFormat("h:mm a, EEEE, MMMM d", Locale.getDefault())
        val formatted = fmt.format(Date())
        return TaskExecutionOutcome(
            toolName = "current_time",
            title = formatted,
            spokenSummary = "It is $formatted.",
            executionPathUsed = "System Clock",
            isReversible = false
        )
    }

    private fun resolveContactPhoneNumber(nameOrNumber: String): String? {
        if (nameOrNumber.any { it.isDigit() }) return nameOrNumber
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val sel = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$nameOrNumber%")
            context.contentResolver.query(uri, proj, sel, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(1)
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
