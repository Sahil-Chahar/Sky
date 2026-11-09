# Sky · On-Device Android AI Assistant (`com.sky.assistant`)

**Sky** is a 100% on-device Android digital assistant and conversational AI built with Kotlin, Jetpack Compose, and a custom **Neo 3D Neumorphic** design system (`Sky`, `Clay`, and `Graphite` palettes in Light and Dark modes).

Long-pressing the power button wakes Sky with a 60 fps **Siri Glow** (Android 13+ AGSL `RuntimeShader` perimeter bloom with Canvas multi-stop fallback on API 29–32) or **Siri Orb** visualizer. Speech is transcribed locally at 16 kHz by **Whistle** (`whistle.cact`), routed through **Needle 3** (`needle3.cact`) for grammar-constrained tool calling and 384-d semantic embeddings, or answered by an on-device **Reply GGUF** model (`SmolLM2 360M Instruct Q8_0`, `Qwen 2.5 1.5B`, `Llama 3.2 1B`, `Gemma 2 2B`) via `llama.cpp`.

---

## Quick Install & Sideload Guide

### Option A: Install via ADB (Computer + USB Debugging)
```bash
# Build and install directly onto a connected arm64-v8a Android 10+ device:
./gradlew installDebug -PenableNativeBuild=true

# Or install a pre-built APK downloaded from GitHub Actions:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Option B: Direct On-Device Sideload (No Computer Required)
1. Open the **Actions** tab of this GitHub repository on your Android phone.
2. Select the latest **Build Sky Android APK** workflow run and download the `sky-assistant-debug-apk` artifact.
3. Extract `app-debug.apk` and tap it in your Android **Files** app (grant *"Install unknown apps"* if prompted).

---

## Setting Sky as Your Power-Button Digital Assistant

Android restricts third-party apps from programmatically claiming `RoleManager.ROLE_ASSISTANT`. Use Sky's in-app **Guided Setup Wizard** (*Drawer → Sky Voice · Hands-free → Setup Wizard*) to step through the system settings:

1. **Step 1 — Runtime Permissions**: Grants Microphone (`RECORD_AUDIO`), Contacts (`READ_CONTACTS`), Phone (`CALL_PHONE`), SMS (`SEND_SMS`), Calendar (`READ_CALENDAR` / `WRITE_CALENDAR`), and Notifications (`POST_NOTIFICATIONS`) in a single OS batch dialog.
2. **Step 2 — Default Digital Assistant (Power-Button Long-Press)**:
   - Tap **"Open Digital Assistant Settings"** in the wizard (deep-links to `Settings.ACTION_VOICE_INPUT_SETTINGS`).
   - **Google Pixel / Stock Android**: *Settings → Apps → Default apps → Digital assistant app → Default digital assistant app → Select **Sky***. Then verify *Settings → System → Gestures → Press & hold power button → Digital assistant* is enabled.
   - **Samsung One UI**: *Settings → Apps → Choose default apps → Digital assistant app → Device assistant app → Select **Sky***. Under *Settings → Advanced features → Side button → Press and hold*, select **Digital assistant**.
3. **Step 3 — Modify System Settings (`WRITE_SETTINGS`)**: Allows silent screen brightness adjustment.
4. **Step 4 — Do Not Disturb Access (`ACCESS_NOTIFICATION_POLICY`)**: Allows silent DND toggling.
5. **Step 5 — Unrestricted Battery**: Prevents Android from evicting warm-loaded `needle3.cact` and `whistle.cact` weights.
6. **Step 6 — Sky Accessibility Service (Android 13+ Sideload Note)**:
   - On **Android 13+ (API 33+)**, Android blocks enabling Accessibility services for sideloaded APKs until you explicitly allow restricted settings:
     1. In the wizard, tap **"1. Open App Info (Allow Restricted)"**.
     2. Tap the **⋮** (three-dot menu) in the top-right corner of Sky's App Info screen and select **"Allow restricted settings"** (confirm with your PIN/biometric).
     3. Return to Sky and tap **"2. Open Accessibility Settings"** to enable **Sky Assistant**.

---

## Hugging Face Authentication (OAuth 2.0 PKCE + Personal Access Token)

All Starter Pack models (`Cactus-Compute/needle3`, `Cactus-Compute/whistle`, and `HuggingFaceTB/SmolLM2-360M-Instruct-GGUF`) and curated community GGUFs (`Qwen`, `bartowski`) download with **zero sign-in required**.

To download gated repositories (such as official `meta-llama/Llama-3.2-1B-Instruct` or `google/gemma-2-2b-it`):
1. **Personal Access Token (Instant)**:
   - Create a `read` token at `https://huggingface.co/settings/tokens`.
   - Open *Drawer → Models → Sign In / Token* and paste `hf_...`. Sky verifies the token via `GET https://huggingface.co/api/whoami-v2` and stores it encrypted with Android Keystore `AES/GCM/NoPadding`.
2. **OAuth 2.0 + PKCE (`com.sky.assistant://oauth-callback`)**:
   - Register a public Developer Application at `https://huggingface.co/settings/applications/new` with:
     - **Redirect URI**: `com.sky.assistant://oauth-callback`
     - **Scopes**: `openid profile read-repos`
   - Paste your Client ID in *Drawer → Models → Sign In / Token* and tap **Launch Browser OAuth (PKCE)**.
3. **Gated Model License Acceptance**:
   - If a download returns HTTP `401` or `403`, Sky displays an inline **"Accept License on Hugging Face"** button that opens `https://huggingface.co/<org>/<repo>` in your browser and provides a one-tap **Retry Download** button.

---

## Architecture Overview

| Layer | Implementation |
| :--- | :--- |
| **Assistant Entry Points** | `SkyVoiceInteractionService`, `SkyVoiceInteractionSessionService`, `SkyVoiceInteractionSession` (`onShow`, `onHide`, `onHandleAssist`), `SkyRecognitionService`, `SkyAssistActivity` (`ACTION_ASSIST`), `SkyQuickSettingsTileService`, `SkyAppWidgetProvider`, static `shortcuts.xml` |
| **Speech-to-Action Pipeline** | 16 kHz mono PCM `AudioRecord` + 650 ms VAD endpointing → `whistle.cact` (`needle_transcribe`) → `needle3.cact` (`needle_complete`) on a dedicated single-thread native executor (`Sky-Needle-SingleThread`) |
| **Risk-Tiered Silent Executor** | **21 built-in tools**: **Tier 1** executes immediately at confidence $\ge 0.70$ (or $\ge 0.45$ with trigger keyword) and shows **Undo** strictly where reversible (`flashlight`, `volume`, `brightness`, `do_not_disturb`, `add_task`, `complete_task`, `take_note`, `create_calendar_event`); **Tier 2** (`send_sms`, `place_call`) requires confidence $\ge 0.85$, device unlock, and a **3-second cancelable countdown** |
| **Conversational Reply Engine** | `llama.cpp` JNI (`libllama_jni.so`), loaded **only after the router returns `RouteToReplyModel`** so Reply GGUF weights never contend with Whistle/Needle during voice wake |
| **Screen Context Security** | *"Ask about this screen"* (`onHandleAssist` / `AssistStructure`) routes strictly to the Reply GGUF with **all tools disabled** so untrusted on-screen text cannot steer tool calls |
| **16 KB Page Alignment** | Configured in `app/src/main/cpp/CMakeLists.txt` with `-Wl,-z,max-page-size=16384` and `useLegacyPackaging = false` for Android 15/16 compatibility |
