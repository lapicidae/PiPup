# PiPup - Roadmap & Feature TODOs

This document tracks planned feature enhancements and extensions (including features inspired by mhoogenbosch/PiPup v0.31), strictly adhering to the architectural rules in `AGENTS.md` (modularity, "The Core is Blind", minimal memory/RAM footprint, and comprehensive KDoc documentation).

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

---

## 2. Audio & Speech Output (Priority: Medium)

- [ ] **Notification Sound (`sound`, `soundVolume`):**
    - Play bundled chime (`sound: "default"`) or external audio clips via URI/URL.
    - Implement transient audio focus with ducking so existing TV playback dips during playback and resumes smoothly.
- [ ] **Text-to-Speech (`tts`, `ttsLanguage`):**
    - On-demand spoken announcements of message contents using Android `TextToSpeech`.
    - Immediate shutdown and release of the TTS engine upon completion to preserve RAM on background services.

---

## 3. Remote TV Control Module (Priority: Medium)

- [ ] **Modular `TvControlModule` (`supportedRoutes`):**
    - Implement as a standalone `PiPupModule` that can be toggled on/off in the Modules submenu.
    - `POST /launch`: Launch applications by package name or deep-link URI (`package=...`, `uri=...`, `wake=true`).
    - `GET /volume` & `POST /volume`: Inspect and adjust volume (`level=0..100`, `step=up|down`, `mute=on|off|toggle`).
    - `POST /key`: Forward media keys (`play`, `pause`, `next`, `previous`) and system navigation (`home`, `back` via Accessibility Service).
    - `GET /apps`: Enumerate launchable TV/Leanback applications.

---

## 4. Future Backlog (Priority: Low)

- [ ] **Multi-Stream Camera Wall (`grid`):**
    - Multi-tile WHEP grid (up to 9 tiles per popup) for simultaneous camera monitoring.
- [x] **Full-Screen Canvas Popups (`fullscreen`):**
    - Borderless full-screen overlay mode bypassing standard window insets, with optional upscaling (`fullscreenUpscale`) for smaller media sources.
