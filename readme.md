# PiPup

PiPup is an application that allows displaying user-defined custom notifications on Android TV.

The most common use-case for this application is for sending notifications, from a home-automation solution, to your Android TV.

![](graphics/screenshot-1.png)

**Some example scenarios:**

- Show a snapshot of your camera on your TV (e.g. on a motion trigger)
- Display a notification with the video of your camera when someone is at your door
- Send a notification when your dryer/washingmachine is ready
- Anything else you might find useful

**The application is currently in a `public beta`**

To enter the `beta` and install the application on your device go to:  
https://play.google.com/apps/testing/nl.rogro82.pipup

_Important: after installation / updating it is currently advised to restart your TV and open the application once to make sure the background-service is running_

#### Sideloading:

On Android TV (8.0+), when sideloading, you will need to set the permission for SYSTEM_ALERT_WINDOW manually (using adb) as there is no interface on Android TV to do this.

To give the application the required permission to draw overlays you will need to run:

```
adb shell appops set nl.rogro82.pipup SYSTEM_ALERT_WINDOW allow
```

## Integrating

PiPup uses an embedded webserver (NanoHTTPD) which runs on port 7979.

### Sending notifications

#### To send notifications with an external media resource (image, url or webview) use application/json

| _Property_        | _Value_                   |
| ----------------- | ------------------------- |
| **Path:**         | /notify, / or /api/notify |
| **Method:**       | POST                      |
| **Content-Type:** | application/json          |

Example JSON data:

```json
{
  "duration": 10,
  "position": 0,
  "title": "Your awesome title",
  "titleColor": "#FFFFFF",
  "titleSize": 24,
  "titleAlignment": 0,
  "message": "What ever you want to say... do it here...",
  "messageColor": "#FFFFFF",
  "messageSize": 16,
  "messageAlignment": 0,
  "backgroundColor": "#CC000000",
  "borderRadius": 0,
  "borderWidth": 0,
  "borderColor": "#00000000",
  "contentPadding": 16,
  "media": {
    "image": {
      "uri": "https://mir-s3-cdn-cf.behance.net/project_modules/max_1200/cfcc3137009463.5731d08bd66a1.png",
      "width": 480
    }
  }
}
```

All fields are optional and for `media` you can specify 3 types:

```json
"image": { "uri": "...", "width": 480, "cache": true, "scale": true } // cache and scale are optional
"video": { "uri": "...", "width": 480, "scale": true } // scale is optional
"web":   { "uri": "...", "width": 640, "height": 480, "cache": true, "scale": true } // cache and scale are optional
```

The `image`, `video`, and `web` objects support additional **optional** flags:

| _Field_   | _Type_  | _Default_ | _Description_                                                 |
| --------- | ------- | --------- | ------------------------------------------------------------- |
| **cache** | Boolean | true      | Toggles disk and memory caching for images and web content    |
| **scale** | Boolean | true      | Automatically scales dimensions relative to a 1080p reference |

#### Background Styling

You can customize the appearance of the notification background using the following optional top-level properties:

| _Field_             | _Type_  | _Default_ | _Description_                                                    |
| ------------------- | ------- | --------- | ---------------------------------------------------------------- |
| **backgroundColor** | String  | #CC000000 | Color of the background in `[AA]RRGGBB` format                   |
| **borderRadius**    | Integer | 0         | Radius in pixels (scaled) to round the corners of the background |
| **borderWidth**     | Integer | 0         | Width of the border around the notification in pixels (scaled)   |
| **borderColor**     | String  | #00000000 | Color of the border in `[AA]RRGGBB` format                       |

You can customize the appearance of the notification background and text using the following optional top-level properties.

#### To send notifications with an image file use multipart/form-data

| _Property_        | _Value_             |
| ----------------- | ------------------- |
| **Path:**         | /notify             |
| **Method:**       | POST                |
| **Content-Type:** | multipart/form-data |

Form-fields:

| _Field_              | _Type_  | _Default_ | _Description_                                    |
| -------------------- | ------- | --------- | ------------------------------------------------ |
| **duration**         | Integer | 30        | Duration in seconds                              |
| **position**         | Integer | 0         | Position index (0..4)                            |
| **title**            | String  |           | Title text                                       |
| **titleSize**        | Float   | 14        | Title font size                                  |
| **titleColor**       | string  | #FFFFFF   | Color of the title text in `[AA]RRGGBB` format   |
| **titleAlignment**   | Integer | 0         | Title alignment (0..2)                           |
| **message**          | String  |           | Message text                                     |
| **messageSize**      | Float   | 14        | Message font size                                |
| **messageColor**     | String  | #FFFFFF   | Color of the message text in `[AA]RRGGBB` format |
| **messageAlignment** | Integer | 0         | Message alignment (0..2)                         |
| **image**            | File    |           | Local image file (multipart only)                |
| **imageWidth**       | Integer | 480       | Width in pixels                                  |

`position` is an enum ranging from 0 to 4

|       | _Position_  |
| ----: | ----------- |
| **0** | TopRight    |
| **1** | TopLeft     |
| **2** | BottomRight |
| **3** | BottomLeft  |
| **4** | Center      |

Color-properties are in `[AA]RRGGBB` where the alpha channel is optional e.g. #FFFFFF or #CCFFFFFF

`titleAlignment` and `messageAlignment` is an enum ranging from 0 to 2

|       | _Position_ |
| ----: | ---------- |
| **0** | left       |
| **1** | center     |
| **2** | right      |

### Cancel notifications

To clear the notification queue and remove the currently displayed notification:

| _Property_  | _Value_ |
| ----------- | ------- |
| **Path:**   | /cancel |
| **Method:** | POST    |

### Contributors:

    - Zaheer <zaheer.aws@gmail.com>
