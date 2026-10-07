# PiPup HTTP API Reference

This document provides a precise technical specification of the PiPup REST API. All endpoints listen on port **7979**.

---

## 1. Notifications

### Display Notification

`POST /notify`

Displays an overlay on the TV. Supports `application/json` and `multipart/form-data`.

**JSON Payload Properties:**
| Field | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `duration` | Int | 10 | Seconds to display. |
| `position` | Int | 0 | 0:TR, 1:TL, 2:BR, 3:BL, 4:Center. |
| `title` | String | null | Heading text. |
| `message` | String | null | Main body text. |
| `media` | Object | null | Media configuration (see below). |
| `animationType`| Int | 0 | 0..10 (Fade, Slide, Scale, etc.). |
| `overwrite` | Bool | false | Interrupt current popup. |

**Media Object Types:**

- **Image:** `{"image": {"uri": "url", "width": 480, "cache": true}}`
- **Video:** `{"video": {"uri": "url", "width": 480, "muted": true, "udp": false}}`
- **Web:** `{"web": {"uri": "url", "width": 640, "height": 480}}`
- **WHEP:** `{"whep": {"uri": "url", "width": 640, "videoFit": "cover"}}`

**Multipart Form Fields:**
Supports all top-level JSON keys as form fields. Local image upload uses the `image` field.

**Responses:**

- `200 OK`: `OK: Enqueued`
- `400 Bad Request`: `invalid request: <reason>`
- `403 Forbidden`: Media module is disabled.

### Cancel Notification

`POST /cancel` | `POST /cancel?id=<id>`

**Responses:**

- `200 OK`: `OK: Cancelled` or `OK: Nothing to cancel`
- `200 OK`: `ID mismatch: visible is <active-id>`

---

## 2. System & Power

### Power Control

`POST /power?state=on|off|toggle`

**Responses:**

- `200 OK`: `{"state":"on","ok":true,"method":"wake_activity","screenOn":true}`
- `403 Forbidden`: Power module is disabled.
- `501 Not Implemented`: Permissions missing (Admin/Accessibility needed for `off`).

### Server State

`GET /state`

Returns a comprehensive JSON snapshot of the application, including:

- **`visible`**: Boolean, current popup visibility.
- **`screenOn`**: Boolean, hardware display state.
- **`power`**: Detailed block with `canSleep` and `sleepMethod`.
- **`permissions`**: Status of Overlay, Admin, and Accessibility.
- **`discovery`**: Count of other PiPup peers found.

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

`GET /settings` | `POST /settings` (application/json)

Styling and module configuration. The JSON structure matches the `SettingsData` class in `AppSettings.kt`.

---

## 5. Development (Debug Only)

- `GET /debug/memory`: Memory usage metrics.
- `POST /debug/idle?ms=<val>`: Set Eco-mode timeout.
- `POST /debug/unload`: Immediate cleanup of dormant modules.
