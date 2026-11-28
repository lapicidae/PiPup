# PiPup - Roadmap & Feature TODOs

This document tracks planned feature enhancements and extensions (including features inspired by mhoogenbosch/PiPup v0.31 and v1.0.0), strictly adhering to the architectural rules in `AGENTS.md` (modularity, "The Core is Blind", minimal memory/RAM footprint, and comprehensive KDoc documentation).

---

## 1. Advanced Media Options (Priority: High)

- [x] **`poster` for Video & Web/WHEP:**
    - Instantly display a still snapshot image (e.g. camera snapshot loaded via Glide) while the RTSP/WHEP stream connects.
    - Smooth replacement once the first live video frame renders.
    - Configurable placeholder banner fallback in app settings (`mediaLoadingPlaceholder`).
- [x] **`bufferMs` for Low-Latency Video (RTSP/ExoPlayer):**
    - Configurable media buffer duration in milliseconds using Media3 / ExoPlayer `DefaultLoadControl`.
    - Configurable default buffer duration in milliseconds via SeekBar (0..3000ms) in Performance Settings (`defaultBufferMs`).
- [x] **`liveBadge` Overlay:**
    - Material 3 live indicator pill overlaid on live stream video once the first frame renders.
    - Configurable badge position (`top_left`, `top_right`, `bottom_left`, `bottom_right`) and accent color (`liveBadgeColor`) in General Settings.
- [x] **Full-Bleed Media Layout (`mediaFullBleed`):**
    - Option to render video or images flush with the popup's borders when text and buttons are omitted, configurable per-request or globally in General Settings.
- [ ] **Transparent Web & Stream Background (`transparent`):**
    - Transparent WebView canvas for Web and WHEP media so custom HTML overlays or camera letterboxing blend directly into the TV display.
- [ ] **Software Decoder Fallback (`softwareDecoder`):**
    - Allow opting out of hardware video decoders per video payload, with automatic detection/fallback for known problematic chipsets (e.g. Amlogic video layer freeze).

---

## 2. Audio & Speech Output (Priority: Medium)

- [ ] **Notification Sound (`sound`, `soundVolume`):**
    - Play bundled chime (`sound: "default"`) or external audio clips via URI/URL.
    - Implement transient audio focus with ducking so existing TV playback dips during playback and resumes smoothly.
- [ ] **Text-to-Speech (`tts`, `ttsLanguage`):**
    - On-demand spoken announcements of message contents using Android `TextToSpeech`.
    - Strict lazy initialization and immediate shutdown/release of the TTS engine upon completion to preserve RAM on background services.

---

## 3. Remote TV Control Module (Priority: Medium)

- [ ] **Modular `TvControlModule` (`supportedRoutes`):**
    - Implement as a standalone `PiPupModule` that can be toggled on/off in the Modules submenu.
    - `POST /launch`: Launch applications by package name or deep-link URI (`package=...`, `uri=...`, `wake=true`).
    - `GET /volume` & `POST /volume`: Inspect and adjust volume (`level=0..100`, `step=up|down`, `mute=on|off|toggle`) and emit `volume_changed` webhook bursts.
    - `POST /key`: Forward media keys (`play`, `pause`, `next`, `previous`) and system navigation (`home`, `back` via Accessibility Service). When media session access is available, route media keys directly to active `TransportControls`.
    - `GET /apps`: Enumerate launchable TV/Leanback applications with icon and label metadata.

---

## 4. Media Sessions & Foreground App Tracking (Priority: Low / Modular Opt-In)

- [ ] **Modular `MediaSessionModule`:**
    - Optional module (`OFF`/`ON`) requiring notification/session listener permission.
    - `GET /media`: Inspect currently playing media metadata (app, title, artist, album, state, duration, position, supported actions).
    - `GET /media/art`: Serve current playback album/cover artwork (JPEG/PNG) with `X-PiPup-Art-Key` caching header.
    - `POST /media/seek`: Seek active media stream by absolute position (`ms=...`) or relative offset (`by=...`).
    - Emit real-time `media` webhook event upon playback state transitions or track changes.
- [ ] **Foreground App Tracking (`/state.foreground`):**
    - Track active foreground package name, label, and timestamp using `UsageStatsManager` (requires `GET_USAGE_STATS` granted via ADB).
    - Emit `foreground` webhook event upon app switches.

---

## 5. UI Automation & Screen Capture (Priority: Low / Advanced Opt-In)

- [ ] **Modular `TvAutomationModule` (`/click`):**
    - Accessible UI interaction via dedicated `PiPupControlService` (opt-in, strictly gated by an ADB-configured package whitelist).
    - Support accessibility click (`method=click`), center tap (`method=tap` on touch-capable devices), and focus-then-OK (`method=ok`).
    - Support chaining with `/launch` (`POST /launch?package=...&click=...&wait=...`) to deep-link and interact in a single call.
- [ ] **Authenticated Screen Capture (`GET /screen.jpg`):**
    - On-demand display frame capture (Android 11+) scaled to requested `width=160..1920`.
    - Strictly protected by an ADB-provisioned shared secret token via `X-PiPup-Token` or `Authorization: Bearer` header.

---

## 6. Future Backlog & Layouts (Priority: Low)

- [ ] **Multi-Stream Camera Wall (`grid`):**
    - Multi-tile WHEP grid (up to 9 tiles per popup) for simultaneous camera monitoring.
- [x] **Full-Screen Canvas Popups (`fullscreen`):**
    - Borderless full-screen overlay mode bypassing standard window insets, with optional upscaling (`fullscreenUpscale`) for smaller media sources.
