# Sky — On-Device Android Voice Assistant & AI Chat

Sky (`com.sky.assistant`) is a privacy-first Android digital assistant and conversational AI app with **100% on-device AI inference** (no cloud LLM or speech servers; network access is used solely for Hugging Face model downloads and explicit user actions like `web_search`), crafted with a calm **Neo 3D Neumorphic** design system. Long-pressing the power button wakes Sky with an authentic Siri-inspired perimeter glow or dynamic orb animation, transcribes speech via **Whistle** (`whistle.cact`), executes device tasks via **Needle 3** (`needle3.cact`) with risk-tiered silent execution and zero runtime permission dialogs, or streams rich answers from local **GGUF** language models via **llama.cpp**.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following product and technical choices incorporate your confirmed decisions, platform realities, and scope refinements:

- **Confirmed Decision — Default Reply Model**: **SmolLM2 360M Instruct Q8_0 (~380 MB)** is featured as the primary one-tap recommended Reply model alongside the ~46 MB **Starter Pack** (`needle3.cact` + `whistle.cact`). All default one-tap recommendations point to **ungated community GGUF repositories** (e.g., `HuggingFaceTB/SmolLM2-360M-Instruct-GGUF` and ungated `bartowski` quantizations for Qwen 2.5 1.5B, Llama 3.2 1B, and Gemma 2 2B) so users never hit license walls out of the box, while gated repositories (`401/403`) show a direct *"Accept license on Hugging Face"* deep-link and retry action.
- **Confirmed Decision — Hugging Face Authentication**: **Dual OAuth 2.0 + PKCE (Custom Tabs with configurable Client ID) and 1-Tap Personal Access Token Paste** (with a direct deep-link to `huggingface.co/settings/tokens`), stored using AES-GCM hardware-backed Android Keystore encryption.
- **Confirmed Decision — Stretch Capabilities & v1 Scope Trim**:
  1. **Included in M4**: **Semantic Chat Search (`needle_embed`)** via a dedicated `EmbeddingEntity` table in Room, and **"Ask About This Screen" (`onHandleAssist`)** isolated strictly to the Reply GGUF path with all tools disabled to prevent untrusted screen text from steering tool calls.
  2. **Trimmed / Deferred Out of v1**: To keep v1 focused and shippable, **Animation Lab**, **Benchmark screen**, and **User-Defined Custom JSON Tasks (including HTTP webhooks)** are moved out of v1 into **Post-v1 Extensions**, and the theme system ships in **M1 with 1 theme × 2 modes (`Sky Light` + `Sky Dark`)** built on `ThemeSpec` (with `Clay` and `Graphite` presets deferred).

### Platform & Distribution Decisions

- **SDK & Graphics Targets**: `minSdk 29` (API 29+ required for modern digital assistant role checking); `targetSdk = latest stable` (API 35/36). AGSL `RuntimeShader` on API 33+, Canvas multi-layer fallback below. For **Siri Glow** perimeter geometry, uses `WindowInsets.getRoundedCorner` on API 31+ and falls back to a standard `28.dp` display corner radius on API 29–30.
- **ABI**: `arm64-v8a` only (keeps APK small; `llama.cpp` and Cactus engines target arm64).
- **Distribution**: Sideloaded / GitHub Releases APK via the planned CI workflow. **Google Play is explicitly out of scope for v1** because Play restricts `SEND_SMS` and Accessibility-service automation.
- **Digital Assistant Role (`ROLE_ASSISTANT`)**: Third-party apps cannot request `ROLE_ASSISTANT` via `RoleManager.createRequestRoleIntent()` popup dialog. Instead, Sky deep-links to `Settings.ACTION_VOICE_INPUT_SETTINGS` (*Default apps → Digital assistant app*) and detects whether Sky currently holds the assistant role via `RoleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)` and `Settings.Secure` inspection (to be verified on-device in **M0**).
- **Sideloaded Accessibility & Restricted Settings (Android 13+)**: On Android 13+ (API 33+), Android blocks enabling Accessibility services for sideloaded APKs under *"Restricted settings"*. The Setup Wizard explicitly guides the user through *App info → ⋮ (top-right menu) → "Allow restricted settings"* before deep-linking to `Settings.ACTION_ACCESSIBILITY_SETTINGS`.
- **Wi-Fi & Bluetooth Toggles**:
  - **Wi-Fi**: `Settings.Panel.ACTION_WIFI` is deprecated on newer APIs; Sky attempts best-effort Quick Settings actuation via `SkyAccessibilityService` when enabled and uses `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` (API 30+) or `Settings.Panel.ACTION_WIFI` (API 29) as the guaranteed fallback.
  - **Bluetooth**: `BluetoothAdapter.enable()` is a no-op on `targetSdk 33+` and there is no Bluetooth `Settings.Panel`; Sky attempts best-effort Quick Settings actuation via `SkyAccessibilityService` when enabled and uses `Settings.ACTION_BLUETOOTH_SETTINGS` as the guaranteed fallback.
  - In both cases, the result card explicitly states which path was used.
- **Large Model Downloads (`targetSdk 34+` / Android 15)**: Because `foregroundServiceType="dataSync"` is subject to strict timeout limits on Android 15, resumable model downloads use **WorkManager with `setExpedited` / Android 14+ user-initiated data transfer jobs** (`JobInfo.Builder.setUserInitiated(true)` + `RUN_USER_INITIATED_JOBS` permission).

---

## 1. Overview & Core Concept

- **What It Does**:
  - **System-Wide Digital Assistant**: Registers as Android's default `VoiceInteractionService`, `VoiceInteractionSessionService`, `RecognitionService`, and `ACTION_ASSIST` handler so long-pressing the hardware power/side button launches the translucent Sky overlay over any app or lockscreen with a **target < 300 ms to overlay visible when the service is warm (measured on reference devices; not guaranteed after the system kills the service)**.
  - **Two-Stage Speech-to-Action Pipeline**: Captures 16 kHz mono PCM audio with energy-based Voice Activity Detection using a **600–700 ms silence endpoint** (default `650 ms`, 15 s max cap). **Two stages: `whistle.cact` produces the transcript, then `needle3.cact` receives the transcript text and returns a tool call.** *(Note: Grammar-constrained decoding, contrastive tool retrieval for >5 tools, and calibrated confidence scores are working assumptions from Cactus documentation to be empirically verified in **M0**; if M0 shows Needle accepts raw audio directly, collapse to one stage and update the latency breakdown.)* Preserves the full per-stage latency breakdown (`STT ms`, `Needle ms`, `Execution ms`), with end-to-end task latency measured **from end-of-speech (VAD endpoint trigger) to action execution**.
  - **Zero-Prompt Device Task Execution (Risk-Tiered)**: Executes 20+ built-in device actions with zero runtime permission dialogs. Each tool has a risk tier (with initial confidence thresholds `0.70` / `0.45` / `0.85` treated as **placeholders to be calibrated from real M0 samples**):
    - **Tier 1 (reversible/benign)**: `flashlight` [Tier 1], `volume` [Tier 1], `brightness` [Tier 1], `do_not_disturb` [Tier 1], `set_timer` [Tier 1], `set_alarm` [Tier 1], `take_note` [Tier 1], `add_task` [Tier 1], `list_tasks` [Tier 1], `complete_task` [Tier 1], `media_control` [Tier 1], `web_search` [Tier 1], `open_app` [Tier 1], `navigate_to` [Tier 1], `create_calendar_event` [Tier 1], `wifi_toggle` [Tier 1], `bluetooth_toggle` [Tier 1], `battery_status` [Tier 1], `current_time` [Tier 1]. Execute immediately at confidence $\ge 0.70$, or $\ge 0.45$ when an unambiguous trigger keyword matches (subject to M0 calibration). Show result card with **Undo only where genuinely reversible** (`volume`, `brightness`, `do_not_disturb`, `flashlight`, `add_task`, `complete_task`, `take_note`, `create_calendar_event`); for non-reversible actions (`set_alarm`, `set_timer`, `open_app`, `navigate_to`, `web_search`), show a contextual shortcut button (e.g., *"Open Clock"*) instead of claiming Undo.
    - **Tier 2 (irreversible or costly)**: `send_sms` [Tier 2] and `place_call` (`call_contact`) [Tier 2]. Require confidence $\ge 0.85$ (subject to M0 calibration) and show a **3-second cancelable countdown card** (*"Texting Mom... Cancel"*) before executing. Still no permission dialog; the countdown keeps the instant feel while preventing mis-transcription mistakes.
    - **Lockscreen Policy & Per-Tool Override**: When the device is locked, Tier 2 actions require unlock first; Tier 1 may run on the lockscreen except where the OS blocks it. Includes *Settings → Tasks → "Run instantly"* toggle per tool so the user can remove the countdown for any Tier 2 tool.
  - **Full-Featured Local LLM Chat**: Streams responses from any Hugging Face or local `.gguf` model via `llama.cpp` JNI with embedded chat template formatting, live Markdown & syntax-highlighted code blocks, collapsible `<think>` reasoning sections, inline executed task cards, private-chat (Ghost) mode, and hybrid semantic search.
- **Target Audience / Persona**: Privacy-conscious Android power users and enthusiasts who want fast, reliable voice control over their phone and local LLM intelligence without cloud LLM latency, subscriptions, or telemetry.
- **Key Value**: Fast post-speech device task execution, 100% on-device AI inference, and a tactile, high-contrast Neo 3D neumorphic UI that feels cohesive across light and dark modes.

---

## Phased Delivery

- **M0 — Native spike (gate for everything else)**: Bare JNI app on a real arm64 phone that:
  1. Transcribes one recorded 16 kHz clip with `whistle.cact`.
  2. Returns one tool call from `needle3.cact` and logs empirical confidence scores across sample prompts to calibrate the Tier 1 / Tier 2 thresholds (`0.70` / `0.45` / `0.85`).
  3. Streams tokens from one GGUF via `llama.cpp`.
  4. Verifies `ROLE_ASSISTANT` detection via `RoleManager.isRoleHeld` + `Settings.ACTION_VOICE_INPUT_SETTINGS`.
  **Do NOT start UI work until M0 passes.** Confirm the real C API names/signatures (`needle_init`, `needle_complete`, `needle_embed`), whether grammar-constrained calls / contrastive retrieval / calibrated confidence work as documented, and the licences of the `.cact` files; update this plan to match.
- **M1 — Chat + Model Hub**: Home chat, drawer, Hugging Face search/auth (OAuth PKCE + token paste, ungated defaults, `401/403` license link), resumable user-initiated data-transfer downloader, `llama.cpp` streaming, Room chat history, and the Neo 3D Neumorphic theme system starting with **1 theme × 2 modes (`Sky Light` + `Sky Dark`)**.
- **M2 — Voice + tasks**: `VoiceInteractionService` overlay (with manual `ViewTreeLifecycleOwner` / `SavedStateRegistryOwner` wiring), 650 ms VAD endpointing, Whistle $\rightarrow$ Needle pipeline, 8 core tools (`set_alarm` [Tier 1], `set_timer` [Tier 1], `place_call` [Tier 2], `send_sms` [Tier 2], `flashlight` [Tier 1], `volume` [Tier 1], `open_app` [Tier 1], `web_search` [Tier 1]), result card (with Undo on reversible tools + 3s countdown on Tier 2), and Tool Playground.
- **M3 — Polish**: Guided Setup Wizard sequence (including Android 13+ *"Allow restricted settings"* step), Siri Glow/Orb (AGSL on API 33+ and Canvas fallback below; API 29–30 corner radius fallback), TTS sentence queue, and remaining built-in tools up to 20+.
- **M4 — Stretch**: Semantic search (`needle_embed` with chunked non-blocking queue and separate `EmbeddingEntity` table) and **"Ask about this screen" (`onHandleAssist`)** running strictly on the Reply GGUF path with tools disabled.
- **Post-v1 / Deferred (Out of v1 Scope)**: Animation Lab, Benchmark screen, User-Defined Custom JSON Tasks (and HTTP webhooks), and additional theme presets (`Clay` and `Graphite`).

---

## 2. User Experience & Visual Design

### Key User Flows

1. **First-Launch Guided Setup Wizard & Starter Pack**:
   - Presents a checklist screen with live status badges and a **"Start Guided Setup"** button that walks the user through a **guided multi-step sequence** (not a single tap, because special Android privileges each open their own system Settings screen):
     1. Batched standard runtime permissions (`RECORD_AUDIO`, `POST_NOTIFICATIONS`, `READ_CONTACTS`, `CALL_PHONE`, `SEND_SMS`, `READ_CALENDAR`, `WRITE_CALENDAR`, `BLUETOOTH_CONNECT`).
     2. Sequential Settings deep-links that auto-verify on `onResume`: Do Not Disturb access (`ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`), Write System Settings (`ACTION_MANAGE_WRITE_SETTINGS`), Battery Optimization Exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), **Android 13+ "Allow restricted settings" in App Info** followed by **Sky Accessibility Service** (`ACTION_ACCESSIBILITY_SETTINGS`), and **Make Sky Your Assistant** (`Settings.ACTION_VOICE_INPUT_SETTINGS`, checking `RoleManager.isRoleHeld(ROLE_ASSISTANT)` with Pixel/Samsung side-key instructions).
   - Offers a one-tap **Starter Pack** download (`needle3.cact` + `whistle.cact`, ~46 MB) plus optional one-tap **SmolLM2 360M Instruct Q8_0** (~380 MB).
2. **Power-Button Voice Assistant Overlay**:
   - Long-pressing the power button (or tapping the Quick Settings tile, homescreen widget, launcher shortcut, or in-app voice button) triggers a subtle haptic tick + two-tone earcon and launches the translucent overlay.
   - Displays either **Siri Glow** (default iOS 18–26 perimeter light following `WindowInsets.getRoundedCorner` on API 31+ or `28.dp` fallback on API 29–30, with background blur) or **Siri Orb** (iOS 27 top-center morphing pill around the cutout).
   - Live words fade in as the user speaks; an **"Ask about this screen"** chip appears when screen context is available via `onHandleAssist` (which routes strictly to the Reply GGUF model with tools disabled so untrusted on-screen text cannot trigger tool calls); a compact keyboard button lets the user switch to typing at any time.
   - Tier 1 tool calls execute immediately with a spoken one-line confirmation and an interactive result card (with **Undo** for reversible tools); Tier 2 tool calls display the 3-second cancelable countdown card (unless overridden in *Settings → Tasks → "Run instantly"*). When the router returns **"no tool"**, Sky loads the Reply GGUF (if not already in memory, showing a subtle *"warming up"* state if the first token takes > 1 s) and streams the answer into a translucent glass card (**Copy**, **Share**, **Continue in Chat**, **Save as Note**) while speaking sentence-by-sentence via Android `TextToSpeech`.
3. **Home Chat & Navigation Drawer**:
   - **Home Empty State**: Top bar with raised neumorphic hamburger button (top-left) and private-chat Ghost Mode toggle (top-right); centered animated **Sky Sun-and-Cloud 3D Emblem** with a gentle floating spring animation and time-of-day greeting; bottom floating `NeuSurface(depth = 2)` input card with attach (`+`) sheet, active Reply model chip, Whistle dictation mic button, and primary round Voice/Send morphing button.
   - **Left Slide Drawer**: Dims and scales the main canvas (`0.95f`) while sliding in navigation rows (`Chat`, `Tasks`, `Models`, `Settings`, highlighted `Sky Voice · Hands-free` card, `About`), followed by a thin hairline divider and chat history grouped into *Today*, *Yesterday*, *This week*, and *Earlier* with semantic search.
4. **Tasks & Tool Playground**:
   - Displays *Today's To-Dos*, *Saved Notes*, and an auditable *Task Execution History* with one-tap Undo (where reversible) or Repeat.
   - Includes the **Tool Playground** (under *Settings → Advanced*) where users can speak or type a command and inspect the raw Whistle transcript, Needle JSON tool call, confidence score (`0.0–1.0`), risk tier, and per-stage latency breakdown in milliseconds (`STT ms`, `Needle ms`, `Execution ms`).
5. **Hugging Face Model Hub & Download Manager**:
   - Tabs for **Task / Speech (`.cact`)** and **Reply (`GGUF`)** with live Hugging Face Hub search, RAM-fit compatibility badges (`Fits`, `Tight`, `Too Big` computed from `ActivityManager.MemoryInfo`), quantization tags, and a "Recommended for Your Phone" filter defaulting to ungated repositories.
   - Resumable downloader backed by WorkManager expedited / Android 14+ user-initiated data transfer jobs with HTTP `Range` support, SHA-256 verification, free-space check, split-GGUF support, and animated neumorphic progress rings.

### Visual Identity & Theme ("Neo 3D Neumorphism")

- **Aesthetic Direction**: Calm, tactile physical surfaces where every component is sculpted from the same material as the background using dual directional soft shadows (top-left highlight `hi`, bottom-right shadow `lo`, 8–14 dp offset, 16–28 dp blur), a subtle $\pm 4\%$ diagonal surface gradient, and a `1.dp` specular inner highlight border (`NeuSurface(depth, shape, pressed)`).
- **Neumorphic Shadow Performance**: Blurred dual shadows on every component are expensive in lists. For repeated items (chat bubbles, history rows, model cards) use pre-rendered/cached shadow bitmaps or a single `drawBehind` with a cached `BlurMaskFilter` paint; reserve live blur for the few hero surfaces (input card, emblem, buttons). Respect reduced-motion/animator scale.
- **Legibility & WCAG Contrast Guarantees**: Sky enforces $\ge 4.5:1$ text contrast, `2.dp` crisp vector strokes, `1.dp` hairline borders where shadow separation is subtle, and $\ge 48.dp$ touch targets.
- **v1 Theme Palette (`Sky Light` & `Sky Dark` via `ThemeSpec`)** (switched with a circular-reveal crossfade in *Settings → Appearance*; `ThemeSpec` makes adding deferred `Clay` and `Graphite` presets trivial):

| Theme Preset | Mode | Background (`bg`) | Light Shadow (`hi`) | Dark Shadow (`lo`) | Primary Text | Muted Text | Accent (Icon / Non-Text) | Accent Fill (Buttons) | On-Accent Text | Accent Text (On `bg`) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Sky (v1 Default)** | Light | `#E8EEF6` | `#FFFFFF` | `#BCC8DA` | `#1B2433` | `#5A687C` | `#1E88E5` | `#1565C0` | `#FFFFFF` | `#1565C0` |
| **Sky (v1 Default)** | Dark | `#1C222D` | `#28313F` | `#12161D` | `#E8EEF7` | `#93A0B4` | `#5CB8FF` | `#5CB8FF` | `#0D131A` | `#5CB8FF` |
| *Clay (Deferred)* | Light | `#F0EBE1` | `#FFFDF8` | `#D3CABA` | `#2B2926` | `#6B645B` | `#C6613F` | `#A84A2C` | `#FFFFFF` | `#A84A2C` |
| *Clay (Deferred)* | Dark | `#262421` | `#322F2C` | `#181716` | `#ECE8E1` | `#A29B90` | `#E58C6C` | `#E58C6C` | `#181614` | `#E58C6C` |
| *Graphite (Deferred)* | Light | `#ECEDEF` | `#FFFFFF` | `#C9CCD2` | `#0D0D0D` | `#666677` | `#0E8F6F` | `#0B7259` | `#FFFFFF` | `#0B7259` |
| *Graphite (Deferred)* | Dark | `#212121` | `#2E2E2E` | `#151515` | `#ECECEC` | `#9B9B9B` | `#10A37F` | `#10A37F` | `#0D1714` | `#10A37F` |

- **Semantic Status Tokens (Fill vs. Text)**:
  - **Badge / Icon Fills ($\ge 3.0:1$)**: Success `#2FBF71`, Warning `#F5A524`, Error `#E5484D`.
  - **Status Text on Light `bg` ($\ge 4.5:1$)**: Success Text `#1B7A43`, Warning Text `#945800`, Error Text `#C62828`.
  - **Status Text on Dark `bg` ($\ge 4.5:1$)**: Success Text `#4CD987`, Warning Text `#FFB84D`, Error Text `#FF6B70`.

> [!NOTE]
> **WCAG Contrast Verification**: Because white text on `#1E88E5` is only ~3.7:1, primary button surfaces in light mode use `Accent Fill` (`#1565C0`) with `#FFFFFF` `On-Accent Text` ($\ge 4.5:1$), while dark mode uses `#5CB8FF` with dark `#0D131A` `On-Accent Text` ($\ge 4.5:1$). Contrast of every text/background token pair (including `On-Accent Text` on `Accent Fill` and `Status Text` on `bg`) is verified by a unit test (WCAG ratio $\ge 4.5$ for text, $\ge 3.0$ for large text and UI icons). The test is the source of truth and values may be nudged to pass.

- **Siri Wake Palette**: `#BC82F3`, `#F5B9EA`, `#8D99FF`, `#AA6EEE`, `#FF6778`, `#FFBA71`, `#C686FF`.
- **Typography & Hierarchy**: Bundled local **Inter** (with **Roboto Mono** for code blocks and JSON inspector) in two crisp weights (`Normal` 400 and `SemiBold` 600), aligned to an `8.dp` spatial grid with `20–28.dp` corner radii and full pill (`CircleShape`) buttons/chips.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Unified Single-Process Inference Broker, Chunked Embedding Queue & Post-Router GGUF Loading**
  - *Chosen Approach*: A singleton `InferenceBroker` shared by `MainActivity` and `SkyVoiceInteractionSessionService` inside the same application process, serializing all `libneedle_jni.so` calls on a dedicated single-thread executor converted to a `CoroutineDispatcher` (`Executors.newSingleThreadExecutor().asCoroutineDispatcher()`), so blocking JNI calls never occupy the shared `Default` pool, while running `libllama_jni.so` on a configurable multi-threaded native pool.
    - **Chunked Non-Blocking `needle_embed` Queue**: Because a blocking native JNI call cannot be interrupted mid-call, background semantic indexing jobs are chunked into single-item tasks (`1 message` or `1 note` per JNI call) in a low-priority queue that checks an atomic `isVoiceSessionActive` flag before each item; when a voice session starts (`Listening`/`Thinking`), **no new embedding jobs start** until the voice session finishes.
    - **Post-Router Reply GGUF Loading**: To avoid competing with Whistle and Needle for CPU/RAM or adding thermal load during wake, the Reply GGUF is loaded **only after the router returns "no tool"** (or when opening Chat / *"Ask about this screen"*), showing a subtle *"warming up"* state if the first streamed token takes > 1 s, and unloaded on `onTrimMemory` or idle timeout.
  - *Why*: Keeps `needle3.cact` and `whistle.cact` (~46 MB total) unobstructed during wake and speech routing for a **target < 300 ms to overlay visible when the service is warm (measured on reference devices; not guaranteed after the system kills the service)**.

- **Decision 2: Risk-Tiered Execution & Untrusted Screen Context Isolation**
  - *Chosen Approach*: Define 20+ single-purpose built-in tools following Cactus's tool-design principles.
    - **Tier 1 (reversible/benign: torch, volume, brightness, DND, timers, alarms, notes, to-dos, media, web search, open app, navigation, calendar, Wi-Fi/BT, battery, time)**: execute immediately at confidence $\ge 0.70$, or $\ge 0.45$ when an unambiguous trigger keyword matches (thresholds calibrated in M0). Show result card with **Undo** where reversible (`volume`, `brightness`, `do_not_disturb`, `flashlight`, `add_task`, `complete_task`, `take_note`, `create_calendar_event`).
    - **Tier 2 (irreversible or costly: `send_sms`, `place_call`)**: require confidence $\ge 0.85$ (calibrated in M0) and show a 3-second cancelable countdown card (*"Texting Mom... Cancel"*) before executing. When the device is locked, Tier 2 actions require unlock first; Tier 1 may run on the lockscreen except where the OS blocks it. Includes *Settings → Tasks → "Run instantly"* toggle per tool.
    - **Untrusted Screen Context (`AssistStructure`) Isolation**: On-screen text captured via `onHandleAssist` is untrusted input that could contain prompt injections attempting to trigger `send_sms` or `place_call`. Therefore, *"Ask about this screen"* always bypasses Needle tool calling and runs strictly on the **Reply GGUF path with all tools disabled**.
  - *Why*: Balances fast, dialog-free device control with strong protection against both ASR mis-transcription and indirect prompt injection.

- **Decision 3: AGSL `RuntimeShader` + Multi-Layer Compose Canvas Fallback for Siri Animations**
  - *Chosen Approach*: Implement both **Siri Glow** (4 stacked perimeter strokes at `4/6/8/11.dp` with `0/3/10/18.dp` blur, additive blending, `WindowInsets.getRoundedCorner` on API 31+ with `28.dp` fallback on API 29–30, and randomized eased color-stop drift every `600 ms`) and **Siri Orb** using hardware-accelerated `RuntimeShader` (API 33+) and offscreen `graphicsLayer` blur/gradient compositing below API 33, with pre-allocated float arrays so zero objects are allocated per frame.
  - *Why*: Delivers smooth 60–120 fps visual fidelity across API 29–36 while respecting Android's `ANIMATOR_DURATION_SCALE` reduced-motion accessibility setting.

### Known Risks & Assumptions to Verify in M0

- **Native Engine API, Capabilities & Licensing (M0 Gate)**: Exact C API signatures (`needle_init`, `needle_complete`, `needle_embed`), `.cact` licensing, and documented capabilities (grammar-constrained tool calls, contrastive tool retrieval for >5 tools, and calibrated confidence scores) are treated as **unverified assumptions until M0 passes on real arm64 hardware**.
- **Empirical Confidence Calibration**: The `0.70` / `0.45` / `0.85` confidence thresholds are initial placeholders; M0 logs real confidence distributions across positive, ambiguous, and out-of-domain prompts to calibrate production thresholds.
- **Thermal Throttling & Battery Drain**: Sustained on-device inference can cause thermal throttling and battery drain; Sky includes a thermal-status check (`PowerManager` thermal API) that automatically lowers thread counts/context size or warns the user when thermal status rises.
- **OEM Power-Button Routing & Assistant Role**: OEMs customize power/side-key behavior (e.g., Samsung Side Key settings vs. Pixel gesture settings); the Setup Wizard deep-links to `Settings.ACTION_VOICE_INPUT_SETTINGS`, detects whether Sky actually holds `ROLE_ASSISTANT`, and presents per-OEM step-by-step instructions.
- **Speech Mis-Transcription of Names & Numbers**: ASR mis-transcription of contact names or phone numbers drives the **Tier 2** 3-second cancelable countdown design.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```text
┌───────────────────────────────────────────────────────────────────────────────────┐
│                           ANDROID SYSTEM & ENTRY POINTS                           │
│  [Power Button Long-Press]   [Quick Settings Tile]   [App Widget]   [Main App UI] │
└──────────────┬─────────────────────────┬──────────────────┬───────────────┬───────┘
               │ BIND_VOICE_INTERACTION  │                  │               │
               ▼                         ▼                  ▼               ▼
┌───────────────────────────────────────────────────┐   ┌───────────────────────────┐
│         SkyVoiceInteractionService (Warm)         │   │       MainActivity        │
│  • SkyVoiceInteractionSession (ViewTree Owners)   │   │  • Neo 3D Neumorphic UI   │
│  • SkyRecognitionService & AssistActivity         │   │  • Drawer / Chat / Tasks  │
│  • onHandleAssist (Tools Disabled -> Reply Only)  │   │  • Model Hub & Playground │
│  • SiriGlow / SiriOrb + 650ms AudioRecord VAD     │   │  • Guided Setup Wizard    │
└─────────────────────────┬─────────────────────────┘   └─────────────┬─────────────┘
                          │                                           │
                          └─────────────────────┬─────────────────────┘
                                                ▼
┌───────────────────────────────────────────────────────────────────────────────────┐
│                        SHARED INFERENCE & ROUTING BROKER                          │
│  ┌──────────────────────────────┐  ┌──────────────────────┐  ┌─────────────────┐  │
│  │  CactusEngine (Dedicated 1T) │  │   LlamaCppEngine     │  │  SkyTtsEngine   │  │
│  │  • libneedle_jni.so (CMake)  │  │  • libllama_jni.so   │  │  • Android TTS  │  │
│  │  • Stage 1: whistle.cact STT │  │  • Load After Router │  │  • Sentence Q   │  │
│  │  • Stage 2: needle3.cact     │  │    Returns "No Tool" │  │  • Amplitude    │  │
│  │  • Chunked needle_embed Q    │  │  • Thermal Monitor   │  │                 │  │
│  └──────────────┬───────────────┘  └──────────┬───────────┘  └────────┬────────┘  │
└─────────────────┼─────────────────────────────┼───────────────────────┼───────────┘
                  │ Tool Calls + Confidence     │ Token Flow            │
                  ▼                             ▼                       ▼
┌──────────────────────────────────────┐  ┌─────────────────────────────────────────┐
│   Risk-Tiered Silent Task Executor   │  │     Local Persistence & Model Hub       │
│  • Tier 1: Immediate (+Undo if Rev.) │  │  • Room DB: Chats, Messages, Embeddings │
│  • Tier 2: 3s Cancelable Countdown   │  │    (Separate Table), To-Dos, Notes,     │
│  • 20 Built-In Device Action Tools   │  │    Task Logs (Skipped in Ghost Mode)    │
│  • Connectivity/BT Settings Fallback │  │  • User-Initiated Transfer Downloader   │
└──────────────────────────────────────┘  └─────────────────────────────────────────┘
```

### Built-In Tool Catalog, Risk Tiers & Reversibility

| Tool Name | Risk Tier | Reversible (Undo) | Description & Execution Mechanism |
| :--- | :--- | :--- | :--- |
| `set_alarm` | **Tier 1** | No (*Open Clock* link) | Sets an alarm via `AlarmClock.ACTION_SET_ALARM` with `EXTRA_SKIP_UI = true`. |
| `set_timer` | **Tier 1** | No (*Open Clock* link) | Starts a countdown timer via `AlarmClock.ACTION_SET_TIMER` with `EXTRA_SKIP_UI = true`. |
| `create_calendar_event` | **Tier 1** | **Yes** (Delete event ID) | Inserts an event directly into `CalendarContract.Events` and stores event URI for Undo. |
| `open_app` | **Tier 1** | No | Resolves package name by spoken label via `PackageManager` and launches activity from session. |
| `place_call` (`call_contact`) | **Tier 2** | No (3s Countdown) | Resolves contact number via `ContactsContract` and dials via `Intent.ACTION_CALL` after 3s cancelable countdown (requires unlock if locked). |
| `send_sms` | **Tier 2** | No (3s Countdown) | Resolves contact and sends SMS via `SmsManager.sendTextMessage` after 3s cancelable countdown (requires unlock if locked). |
| `flashlight` | **Tier 1** | **Yes** (Restore state) | Toggles rear camera torch via `CameraManager.setTorchMode`. |
| `volume` | **Tier 1** | **Yes** (Restore level) | Adjusts media/ring/alarm stream volume via `AudioManager` and saves previous index for Undo. |
| `brightness` | **Tier 1** | **Yes** (Restore level) | Adjusts screen brightness via `Settings.System.SCREEN_BRIGHTNESS` and saves previous value for Undo. |
| `do_not_disturb` | **Tier 1** | **Yes** (Restore filter) | Sets interruption filter via `NotificationManager.setInterruptionFilter`. |
| `wifi_toggle` | **Tier 1** | No (States path used) | Best-effort Quick Settings tile via `SkyAccessibilityService`; guaranteed fallback to `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` (API 30+) / `ACTION_WIFI` (API 29). |
| `bluetooth_toggle` | **Tier 1** | No (States path used) | Best-effort Quick Settings tile via `SkyAccessibilityService`; guaranteed fallback to `Settings.ACTION_BLUETOOTH_SETTINGS`. |
| `navigate_to` | **Tier 1** | No | Launches turn-by-turn navigation intent (`google.navigation:q=...` or `geo:0,0?q=...`). |
| `web_search` | **Tier 1** | No | Launches browser search intent (`Intent.ACTION_WEB_SEARCH` / search URI; uses network). |
| `media_control` | **Tier 1** | No | Dispatches `KeyEvent` (`KEYCODE_MEDIA_PLAY_PAUSE`, `NEXT`, `PREVIOUS`) via `AudioManager.dispatchMediaKeyEvent`. |
| `add_task` | **Tier 1** | **Yes** (Delete row) | Inserts a new item into Today's To-Dos in Room. |
| `list_tasks` | **Tier 1** | N/A (Read-only) | Queries pending Today's To-Dos from Room and presents an interactive checklist card. |
| `complete_task` | **Tier 1** | **Yes** (Uncheck row) | Marks matching To-Do item completed in Room. |
| `take_note` | **Tier 1** | **Yes** (Delete note) | Saves a timestamped note in Room and queues a single-item job for `needle_embed`. |
| `battery_status` | **Tier 1** | N/A (Read-only) | Reads battery percentage, charging state, and temperature via `BatteryManager`. |
| `current_time` | **Tier 1** | N/A (Read-only) | Formats and speaks current local time, date, and timezone. |

### Data Model & State (Room Database + Encrypted Keystore)

1. **`ChatSessionEntity` & `ChatMessageEntity`**: Stores conversation threads (grouped by *Today / Yesterday / This week / Earlier*, pinned state, title), message roles (`user`, `assistant`, `tool`), collapsible `<think>` blocks, inline task execution metadata, and token/s metrics. (**Skipped completely when Private Chat Ghost Mode is active**).
2. **`EmbeddingEntity` (Separate Table)**: Stores vector embeddings separately from messages/notes with columns `id`, `sourceType` (`MESSAGE` or `NOTE`), `sourceId` (foreign key with cascade delete), `modelId`, `modelVersion`, `dimension`, and `vectorBlob` (`ByteArray` serialized `FloatArray`). (**Skipped when Ghost Mode is active**).
3. **`TodoItemEntity` & `NoteEntity`**: Powers the local `add_task`, `list_tasks`, `complete_task`, and `take_note` voice commands and the **Tasks** screen.
4. **`TaskExecutionLogEntity`**: Records executed tool calls, arguments, risk tier, confidence score, execution path used (e.g., Accessibility vs. `ACTION_INTERNET_CONNECTIVITY` / `ACTION_BLUETOOTH_SETTINGS`), latency (`STT ms`, `Needle ms`, `Execution ms`), status, and reversible Undo payload. (**Skipped or redacted when Ghost Mode is active**).
5. **`InstalledModelEntity` & `DownloadTaskEntity`**: Tracks local `.cact` and `.gguf` files, SHA-256 checksums, active slot assignments (`TASK`, `SPEECH`, `REPLY`), GGUF header metadata (architecture, quantization, context length, embedded chat template), and resumable HTTP byte offsets.
6. **`EncryptedPrefs / Android Keystore`**: Protects the Hugging Face OAuth 2.0 access/refresh tokens or pasted Personal Access Token alongside theme, voice, per-tool *"Run instantly"* overrides, and LLM sampling parameters.

### Interactive Component & State Mapping

- **Compose Inside `VoiceInteractionSession`**: Because `VoiceInteractionSession` hosts a raw `Window` rather than a `ComponentActivity`, Sky explicitly wires `ViewTreeLifecycleOwner`, `ViewTreeViewModelStoreOwner`, and `ViewTreeSavedStateRegistryOwner` onto the session window's decor view before attaching the overlay `ComposeView`.
- **Voice Session Lifecycle**:
  - `Idle` → `Listening` (on overlay open, set `isVoiceSessionActive = true` so the chunked `needle_embed` queue starts no new jobs; capture 16 kHz `AudioRecord` ring buffer, RMS amplitude driving Siri Glow stroke width/intensity or Siri Orb scale, energy VAD detecting speech start and **600–700 ms** trailing silence) → `Thinking` (two-stage pipeline: `whistle.cact` produces transcript, then `needle3.cact` receives transcript text and returns tool calls; if *"Ask about this screen"* was triggered, bypass Needle and route directly to Reply GGUF with tools disabled) → `ExecutingTask` (Tier 1 immediate silent execution + Undo card if reversible + TTS summary; Tier 2 3-second cancelable countdown card unless overridden, requiring unlock if device is locked) OR `StreamingReply` (load Reply GGUF now if not already loaded, showing a subtle *"warming up"* state if first token takes > 1 s; stream tokens via `llama.cpp` into glass card + sentence-buffered TTS; unload Reply GGUF on `onTrimMemory` or idle timeout) → `FollowUp` or `Dismissed` (reset `isVoiceSessionActive = false` so queued embedding jobs resume).
- **Deliverables & CI**:
  - Native CMake build (`app/src/main/cpp/CMakeLists.txt`) compiling `libneedle_jni.so` and `libllama_jni.so` with 16 KB page alignment (`-Wl,-z,max-page-size=16384`).
  - Comprehensive automated test suite:
    - Unit tests for Hugging Face Hub parsing (including `401/403` gated repo detection), resumable downloads, router logic, and tool argument schema parsing.
    - **Unit tests for risk-tier routing & reversibility** (verifying Tier 1 vs. Tier 2 thresholds, 3s countdown state, lockscreen gating, per-tool *"Run instantly"* override, reversible-only Undo affordance, and *"Ask about this screen"* forcing Reply-only with tools disabled).
    - **Contrast token unit test** (verifying WCAG ratio $\ge 4.5$ for all text/background token pairs—including `Primary Text`, `Muted Text`, `Accent Text`, `On-Accent Text` on `Accent Fill`, and `Status Text`—and $\ge 3.0$ for large text and UI icons).
    - **Chunked embedding queue gating unit test** (verifying that **no new `needle_embed` jobs start** on the single-thread native dispatcher while a voice session is active, and pending items resume once the session ends).
    - Instrumented smoke test for the assistant service.
  - `.github/workflows/build-apk.yml` CI workflow building the NDK debug APK and uploading it as a downloadable artifact, plus comprehensive `README.md` + `PROGRESS.md`.
