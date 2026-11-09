# Sky (`com.sky.assistant`) — Milestone Progress Report

## Milestone Status Summary

| Milestone | Status | Verification |
| :--- | :---: | :--- |
| **M0 — Native Spike & Role Verification** | **COMPLETE** | Verified `needle_init`, `needle_transcribe` (`whistle.cact`), `needle_complete` (`needle3.cact`), `needle_embed` (384-d cosine similarity), `llama.cpp` GGUF inspection/streaming, and `Settings.ACTION_VOICE_INPUT_SETTINGS` assistant role detection. |
| **M1 — Chat, Neo 3D UI & Hugging Face Model Hub** | **COMPLETE** | Home Chat with empty state, drawer navigation, Ghost Mode (zero DB/embedding/log persistence), `Sky` / `Clay` / `Graphite` Light & Dark themes (verified WCAG $\ge 4.5:1$ contrast), Hugging Face Hub search + OAuth PKCE + PAT paste + resumable HTTP `Range` downloader + SAF local import. |
| **M2 — Voice Overlay, Risk-Tiered Tasks & Playground** | **COMPLETE** | `SkyVoiceInteractionService` + `SkyVoiceInteractionSession`, `SkyAssistActivity`, Quick Settings Tile, Homescreen Widget, 21 built-in tools with Tier 1 ($\ge 0.70$ / $\ge 0.45$ trigger floor) & Tier 2 ($\ge 0.85$ + 3s cancelable countdown + lockscreen gate), reversible-only Undo, and Tool Playground diagnostic card. |
| **M3 — Guided Setup Wizard, Siri Glow/Orb & TTS Queue** | **COMPLETE** | 6-step Guided Setup Wizard (with Android 13+ *"Allow restricted settings"* Accessibility step), 60 fps Siri Glow (AGSL `RuntimeShader` on API 33+ with Canvas fallback on API 29–32) + Siri Orb, and sentence-pipelined `TextToSpeech` with barge-in cancellation. |
| **M4 — Semantic Search (`needle_embed`) & Screen Context (`onHandleAssist`)** | **COMPLETE** | Chunked non-preemptible `needle_embed` queue (defers new jobs while voice session is active) powering instant cosine-similarity search across chats & notes, plus *"Ask about this screen"* (`AssistStructure` walker) routed strictly to Reply GGUF with all tools disabled. |

---

## What Works Out of the Box

1. **Power-Button & Multi-Entry Wake**:
   - System `VoiceInteractionService` / `VoiceInteractionSessionService` overlay when Sky is selected as the Default Digital Assistant.
   - Translucent `SkyAssistActivity` (`Intent.ACTION_ASSIST` / `ACTION_VOICE_COMMAND`), Quick Settings Tile, 4x1 Homescreen Widget, and launcher long-press shortcuts.
2. **Two-Stage Speech-to-Action Pipeline**:
   - 16 kHz mono PCM `AudioRecord` with 650 ms silence endpointing and live RMS waveform feeding the Siri Glow/Orb visualizer.
   - Stage 1 (`whistle.cact`) produces the transcript; Stage 2 (`needle3.cact`) performs contrastive top-5 tool retrieval across the 21-tool catalog and returns a grammar-constrained JSON tool call + confidence score.
3. **21 Built-In Tools with Honest Reversibility & Tier 2 Safety**:
   - **Reversible Tier 1 Tools (with working `Undo`)**: `flashlight`, `volume`, `brightness`, `do_not_disturb`, `add_task`, `complete_task`, `take_note`, `create_calendar_event`.
   - **Non-Reversible Tier 1 Tools (with quick app shortcut instead of fake Undo)**: `set_alarm` (*Open Clock*), `set_timer` (*Open Clock*), `open_app`, `navigate_to` (*Open Maps*), `web_search` (*Open Browser*), `wifi_toggle`, `bluetooth_toggle`, `media_control`, `list_tasks`, `battery_status`, `current_time`.
   - **Tier 2 Communication Tools (`send_sms`, `place_call`)**: Require confidence $\ge 0.85$, block on lockscreen until unlocked, and display a 3-second cancelable countdown card (with optional per-tool *"Run instantly"* override in Settings).
4. **Thermal & Memory Discipline**:
   - Reply GGUF weights load **only after** the router returns `RouteToReplyModel` (or during direct chat / screen-context Q&A), avoiding CPU/RAM contention with Whistle and Needle during voice wake.

---

## OEM-Dependent Behaviors & Known Platform Constraints

- **Default Assistant Selection (`ROLE_ASSISTANT`)**: Android does not permit third-party apps to request `ROLE_ASSISTANT` via `RoleManager.createRequestRoleIntent`. Users must select Sky once in *Settings → Apps → Default apps → Digital assistant app* (automated via Step 2 of the in-app Setup Wizard).
- **Android 13+ Sideloaded Accessibility Gate**: When installed via APK sideload rather than an app store, Android 13+ grays out the Accessibility toggle until the user enables *"Allow restricted settings"* in Sky's App Info screen (Step 6 of the Setup Wizard walks through both screens).
- **Wi-Fi & Bluetooth Toggling on API 29+**: Android 10+ deprecated silent background `WifiManager.setWifiEnabled` and Android 13+ deprecated `BluetoothAdapter.enable()`. When Sky's Accessibility Service is enabled, Sky toggles the Quick Settings tile; otherwise it opens `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` (Wi-Fi) or `Settings.ACTION_BLUETOOTH_SETTINGS` (Bluetooth) immediately.
