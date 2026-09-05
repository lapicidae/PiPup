# PiPup HTTP API Reference

This document describes the REST API for PiPup. The API is designed to be 100% compatible with the reference fork.

## Base URL

`http://<tv-ip-address>:7979`

---

## Endpoints

### 1. Send Notification

`POST /notify`

Displays a popup notification on the TV screen.

**Request Body (JSON):**
| Field | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `id` | string | null | Optional identifier for the popup. |
| `duration` | integer | 10 | Duration in seconds to show the popup. |
| `position` | integer | 0 | 0: Top-Right, 1: Top-Left, 2: Bottom-Right, 3: Bottom-Left, 4: Center. |
| `title` | string | null | Primary heading text. |
| `message` | string | null | Descriptive message text. |
| `media` | object | null | Media payload (image, video, web, or whep). |

**Media Object Examples:**

- **Image:** `{"image": {"uri": "https://example.com/pic.jpg", "width": 480}}`
- **Video:** `{"video": {"uri": "https://example.com/stream.m3u8", "width": 480, "muted": true}}`
- **Web:** `{"web": {"uri": "https://example.com", "width": 640, "height": 480}}`

**Response:**

- `200 OK`: Returns the string representation of the parsed properties.
- `400 Bad Request`: `invalid request: <reason>`

---

### 2. Get Server State

`GET /state`

Returns detailed information about the current application state.

**Response (JSON):**

```json
{
    "app": "PiPup",
    "version": "0.6.10",
    "id": "unique-device-id",
    "name": "TV Name",
    "visible": true,
    "screenOn": true,
    "popupsShown": 42,
    "uptime": 3600,
    "permissions": {
        "overlay": true,
        "installPackages": true,
        "autoStart": null,
        "deviceAdmin": true,
        "accessibility": true,
        "complete": true,
        "fixable": {
            "overlay": true,
            "install": true,
            "admin": true,
            "accessibility": true
        }
    },
    "update": {
        "available": false,
        "latest": "0.6.10",
        "installing": false,
        "silent": true,
        "checkedSecondsAgo": 300
    },
    "lastPopup": {
        "title": "API Test",
        "duration": 15,
        "position": "TopRight",
        "muted": true,
        "media": { "type": "image", "width": 480 }
    }
}
```

---

### 3. Power Control

`POST /power?state=on|off|toggle`

Remotely controls the TV screen power state.

**Response:**

- `200 OK`: `{"state":"on","ok":true,"method":"wake_activity","screenOn":true}`
- `501 Not Implemented`: Returned if permissions are missing for the requested action.

---

### 4. Cancel Notification

`POST /cancel`
`POST /cancel?id=<popup-id>`

Closes the current notification. If an `id` is provided, the popup is only closed if its ID matches.

**Response:**

- `200 OK`: `Queue cleared`
- `200 OK`: `id mismatch: visible popup is <active-id>` (if ID provided but didn't match)

---

### 5. Diagnostics

`GET /permissions/diagnose`

Returns a detailed report on system permissions and intent resolution. Used for troubleshooting restricted devices.

---

### 6. Debug & Development (Debug Builds Only)

These endpoints are only available when the application is compiled in `debug` mode and are intended for testing and performance tuning.

#### Get Memory Statistics
`GET /debug/memory`

Returns a snapshot of the current memory usage (Java Heap, Native Heap) and module statuses.

#### Set Idle Timeout
`POST /debug/idle?ms=<milliseconds>`

Sets the timeout duration after which Eco-mode modules (like WebView) are automatically unloaded.
- **Example:** `POST /debug/idle?ms=15000` (Sets timeout to 15 seconds)

#### Force Immediate Unload
`POST /debug/unload`

Triggers an immediate cleanup of all dormant/Eco modules, destroying active WebViews and freeing associated resources.
