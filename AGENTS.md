# PiPup - Universal AI Agent Instructions

This file serves as the central "Source of Truth" for all AI agents (Gemini, Cursor, Copilot, Claude Code, etc.) working on the PiPup project.

## Project Context

- **Name:** PiPup
- **Purpose:** An Android application designed to display notifications and media in a Picture-in-Picture (PiP) window, primarily optimized for Android TV.
- **Technology Stack:** Kotlin, Android SDK, org.json, SharedPreferences, NanoHTTPD (WebServer), Media3 (Video), Glide (Images).

## Coding Standards & Preferences

- **General:** Strictly adhere to the rules defined in [.editorconfig](.editorconfig) and [config/detekt/detekt.yml](config/detekt/detekt.yml).
- **Principles:** Prioritize **KISS** (Keep It Simple, Stupid) and **DRY** (Don't Repeat Yourself). Ensure **efficient RAM usage** and minimal memory footprint, especially important for always-running background services on Android TV. Avoid over-engineering.
- **Style:** Use idiomatic Kotlin. Follow Android Studio's default formatting and import layouts.
- **Package Name:** Never hardcode the package name `nl.rogro82.pipup`. Always use `PiPupApp.APP_PACKAGE` (which refers to `BuildConfig.APPLICATION_ID`) for dynamic resolution in code, and relative class names (e.g., `.service.PipUpService`) in the `AndroidManifest.xml`.
- **Settings:** When adding or modifying app settings, follow the pattern established in `AppSettings.kt` using the custom property delegates (e.g., `IntPref`, `ColorPref`).
- **Single Source of Truth (Limits & Defaults):** Define shared API/UI bounds and defaults as named constants in `PopupProps.Companion` (or `AppSettings.Companion`) and reuse them across parsers, settings, views, and programmatic `SeekBar.max` binding instead of duplicating numeric literals in Kotlin or layout XML.
- **UI/UX:** Ensure all UI changes are optimized for Android TV (high contrast, readable font sizes, D-pad navigation support). Use the project's preset color resources.
- **Documentation:** Use KDoc for all public-facing methods and classes.

## API Contract & State Layering Rules

- **API Contract First:** Always define endpoints, HTTP status codes, query parameters, and JSON payloads in `docs/api.md` and back them with automated contract tests before changing internal implementation.
- **State Layering:** Clearly distinguish between 4 execution layers instead of returning generic success:
    1. _Transport Layer (HTTP):_ Request reception and validation (`200 OK: Enqueued` vs `400 Bad Request`).
    2. _Display Layer (TV Overlay):_ Active rendering on screen (`visible`, `popup`, `popups` array in `/state`, `popup_shown` event).
    3. _Media Playback Layer:_ Stream and image loading status (`mediaError`, fallback placeholders).
    4. _System & Update Layer:_ Self-update lifecycle (`update.phase`, `update.error`, `permissions`).

## Core Knowledge Base

Before proposing architectural changes, adding new features, or implementing modules, you MUST read the following documentation:

- **[Architecture Overview](docs/architecture.md)**: Design principles and component interactions.
- **[Module Development Guide](docs/module-development.md)**: Blueprint and recipe for adding new modular features.
- **[HTTP API Reference](docs/api.md)**: Precision specification for remote requests.

## Testing & Verification

- **Preserve Emulator/Device State:** Do NOT run `:app:connectedDebugAndroidTest` on a local emulator/device unless explicitly requested — AGP's teardown uninstalls both the app and test APKs, wiping all `SharedPreferences` (settings) and granted permissions. Instead, install with `:app:installDebug :app:installDebugAndroidTest` and run tests via `adb shell am instrument -w -e class <TestClass[#method]> nl.rogro82.pipup.test/nl.rogro82.pipup.PiPupTestRunner`.
- **Restore Mutated Settings:** Any instrumented test that modifies `AppSettings` (directly, via `/settings`, or through UI controls) MUST capture the previous state and restore it in a `finally` block.
- **Detekt Baseline & Enforcement:** `:app:detekt` enforces all rules strictly (`ignoreFailures = false`) against the project-wide baseline in `config/detekt/baseline.xml`. Fix any reported violation in new or modified code — do NOT regenerate `config/detekt/baseline.xml` to silence new findings.

## Interaction Guidelines

- **Language:** All instructions, code comments, and technical documentation within the codebase MUST be in English.
- **Efficiency:** Be concise and succinct in all interactions and code to minimize token usage.
- **Git Workflow:** When generating commit messages, analyze actual Git changes (e.g., `git diff`, `git status`) and ignore conversation history. Strictly follow the format and requirements in [.gitmessage](.gitmessage).
- **GitHub & External Communication:** NEVER post, edit, or close public GitHub content (issue comments, PR comments, reviews, issues, PRs, releases) without first presenting the exact draft to the user for review and receiving explicit approval.
- **Scope & Scope Creep:** Strictly restrict code modifications to what was explicitly requested. Do NOT refactor, reformat, clean up, or modify unrelated files or lines of code unless explicitly instructed or approved.
- **Decision Making:** Always prioritize consistency with the existing architecture. If a proposed change deviates significantly or requires adjustments beyond the original scope, ask for clarification and approval first.
- **Workflow:** For all tasks, present a plan for review before implementation. Wait for explicit approval before modifying any files.
