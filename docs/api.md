# PiPup HTTP API Reference

This document provides a precise technical specification of the PiPup REST API. All endpoints listen on port **7979**.

---

## 1. Notifications

### Display Notification

`POST /notify`

Displays an overlay on the TV. Supports `application/json` (also accepted when `Content-Type` is omitted or chunked, up to 256 KB) and `multipart/form-data` (up to 32 MB; uploaded images capped at 32 MP).

**JSON Payload Properties:**

| Field                  | Type        | Default          | Description                                                                                                                                                                                                                                                                                                                                                                                                                |
| :--------------------- | :---------- | :--------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `id`                   | String      | null             | Unique identifier for popup stacking and in-place updates.                                                                                                                                                                                                                                                                                                                                                                 |
| `duration`             | Int         | 10               | Seconds to display (`0` or negative for indefinite).                                                                                                                                                                                                                                                                                                                                                                       |
| `position`             | Int/String  | 0                | `0..8` or name (`"TopRight"`, `"TopLeft"`, `"BottomRight"`, `"BottomLeft"`, `"Center"`, `"TopCenter"`, `"BottomCenter"`, `"CenterLeft"`, `"CenterRight"`).                                                                                                                                                                                                                                                                 |
| `stack`                | String      | null             | Stacking axis direction: `"vertical"` or `"horizontal"`.                                                                                                                                                                                                                                                                                                                                                                   |
| `title`                | String      | null             | Heading text.                                                                                                                                                                                                                                                                                                                                                                                                              |
| `message`              | String      | null             | Main body text.                                                                                                                                                                                                                                                                                                                                                                                                            |
| `icon`                 | String      | null             | Optional header icon displayed beside the title/message block: either an image URL (PNG, WebP, JPEG, GIF, SVG, BMP, `data:`) or an emoji/short glyph (e.g. `"🔔"`, `"🚨"`).                                                                                                                                                                                                                                                |
| `iconPosition`         | String      | null             | Placement of `icon` relative to title/message: `"left"` or `"right"` (inherits Media Settings default `"left"` when omitted).                                                                                                                                                                                                                                                                                              |
| `iconWidth`            | Int         | null             | Width of `icon` in pixels (`16..512`, aspect ratio preserved; inherits Media Settings default `96` when omitted).                                                                                                                                                                                                                                                                                                          |
| `urgency`              | String      | null             | Border preset shorthand using Material Design 3 semantic colors (Light / Dark): `"info"` (`4px`, `#196584` / `#8ECFF2`), `"warning"` (`6px`, `#8B5000` / `#FFB866`), or `"critical"` (`8px`, `#B3261E` / `#F2B8B5`). Explicit `borderWidth`, `borderColor`, and `borderRadius` override individual parts of the preset.                                                                                                    |
| `borderRadius`         | Int         | null             | Corner radius in pixels (alias: `cornerRadius`). Omit to inherit App Settings; pass `0` to explicitly disable rounding.                                                                                                                                                                                                                                                                                                    |
| `borderWidth`          | Int         | null             | Border width in pixels. Omit to inherit `urgency`/App Settings; pass `0` to explicitly disable the border.                                                                                                                                                                                                                                                                                                                 |
| `borderColor`          | String      | null             | Border hex color (`#RRGGBB` or `#AARRGGBB`). Omit to inherit `urgency`/App Settings.                                                                                                                                                                                                                                                                                                                                       |
| `contentPadding`       | Int         | null             | Content padding in pixels (alias: `padding`).                                                                                                                                                                                                                                                                                                                                                                              |
| `opacity`              | Float       | null             | Whole-popup window opacity `0.0..1.0` (defaults to `1.0` fully opaque).                                                                                                                                                                                                                                                                                                                                                    |
| `showProgress`         | Bool        | false            | If true and `duration > 0`, displays a clock-driven countdown progress bar at the bottom of the popup.                                                                                                                                                                                                                                                                                                                     |
| `bringToFront`         | Bool        | false            | If true on an in-place update of an existing `id`, raises the popup to the top of the stack.                                                                                                                                                                                                                                                                                                                               |
| `dismissScreensaver`   | Bool        | true             | If true and the device screensaver (DreamService) is active, wakes the display before showing the popup.                                                                                                                                                                                                                                                                                                                   |
| `media`                | Object      | null             | Media configuration (see below).                                                                                                                                                                                                                                                                                                                                                                                           |
| `mediaFullBleed`       | Bool        | null             | If true, stretches media edge-to-edge to the popup border while keeping text/buttons padded inside.                                                                                                                                                                                                                                                                                                                        |
| `liveBadge`            | Bool        | null             | If true, renders a LIVE badge overlay once the stream's first frame is rendered (`video`, `web`, `whep`).                                                                                                                                                                                                                                                                                                                  |
| `liveBadgeColor`       | String      | null             | Hex color code for the live badge background (e.g. `"#B3261E"`).                                                                                                                                                                                                                                                                                                                                                           |
| `liveBadgePosition`    | String      | `"bottom_right"` | Position of the live badge inside media: `"top_left"`, `"top_right"`, `"bottom_left"`, `"bottom_right"` (or `0..3`).                                                                                                                                                                                                                                                                                                       |
| `fullscreen`           | Bool        | false            | If true, expands the overlay window to cover the entire display (including system bars and cutouts) with centered content and disables stacking. Explicit media `width`/`height` values in the payload cap the media dimensions even in fullscreen mode; omit `width`/`height` to automatically size media to the available fullscreen box.                                                                                |
| `fullscreenUpscale`    | Bool        | null             | If true in fullscreen mode, upscales smaller media (e.g. 480p) up to the available fullscreen box (or up to any explicit media `width`/`height` cap) (alias: `upscale`; falls back to Media Settings).                                                                                                                                                                                                                     |
| `animation`            | Int/String  | null             | Entrance/exit animation: integer `0..10` (or numeric string) or compatibility string `"none"` (`0`), `"fade"` (`1`), `"slide_left"` / `"slide_right"` / `"slide_top"` / `"slide_bottom"` (`2`, position-aware slide). Omit to inherit App Settings; pass `0` or `"none"` to explicitly disable. Legacy alias: `animationType`.                                                                                             |
| `overwrite`            | Bool        | false            | If true and max concurrency or geometry limit is reached, evicts conflicting or oldest popups.                                                                                                                                                                                                                                                                                                                             |
| `dismissOnBack`        | Bool        | false            | If true, allows hardware Back key presses on the remote to dismiss button-less popups (e.g. fullscreen streams) with `popup_removed` (`reason: "back"`). Popups with `buttons` always support Back dismissal without firing button callbacks.                                                                                                                                                                              |
| `buttons`              | Array       | `[]`             | Up to 5 interactive action buttons: `[{"id": "open", "label": "Open"}]` (labels truncated to 30 chars). Pressing a button POSTs `{"popup": "<popup_id>", "button": "<button_id>", "label": "<button_label>", "device": "<device_id>", "name": "<device_name>"}` to `callback` and dismisses the popup with `popup_removed` (`reason: "button"`).                                                                           |
| `callback`             | String      | null             | `http(s)` webhook URL invoked when an interactive action button in `buttons` is pressed.                                                                                                                                                                                                                                                                                                                                   |
| `buttonsInline`        | Bool        | false            | If true, arranges action buttons horizontally in a single row instead of vertically stacked.                                                                                                                                                                                                                                                                                                                               |
| `buttonSize`           | Float       | null             | Action button text size in `sp` (scales action button font, padding, and spacing proportionally; inherits Button Settings default of `14`).                                                                                                                                                                                                                                                                                |
| `buttonColor`          | String      | null             | Hex color for action buttons (and default tint for `closeButton`; inherits Button Settings).                                                                                                                                                                                                                                                                                                                               |
| `buttonFocusColor`     | String      | null             | Hex color for focused action buttons (and focused tint for `closeButton`; inherits Button Settings).                                                                                                                                                                                                                                                                                                                       |
| `buttonTextColor`      | String      | null             | Hex text color for action buttons (inherits Button Settings).                                                                                                                                                                                                                                                                                                                                                              |
| `buttonFocusTextColor` | String      | null             | Hex text color for focused action buttons (inherits Button Settings).                                                                                                                                                                                                                                                                                                                                                      |
| `buttonBorderColor`    | String      | null             | Hex border color for action buttons (inherits Button Settings).                                                                                                                                                                                                                                                                                                                                                            |
| `buttonCornerRadius`   | Float       | null             | Corner radius in `dp` for action buttons (inherits Button Settings default of `20`).                                                                                                                                                                                                                                                                                                                                       |
| `closeButton`          | String/Bool | null             | Overlaid circular `✕` close control taking zero layout space (keeps fullscreen media edge-to-edge). Accepts `"top_right"`, `"top_left"`, `"bottom_right"`, `"bottom_left"`, `true` (`"top_right"`), or `false`/`"none"` (inherits Button Settings when omitted). Dismisses with `popup_removed` (`reason: "close"`) without firing button callbacks. Does not enable Back dismissal unless `dismissOnBack` is also `true`. |
| `closeButtonTimeout`   | Int         | null             | Auto-hide delay in seconds for `closeButton` (`0` = always visible, `1..300`; inherits Button Settings default of `5`). Fades to 0% opacity and reappears on D-pad/OK/touch (first OK wakes the button, second OK closes).                                                                                                                                                                                                 |
| `closeButtonSize`      | Int         | null             | Diameter of the overlaid `closeButton` in `dp` (`24..72`; inherits Button Settings default of `32`).                                                                                                                                                                                                                                                                                                                       |
| `popupLoadingBehavior` | Int         | 0                | 0: Strict Sequential (Default), 1: Parallel Fast-Track                                                                                                                                                                                                                                                                                                                                                                     |

**Popup Stacking & ID Behavior:**

- **Stacking:** Multiple notifications sent to the same screen position stack vertically up to the configured `maxPopups` limit (1..5, configured in performance settings).
- **In-Place Updates:** If a notification with a matching `id` is already active or waiting in the queue when a new request arrives, it updates in-place without dismissing or re-animating. Replacing an active popup in-place resets `elapsed`, restarts its `duration` countdown, and pushes a `popup_replaced` webhook event (`shownId`, `replacedId`). Pass `bringToFront: true` to raise the updated popup to the top of the stack.
- **Cancellation:** Active popups can be cancelled individually by ID (`POST /cancel?id=<id>`), only the currently active top popup (`POST /cancel?current=true`), or all at once (`POST /cancel` or `POST /cancel?all=true`).

**Media Object Types:**

- **Image:** `{"image": {"uri": "url", "width": 480, "cache": true}}`
- **Video:** `{"video": {"uri": "url", "width": 480, "height": 270, "muted": true, "udp": false, "poster": "url", "posterMode": "fit", "bufferMs": 500}}` (`height` is optional; `bufferMs` is clamped to `100..10000`; `posterMode` accepts `"fit"`, `"fill"`, `"stretch"`, `"center"`)
- **Web:** `{"web": {"uri": "url", "width": 640, "height": 480, "muted": true, "poster": "url", "posterMode": "fit"}}` (`height` is optional, defaults to 16:9 ratio `width * 9 / 16`)
- **WHEP:** `{"whep": {"uri": "url", "width": 640, "height": 360, "muted": true, "videoFit": "cover", "poster": "url", "posterMode": "fit"}}` (`height` is optional, defaults to source video aspect ratio)

**Multipart Form Fields:**
Supports all top-level JSON keys (including `id`, `animation`, `urgency`, `opacity`, `bringToFront`, `dismissScreensaver`, `showProgress`, `icon`, `iconPosition`, `iconWidth`) as form fields. Local image upload uses the `image` field.

**Responses:**

- `200 OK`: `OK: Enqueued`
- `400 Bad Request`: `invalid request: <reason>`
- `403 Forbidden`: Media module is disabled.

### Cancel Notification

`POST /cancel` | `POST /cancel?all=true` | `POST /cancel?current=true` | `POST /cancel?id=<id>`

- **`/cancel`** or **`/cancel?all=true`**: Clears the entire notification queue and cancels all active notifications.
- **`/cancel?current=true`** (or `1` / `on`): Cancels only the currently active (most recently shown) popup, leaving any remaining stacked or queued notifications intact so the queue advances.
- **`/cancel?id=<id>`**: Cancels the specific notification matching `<id>`.
    - If active on screen: closes and removes it (`OK: Cancelled`).
    - If waiting in queue: removes it from queue (`OK: Removed from queue`).

**Responses:**

- `200 OK`: `OK: Cancelled`
- `200 OK`: `OK: Removed from queue`
- `200 OK`: `OK: Nothing to cancel`
- `200 OK`: `ID not found: <id>`

---

## 2. System & Power

### Power Control

`POST /power?state=on|off|toggle`

Waits up to 3 seconds for the hardware screen state to reach the requested target before answering.

**Responses:**

- `200 OK`: `{"state":"on","ok":true,"reached":true,"method":"wake_activity","screenOn":true}`
- `403 Forbidden`: Power module is disabled.
- `501 Not Implemented`: Permissions missing (Admin/Accessibility needed for `off`).

### Server State

`GET /state`

Requests may include integration headers `X-HA-PiPup-Version` and `X-HA-PiPup-Auto-Update` (`on`/`off`), which are reflected in `haPipup.connected` and `update.haManaged`.

Returns a comprehensive JSON snapshot of the application, including:

- **`visible`**: Boolean, current popup visibility.
- **`screenOn`**: Boolean, hardware display state.
- **`dreaming`**: Boolean, screensaver/dream state.
- **`popupsShown`**: Integer, total count of popups shown.
- **`watchdogCleanups`**: Integer, count of watchdog timeout cleanups.
- **`uptime`**: Integer, server uptime in seconds.
- **`device`**: Object with `model`, `manufacturer`, and `android` version.
- **`popup`**: Object with details of the active top popup (`id`, `title`, `duration`, `indefinite`, `elapsed`).
- **`popups`**: Array of all active popups currently on screen or exiting in stack order.
- **`lastPopup`**: Summary of the most recently shown popup (retained after dismissal for diagnostics, including `id`, `position`, `animation`, `firstFrameMs`, `mediaError`, `secondsAgo`).
- **`power`**: Detailed block with `canWake`, `canSleep`, and `sleepMethod`.
- **`permissions`**: Status of Overlay, Admin, Accessibility, and App Ops.
- **`update`**: Detailed block with update status (`checksEnabled`, `source`, `available`, `latest`, `installing`, `haManaged`, `error`, etc.).
- **`haPipup`**: Object with integration compatibility info (`recommended`, `minimum`, `connected`).
- **`push`**: Object with webhook/push notification status (`supported`, `webhook`, `heartbeat`, `lastEvent`, `lastStatus`, `lastError`, `lastSecondsAgo`).
- **`discovery`**: Count of other PiPup peers found.

**Webhook Events (`POST` to configured `webhook` URL):**

Every push sends the full `/state` JSON object plus an `"event"` field:

- `started`: Emitted once when the PiPup service finishes starting its HTTP server.
- `heartbeat`: Emitted periodically every `webhookHeartbeat` seconds when enabled (`> 0`).
- `settings`: Emitted when webhook or application settings are updated via `POST /settings`.
- `screen_on` / `screen_off` / `dreaming_started` / `dreaming_stopped`: Display and screensaver transitions.
- `popup_shown` (`shownId`), `popup_replaced` (`shownId`, `replacedId`), `popup_removed` (`reason`, `removedId` — reasons include `"expired"`, `"cancelled"`, `"button"`, `"back"`, `"close"`, `"queue_cancelled"`): Popup lifecycle events.

---

## 3. Maintenance

### Diagnostics

`GET /permissions/diagnose`
Returns a detailed report on system permissions and intent resolution for troubleshooting.

### Trigger Update

`POST /update`
Triggers an immediate check for updates and starts the background installation process.

---

## 4. Application Settings

### Get/Update Settings

`GET /settings` | `POST /settings`

Both `GET` and `POST` respond with `200 OK` (`application/json`) containing the full `SettingsData` object (with `animation` as the primary key; legacy `animationType` is still accepted on input) plus Home Assistant compatibility fields:

- `updateChecks`: Boolean (`updateInterval > 0`).
- `updateSource`: Read-only String (`"github"`). Any `updateSource` parameter in `POST /settings` is silently ignored.
- `webhook`: Boolean indicating whether a webhook URL is currently configured (the URL itself is never echoed back).

- **Full or Partial Settings (`application/json`):** `POST /settings` merges provided keys (including `moduleModes`, `updateChecks`, `webhook`, `webhookHeartbeat`, `animation`) onto the current settings, preserving all omitted fields (e.g. `{"maxPopups": 4}`).
- **Query Parameters:**
    - `webhook`: `http(s)` URL to receive state push events (empty string disables push).
    - `webhookHeartbeat`: Periodic heartbeat interval in seconds (`0` disables heartbeat; positive values are clamped to `10..86400`; negative or non-integer values return `400 Bad Request`).
    - `updateChecks`: `true`/`false` (`1`/`0`, `on`/`off`) to enable or disable periodic update checks.
    - Example query: `POST /settings?webhook=http://192.168.1.10:8123/api/webhook/pipup&webhookHeartbeat=60&updateChecks=true`
    - Example partial JSON: `POST /settings` with `{"webhookHeartbeat": 60}` or `{"maxPopups": 4}`

---

## 5. Development (Debug Only)

- `GET /debug/memory`: Memory usage metrics.
- `POST /debug/idle?ms=<val>`: Set Eco-mode timeout.
- `POST /debug/unload`: Immediate cleanup of dormant modules.
