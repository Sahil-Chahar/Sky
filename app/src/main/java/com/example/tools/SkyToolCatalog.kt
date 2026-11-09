package com.example.tools

import com.example.data.local.ToolRiskTier
import org.json.JSONArray
import org.json.JSONObject

data class SkyToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null
)

data class SkyToolDefinition(
    val name: String,
    val title: String,
    val description: String,
    val riskTier: ToolRiskTier,
    val isReversible: Boolean,
    val shortcutActionLabel: String? = null,
    val triggerKeywords: List<String>,
    val parameters: List<SkyToolParam>
) {
    fun toCompactSchemaJson(): JSONObject {
        val props = JSONObject()
        val reqArr = JSONArray()
        for (p in parameters) {
            val pObj = JSONObject()
                .put("type", p.type)
                .put("description", p.description)
            if (!p.enumValues.isNullOrEmpty()) {
                pObj.put("enum", JSONArray(p.enumValues))
            }
            props.put(p.name, pObj)
            if (p.required) reqArr.put(p.name)
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", reqArr)
            )
    }
}

data class ParsedToolCall(
    val toolName: String,
    val arguments: Map<String, String>,
    val argumentsJson: String,
    val confidence: Float,
    val matchedTriggerKeyword: Boolean
)

sealed class RouteAction {
    data class ExecuteImmediately(
        val call: ParsedToolCall,
        val tool: SkyToolDefinition
    ) : RouteAction()

    data class StartTier2Countdown(
        val call: ParsedToolCall,
        val tool: SkyToolDefinition,
        val countdownSeconds: Int = 3
    ) : RouteAction()

    data class RequireUnlockForTier2(
        val call: ParsedToolCall,
        val tool: SkyToolDefinition
    ) : RouteAction()

    data class RouteToReplyModel(
        val reason: String,
        val toolsDisabledForScreenContext: Boolean = false
    ) : RouteAction()
}

/**
 * 21 single-purpose device tools designed per Cactus Compute's Needle 3 tool-design guide:
 *  - One tool per action
 *  - Natural spoken names & enum values
 *  - Explicit format patterns in parameter descriptions
 *  - > 5 tools declared so Needle's contrastive tool retrieval automatically selects the top 5
 */
object SkyToolCatalog {

    const val TIER1_CONFIDENCE_THRESHOLD = 0.70f
    const val TIER1_TRIGGER_FLOOR = 0.45f
    const val TIER2_CONFIDENCE_THRESHOLD = 0.85f

    val BuiltInTools: List<SkyToolDefinition> = listOf(
        SkyToolDefinition(
            name = "set_alarm",
            title = "Set Alarm",
            description = "Set a wake-up or reminder alarm for a specific hour and minute.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = false,
            shortcutActionLabel = "Open Clock",
            triggerKeywords = listOf("alarm", "wake me"),
            parameters = listOf(
                SkyToolParam("hour", "integer", "24-hour clock hour (0-23), e.g., 7 or 19"),
                SkyToolParam("minute", "integer", "Minute (0-59), e.g., 30"),
                SkyToolParam("label", "string", "Optional spoken label, e.g., Workout", required = false)
            )
        ),
        SkyToolDefinition(
            name = "set_timer",
            title = "Set Timer",
            description = "Start a countdown timer for a duration in seconds or minutes.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = false,
            shortcutActionLabel = "Open Clock",
            triggerKeywords = listOf("timer", "countdown"),
            parameters = listOf(
                SkyToolParam("seconds", "integer", "Total duration in seconds, e.g., 300 for 5 minutes"),
                SkyToolParam("label", "string", "Optional timer label, e.g., Pasta", required = false)
            )
        ),
        SkyToolDefinition(
            name = "create_calendar_event",
            title = "Create Calendar Event",
            description = "Schedule a new event on the user's calendar.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = true,
            triggerKeywords = listOf("calendar", "schedule", "meeting", "appointment"),
            parameters = listOf(
                SkyToolParam("title", "string", "Event title, e.g., Dentist appointment"),
                SkyToolParam("start_offset_minutes", "integer", "Minutes from now to start, e.g., 60"),
                SkyToolParam("duration_minutes", "integer", "Event duration in minutes, e.g., 30", required = false)
            )
        ),
        SkyToolDefinition(
            name = "open_app",
            title = "Open App",
            description = "Launch an installed application by its spoken name.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = false,
            triggerKeywords = listOf("open", "launch"),
            parameters = listOf(
                SkyToolParam("app_name", "string", "Spoken application name, e.g., Camera, Maps, Settings, YouTube")
            )
        ),
        SkyToolDefinition(
            name = "place_call",
            title = "Call Contact",
            description = "Place a phone call to a contact name or phone number.",
            riskTier = ToolRiskTier.TIER_2,
            isReversible = false,
            triggerKeywords = listOf("call", "dial", "phone"),
            parameters = listOf(
                SkyToolParam("recipient", "string", "Contact name or phone number, e.g., Mom or 555-0192")
            )
        ),
        SkyToolDefinition(
            name = "send_sms",
            title = "Send Text Message (SMS)",
            description = "Send an SMS text message to a contact or phone number.",
            riskTier = ToolRiskTier.TIER_2,
            isReversible = false,
            triggerKeywords = listOf("text", "sms", "message"),
            parameters = listOf(
                SkyToolParam("recipient", "string", "Contact name or phone number, e.g., Alex"),
                SkyToolParam("message", "string", "Exact SMS body text, e.g., On my way home")
            )
        ),
        SkyToolDefinition(
            name = "flashlight",
            title = "Flashlight / Torch",
            description = "Turn the rear camera flashlight on or off.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = true,
            triggerKeywords = listOf("flashlight", "torch", "light on", "light off"),
            parameters = listOf(
                SkyToolParam("state", "string", "Target state", enumValues = listOf("on", "off", "toggle"))
            )
        ),
        SkyToolDefinition(
            name = "volume",
            title = "Adjust Volume",
            description = "Change or mute the device audio volume level.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = true,
            triggerKeywords = listOf("volume", "mute", "unmute", "louder", "quieter"),
            parameters = listOf(
                SkyToolParam("level_percent", "integer", "Target volume percentage (0-100), e.g., 50"),
                SkyToolParam(
                    "stream",
                    "string",
                    "Audio stream",
                    required = false,
                    enumValues = listOf("media", "ring", "alarm")
                )
            )
        ),
        SkyToolDefinition(
            name = "brightness",
            title = "Screen Brightness",
            description = "Set the display brightness percentage.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = true,
            triggerKeywords = listOf("brightness", "dim screen", "brighten"),
            parameters = listOf(
                SkyToolParam("level_percent", "integer", "Target brightness percentage (0-100), e.g., 80")
            )
        ),
        SkyToolDefinition(
            name = "do_not_disturb",
            title = "Do Not Disturb",
            description = "Enable or disable Do Not Disturb silence mode.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = true,
            triggerKeywords = listOf("do not disturb", "dnd", "silence notifications"),
            parameters = listOf(
                SkyToolParam("state", "string", "Target DND state", enumValues = listOf("on", "off"))
            )
        ),
        SkyToolDefinition(
            name = "wifi_toggle",
            title = "Wi-Fi Toggle",
            description = "Turn Wi-Fi on or off via Accessibility Quick Settings or Connectivity Panel.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = false,
            triggerKeywords = listOf("wi-fi", "wifi"),
            parameters = listOf(
                SkyToolParam("state", "string", "Target Wi-Fi state", enumValues = listOf("on", "off", "toggle"))
            )
        ),
        SkyToolDefinition(
            name = "bluetooth_toggle",
            title = "Bluetooth Toggle",
            description = "Turn Bluetooth on or off via Accessibility Quick Settings or Bluetooth Settings.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = false,
            triggerKeywords = listOf("bluetooth"),
            parameters = listOf(
                SkyToolParam("state", "string", "Target Bluetooth state", enumValues = listOf("on", "off", "toggle"))
            )
        ),
        SkyToolDefinition(
            name = "navigate_to",
            title = "Navigate To Destination",
            description = "Start turn-by-turn map navigation to an address or place.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = false,
            shortcutActionLabel = "Open Maps",
            triggerKeywords = listOf("navigate", "directions to", "take me to"),
            parameters = listOf(
                SkyToolParam("destination", "string", "Place or address, e.g., SFO Airport or Coffee shop")
            )
        ),
        SkyToolDefinition(
            name = "web_search",
            title = "Web Search",
            description = "Search the web in the browser for a query.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = false,
            shortcutActionLabel = "Open Browser",
            triggerKeywords = listOf("search the web", "google", "search for"),
            parameters = listOf(
                SkyToolParam("query", "string", "Search query string, e.g., Kotlin 2.2 release notes")
            )
        ),
        SkyToolDefinition(
            name = "media_control",
            title = "Media Playback Control",
            description = "Play, pause, skip next, or go to previous track for active media.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = false,
            triggerKeywords = listOf("pause music", "resume music", "next song", "next track", "previous track", "play music"),
            parameters = listOf(
                SkyToolParam("action", "string", "Media command", enumValues = listOf("play_pause", "next", "previous"))
            )
        ),
        SkyToolDefinition(
            name = "add_task",
            title = "Add To-Do Task",
            description = "Add an item to Today's local to-do list.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = true,
            triggerKeywords = listOf("add task", "add to-do", "add todo", "remind me to"),
            parameters = listOf(
                SkyToolParam("title", "string", "Task description, e.g., Buy oat milk")
            )
        ),
        SkyToolDefinition(
            name = "list_tasks",
            title = "List To-Do Tasks",
            description = "Read out and display Today's pending to-do tasks.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = false,
            triggerKeywords = listOf("list tasks", "my tasks", "my to-dos", "what's on my to-do"),
            parameters = emptyList()
        ),
        SkyToolDefinition(
            name = "complete_task",
            title = "Complete To-Do Task",
            description = "Mark a matching task on Today's to-do list as completed.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = true,
            triggerKeywords = listOf("complete task", "check off", "mark done"),
            parameters = listOf(
                SkyToolParam("title", "string", "Task name to mark complete, e.g., Buy oat milk")
            )
        ),
        SkyToolDefinition(
            name = "take_note",
            title = "Save Note",
            description = "Save a quick voice or text note locally.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = true,
            triggerKeywords = listOf("take a note", "save a note", "note to self"),
            parameters = listOf(
                SkyToolParam("body", "string", "Note content, e.g., Door code is 4829")
            )
        ),
        SkyToolDefinition(
            name = "battery_status",
            title = "Battery Status",
            description = "Check current battery percentage and charging status.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = false,
            triggerKeywords = listOf("battery", "charge level"),
            parameters = emptyList()
        ),
        SkyToolDefinition(
            name = "current_time",
            title = "Current Time & Date",
            description = "Speak the current local time and date.",
            riskTier = ToolRiskTier.TIER_1,
            isReversible = false,
            triggerKeywords = listOf("what time is it", "current time", "what's today's date"),
            parameters = emptyList()
        )
    )

    fun byName(name: String): SkyToolDefinition? =
        BuiltInTools.find { it.name.equals(name, ignoreCase = true) }

    fun toAllToolsJsonString(disabledTools: Set<String> = emptySet()): String {
        val arr = JSONArray()
        for (tool in BuiltInTools) {
            if (tool.name !in disabledTools) {
                arr.put(tool.toCompactSchemaJson())
            }
        }
        return arr.toString()
    }

    /**
     * Contrastive Top-5 Tool Retrieval:
     * When > 5 tools are registered, scores all enabled tools against the user prompt
     * and returns the top 5 matching schemas for Needle's grammar-constrained context.
     */
    fun retrieveTop5ToolsForQuery(
        query: String,
        disabledTools: Set<String> = emptySet()
    ): List<SkyToolDefinition> {
        val enabled = BuiltInTools.filter { it.name !in disabledTools }
        if (enabled.size <= 5) return enabled
        val lower = query.lowercase()
        return enabled
            .map { tool ->
                var score = 0
                for (kw in tool.triggerKeywords) {
                    if (lower.contains(kw)) score += 10
                }
                if (lower.contains(tool.name.replace("_", " "))) score += 8
                for (word in tool.description.lowercase().split(" ")) {
                    if (word.length > 3 && lower.contains(word)) score += 1
                }
                tool to score
            }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
    }

    /**
     * Evaluates risk-tiered routing for a candidate tool call:
     *  - If [isScreenContextQuery] is true ("Ask about this screen" via onHandleAssist),
     *    untrusted screen text MUST NEVER steer tool calls -> always returns
     *    [RouteAction.RouteToReplyModel] with `toolsDisabledForScreenContext = true`.
     *  - Tier 1: executes immediately at confidence >= 0.70, or >= 0.45 when an unambiguous
     *    trigger keyword matches.
     *  - Tier 2 (`send_sms`, `place_call`): requires confidence >= 0.85. Requires unlock
     *    if [isDeviceLocked] is true. Shows 3-second cancelable countdown unless overridden
     *    in [instantTier2Tools].
     */
    fun evaluateToolRouteDecision(
        candidateCall: ParsedToolCall?,
        isScreenContextQuery: Boolean,
        isDeviceLocked: Boolean,
        disabledTools: Set<String> = emptySet(),
        instantTier2Tools: Set<String> = emptySet()
    ): RouteAction {
        if (isScreenContextQuery) {
            return RouteAction.RouteToReplyModel(
                reason = "Screen context query: tools disabled for untrusted AssistStructure input",
                toolsDisabledForScreenContext = true
            )
        }

        if (candidateCall == null) {
            return RouteAction.RouteToReplyModel(reason = "No tool call emitted by Needle")
        }

        val toolDef = byName(candidateCall.toolName)
            ?: return RouteAction.RouteToReplyModel(reason = "Unknown tool: ${candidateCall.toolName}")

        if (toolDef.name in disabledTools) {
            return RouteAction.RouteToReplyModel(reason = "Tool ${toolDef.name} is disabled in settings")
        }

        return when (toolDef.riskTier) {
            ToolRiskTier.TIER_1 -> {
                val threshold = if (candidateCall.matchedTriggerKeyword) {
                    TIER1_TRIGGER_FLOOR
                } else {
                    TIER1_CONFIDENCE_THRESHOLD
                }
                if (candidateCall.confidence >= threshold) {
                    RouteAction.ExecuteImmediately(candidateCall, toolDef)
                } else {
                    RouteAction.RouteToReplyModel(
                        reason = "Tier 1 confidence ${candidateCall.confidence} below $threshold"
                    )
                }
            }

            ToolRiskTier.TIER_2 -> {
                if (candidateCall.confidence < TIER2_CONFIDENCE_THRESHOLD) {
                    return RouteAction.RouteToReplyModel(
                        reason = "Tier 2 confidence ${candidateCall.confidence} below $TIER2_CONFIDENCE_THRESHOLD"
                    )
                }
                if (isDeviceLocked) {
                    return RouteAction.RequireUnlockForTier2(candidateCall, toolDef)
                }
                if (toolDef.name in instantTier2Tools) {
                    RouteAction.ExecuteImmediately(candidateCall, toolDef)
                } else {
                    RouteAction.StartTier2Countdown(
                        call = candidateCall,
                        tool = toolDef,
                        countdownSeconds = 3
                    )
                }
            }
        }
    }
}
