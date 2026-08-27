# PiPup - Universal AI Agent Instructions

This file serves as the central "Source of Truth" for all AI agents (Gemini, Cursor, Copilot, Claude Code, etc.) working on the PiPup project.

## Project Context

- **Name:** PiPup
- **Purpose:** An Android application designed to display notifications and media in a Picture-in-Picture (PiP) window, primarily optimized for Android TV.
- **Technology Stack:** Kotlin, Android SDK, Jackson (JSON), SharedPreferences.

## Coding Standards & Preferences

- **General:** Strictly adhere to the rules defined in [.editorconfig](.editorconfig) and [config/detekt/detekt.yml](config/detekt/detekt.yml).
- **Principles:** Prioritize **KISS** (Keep It Simple, Stupid) and **DRY** (Don't Repeat Yourself). Ensure **efficient RAM usage** and minimal memory footprint, especially important for always-running background services on Android TV. Avoid over-engineering.
- **Style:** Use idiomatic Kotlin. Follow Android Studio's default formatting and import layouts.
- **Settings:** When adding or modifying app settings, follow the pattern established in `AppSettings.kt` using the custom property delegates (e.g., `IntPref`, `ColorPref`).
- **UI/UX:** Ensure all UI changes are optimized for Android TV (high contrast, readable font sizes, D-pad navigation support). Use the project's preset color resources.
- **Documentation:** Use KDoc for all public-facing methods and classes.

## Interaction Guidelines

- **Language:** All instructions, code comments, and technical documentation within the codebase MUST be in English.
- **Efficiency:** Be concise and succinct in all interactions and code to minimize token usage.
- **Git Workflow:** When generating commit messages, analyze actual Git changes (e.g., `git diff`, `git status`) and ignore conversation history. Strictly follow the format and requirements in [.gitmessage](.gitmessage).
- **Scope & Scope Creep:** Strictly restrict code modifications to what was explicitly requested. Do NOT refactor, reformat, clean up, or modify unrelated files or lines of code unless explicitly instructed or approved.
- **Decision Making:** Always prioritize consistency with the existing architecture. If a proposed change deviates significantly or requires adjustments beyond the original scope, ask for clarification and approval first.
- **Workflow:** For all tasks, present a plan for review before implementation. Wait for explicit approval before modifying any files.
