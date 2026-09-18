<h1>
  <img src="graphics/ic_launcher-web.svg" alt="PiPup Icon" width="64" style="vertical-align: middle;"> PiPup
</h1>

PiPup is an application that allows displaying user-defined custom notifications on Android TV.

The most common use-case for this application is for sending notifications, from a home-automation solution, to your Android TV.

![](graphics/screenshot-1.png)

**Some example scenarios:**

- Show a snapshot of your camera on your TV (e.g. on a motion trigger)
- Display a notification with the video of your camera when someone is at your door
- Send a notification when your dryer/washingmachine is ready
- Anything else you might find useful

---

:warning: **Disclaimer:** Recent features and UI updates were completely vibe-coded via AI. They get the job done, but expect pure AI logic under the hood.

### Key Features

- **Customizable Overlays:** Display rich notifications over any Android TV app.
- **Flexible Media Positioning:** Place images, videos, or web views at the Top, Bottom, Left, or Right of your text.
- **Live Preview Settings:** Fine-tune colors, borders, and padding with a real-time preview dashboard right on your TV.
- **Modular Design:** Enable or disable optional modules to optimize performance and minimize required permissions.
- **Reliable Power Control:** Remote wake and sleep functionality.
- **Settings Backup & Sync:** Easily export or clone your customized styling configurations from one PiPup device to another over the network.
- **Update Engine:** Built-in self-updater with support for Stable and Beta channels.
- **Smart Queueing & Sync:** Built-in notification service synchronization ensuring alerts are handled smoothly.
- **Customizable Animations:** Choose from 10 different animations (like Slide, Scale, or Bounce).

---

:exclamation: _Important: after installation / updating it is currently advised to open the application once to ensure the background-service and permissions are correctly initialized_ :exclamation:

#### Sideloading:

On Android TV (8.0+), when sideloading, you may need to grant the `SYSTEM_ALERT_WINDOW` permission manually via ADB, as many Android TV devices lack a UI setting for this.

To grant the application the required permission to draw overlays, run:

```bash
adb shell appops set nl.rogro82.pipup SYSTEM_ALERT_WINDOW allow
```

To enable the "Power Control" sleep functionality:

```bash
# Device Admin (preferred)
adb shell dpm set-active-admin --user current nl.rogro82.pipup/.service.AdminReceiver

# Accessibility Service (for devices without the device-admin feature)
adb shell settings put secure --user current enabled_accessibility_services \
    nl.rogro82.pipup/.service.PiPupAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

To enable the optional app update feature:

```bash
adb shell appops set nl.rogro82.pipup REQUEST_INSTALL_PACKAGES allow
```

Disabling battery optimisation is optional, but is recommended:

```bash
adb shell dumpsys deviceidle whitelist +nl.rogro82.pipup
```

## Integrating

PiPup uses an embedded webserver (NanoHTTPD) which runs on port 7979.

### Sending notifications

You can send notifications either as an **application/json** payload (for external media URLs) or as **multipart/form-data** (to upload a local image file).  
For a detailed list of all endpoints and response formats, please refer to the [Full API Reference](docs/api.md).

| _Property_        | _Value_                                     |
| ----------------- | ------------------------------------------- |
| **Path:**         | `/notify`                                   |
| **Method:**       | `POST`                                      |
| **Content-Type:** | `application/json` OR `multipart/form-data` |

#### Example JSON data:

```json
{
    "duration": 10,
    "position": 0,
    "title": "Your awesome title",
    "titleColor": "#FFFFFF",
    "titleSize": 22,
    "titleAlignment": 0,
    "message": "What ever you want to say... do it here...",
    "messageColor": "#FFFFFF",
    "messageSize": 16,
    "messageAlignment": 0,
    "mediaPosition": 0,
    "backgroundColor": "#CC000000",
    "borderRadius": 16,
    "borderWidth": 0,
    "borderColor": "#00000000",
    "contentPadding": 20,
    "animationType": 0,
    "animationDuration": 500,
    "animationExit": false,
    "overwrite": false,
    "media": {
        "image": {
            "uri": "https://mir-s3-cdn-cf.behance.net/project_modules/max_1200/cfcc3137009463.5731d08bd66a1.png",
            "width": 480
        }
    }
}
```

For JSON payloads, the `media` object supports 4 types:

```json
{
"image": { "uri": "...", "width": 480, "cache": true, "scale": true },                      "//comment_im": "cache and scale are optional",
"video": { "uri": "...", "width": 480, "scale": true },                                     "//comment_vi": "scale is optional",
"web":   { "uri": "...", "width": 640, "height": 480, "cache": true, "scale": true },       "//comment_we": "cache and scale are optional",
"whep":  { "uri": "...", "width": 640, "height": 480, "scale": true, "videoFit": "cover" }, "//comment_wh": "videoFit and scale are optional"
}
```

---

### Configuration Properties

All fields below are optional.

#### General & Text Settings

| _Field_               | _Type_  | _Default_ | _Description_                                                          |
| --------------------- | ------- | --------- | ---------------------------------------------------------------------- |
| **duration**          | Integer | 10        | Duration in seconds                                                    |
| **position**          | Integer | 0         | Position index on the screen (0..4)                                    |
| **contentPadding**    | Integer | 20        | Inner padding in pixels between the border and content                 |
| **title**             | String  |           | Title text                                                             |
| **titleSize**         | Float   | 22        | Title font size                                                        |
| **titleColor**        | String  | #FFFFFF   | Color of the title text in `[AA]RRGGBB` format                         |
| **titleAlignment**    | Integer | 0         | Title alignment (0..2)                                                 |
| **message**           | String  |           | Message text                                                           |
| **messageSize**       | Float   | 16        | Message font size                                                      |
| **messageColor**      | String  | #FFFFFF   | Color of the message text in `[AA]RRGGBB` format                       |
| **messageAlignment**  | Integer | 0         | Message alignment (0..2)                                               |
| **animationType**     | Integer | 0         | Animation type index for popup entrance (0..10)                        |
| **animationDuration** | Integer | 500       | Duration of the entrance animation in milliseconds                     |
| **animationExit**     | Boolean | false     | Toggles whether the entrance animation is inverted upon dismissal      |
| **overwrite**         | Boolean | false     | Toggles whether to interrupt the current popup and prioritize this one |
| **mediaTimeout**      | Integer | 10        | Global timeout in seconds for loading external media (1..30)           |

#### Background & Border Styling

| _Field_             | _Type_  | _Default_ | _Description_                                                  |
| ------------------- | ------- | --------- | -------------------------------------------------------------- |
| **backgroundColor** | String  | #CC000000 | Color of the background in `[AA]RRGGBB` format                 |
| **borderRadius**    | Integer | 0         | Radius in pixels (scaled) to round the background corners      |
| **borderWidth**     | Integer | 0         | Width of the border around the notification in pixels (scaled) |
| **borderColor**     | String  | #00000000 | Color of the border in `[AA]RRGGBB` format                     |

#### Media Options

| _Field_           | _Type_  | _Default_ | _Description_                                                                 |
| ----------------- | ------- | --------- | ----------------------------------------------------------------------------- |
| **mediaPosition** | Integer | 0         | Position of the media relative to text (0..3)                                 |
| **image**         | File    |           | **(Multipart only)** Local image file upload                                  |
| **imageWidth**    | Integer | 480       | **(Multipart only)** Width of the uploaded image in pixels                    |
| **cache**         | Boolean | true      | Toggles disk/memory caching for images and web content                        |
| **scale**         | Boolean | true      | Automatically scales dimensions relative to a 1080p reference                 |
| **videoFit**      | String  | cover     | **(WHEP only)** CSS object-fit property for WHEP video (cover, contain, fill) |

---

### Parameter Values & Enums

- **Color-properties** are defined in `[AA]RRGGBB` hex format where the alpha channel is optional (e.g., `#FFFFFF` or `#CCFFFFFF`).
- **`position`** defines where the notification overlay appears on your TV screen:

| _Value_ | _Position_         |
| ------- | ------------------ |
| **0**   | TopRight (Default) |
| **1**   | TopLeft            |
| **2**   | BottomRight        |
| **3**   | BottomLeft         |
| **4**   | Center             |

- **`titleAlignment`** and **`messageAlignment`** control text alignment inside the layout:

| _Value_ | _Alignment_    |
| ------- | -------------- |
| **0**   | Left (Default) |
| **1**   | Center         |
| **2**   | Right          |

- **`mediaPosition`** determines where the media asset is rendered relative to the notification text:

| _Value_ | _Position_    | _Description_                            |
| ------- | ------------- | ---------------------------------------- |
| **0**   | Top (Default) | Media is placed above the text           |
| **1**   | Bottom        | Media is placed below the text           |
| **2**   | Left          | Media is placed to the left of the text  |
| **3**   | Right         | Media is placed to the right of the text |

- **`animationType`** defines the entrance animation of the popup:

| _Value_ | _Animation_    |
| ------- | -------------- |
| **0**   | None (Default) |
| **1**   | Fade           |
| **2**   | Slide          |
| **3**   | Slide & Bounce |
| **4**   | Scale In       |
| **5**   | Scale & Bounce |
| **6**   | Scale Ta-da    |
| **7**   | Slide & Zoom   |
| **8**   | Slide & Flip   |
| **9**   | Slide & Ta-da  |
| **10**  | Diagonal Zoom  |

---

### Cancel notifications

To clear the notification queue and remove the currently displayed notification:

| _Property_  | _Value_ |
| ----------- | ------- |
| **Path:**   | /cancel |
| **Method:** | POST    |

---

### Power Control

You can remotely control the power state of your TV screen.

| _Property_  | _Value_                      |
| ----------- | ---------------------------- |
| **Path:**   | /power?state={on,off,toggle} |
| **Method:** | POST                         |

_Note: Turning the screen off requires the Device Admin or Accessibility permission to be granted._

---

### Backup & Sync Settings

PiPup allows you to remotely view or update the global application settings via the webserver.

#### Get Current Settings

Retrieves a JSON object containing all current styling and layout configurations.

| _Property_  | _Value_   |
| :---------- | :-------- |
| **Path:**   | /settings |
| **Method:** | GET       |

**Example Response:**

```json
{
    "positionIndex": 0,
    "backgroundColor": "#1C1B1F",
    "backgroundAlpha": 225,
    "titleColor": "#E6E1E5",
    "titleSize": 22.0,
    "messageColor": "#E6E1E5",
    "messageSize": 16.0,
    "borderRadius": 16,
    "borderWidth": 0,
    "borderColor": "#00000000",
    "contentPadding": 20,
    "titleAlignment": 0,
    "messageAlignment": 0,
    "mediaPosition": 0,
    "animationType": 0,
    "animationDuration": 500,
    "animationExit": false,
    "mediaTimeout": 10,
    "mediaRetries": 3,
    "appTheme": 0,
    "advancedMode": false,
    "moduleModes": {
        "power": 1,
        "discovery": 1,
        "media": 2,
        "vendor": 0
    },
    "updateChannel": 0,
    "language": "default"
}
```

#### Update Settings

Overwrites and persists the application configurations globally.

| _Property_        | _Value_          |
| ----------------- | ---------------- |
| **Path:**         | /settings        |
| **Method:**       | POST             |
| **Content-Type:** | application/json |

_Note: The payload must match the structure of the `GET` request shown above._

#### Example: Clone Settings via cURL

If you want to quickly clone your complete styling configuration from device A to device B without using the TV interface, you can pipe the `GET` response directly into a `POST` request:

```bash
curl -s http://<IP_DEVICE_A>:7979/settings | \
curl -X POST -H "Content-Type: application/json" -d @- http://<IP_DEVICE_B>:7979/settings
```

---

## Contributors

- Zaheer [zaheer.aws@gmail.com](mailto:zaheer.aws@gmail.com)
- Gemini (Android Studio AI Agent)
