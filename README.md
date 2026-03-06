# GPS Tracker — Android App

An Android application that tracks GPS location and reports it to `http://gps.ctrlall.com/save.php` at configurable intervals. The app starts automatically on device boot and runs as a foreground service.

---

## Features

- ✅ **Auto-start on boot** — restarts tracking automatically after reboot (if it was active)
- ✅ **Foreground service** — survives background restrictions and battery optimizations
- ✅ **Configurable interval** — 5 seconds to 1 hour (slider or manual input)
- ✅ **Configurable username** — stored persistently
- ✅ **Dual GPS providers** — uses both GPS satellite + network location
- ✅ **Send Now button** — trigger an immediate report
- ✅ **Persistent notification** — shows current tracking status and last coordinates
- ✅ **Survives restarts** — START_STICKY service restarts if killed by Android

---

## Project Structure

```
GPSTracker/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/gps/tracker/
│       │   ├── MainActivity.java        ← UI & settings
│       │   ├── TrackingService.java     ← Foreground service, GPS, HTTP
│       │   └── BootReceiver.java        ← Auto-start on boot
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/strings.xml
│           ├── values/colors.xml
│           ├── values/themes.xml
│           └── drawable/               ← Card & button backgrounds
├── build.gradle
└── settings.gradle
```

---

## Setup & Build

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Java 8+

### Steps

1. **Open in Android Studio**
   ```
   File → Open → select GPSTracker folder
   ```

2. **Sync Gradle**
   - Click "Sync Now" when prompted

3. **Add app icons** (optional)
   - Place `ic_launcher.png` files in `res/mipmap-*` folders
   - Or use Android Studio's Image Asset Studio

4. **Build APK**
   ```
   Build → Build Bundle(s) / APK(s) → Build APK(s)
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`

5. **Install on device**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Permissions Required

| Permission | Purpose |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS coordinates |
| `ACCESS_COARSE_LOCATION` | Network location fallback |
| `ACCESS_BACKGROUND_LOCATION` | Location when screen off (Android 10+) |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot |
| `FOREGROUND_SERVICE` | Background service |
| `FOREGROUND_SERVICE_LOCATION` | Location in foreground service (Android 14) |
| `INTERNET` | Send data to server |
| `WAKE_LOCK` | Keep CPU awake during sending |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent system from killing service |
| `POST_NOTIFICATIONS` | Show tracking notification (Android 13+) |

---

## HTTP Request Format

The app sends a **GET request** to:

```
http://gps.ctrlall.com/save.php?username=USER&time=TIMESTAMP&lat=LAT&lng=LNG&alt=ALT&speed=SPEED&accuracy=ACC&method=METHOD&provider=PROVIDER&fixed=1&device=MODEL&android=API
```

### Parameters

| Parameter | Type | Description |
|---|---|---|
| `username` | string | Configured username |
| `time` | string | `yyyy-MM-dd HH:mm:ss` local time |
| `lat` | double | Latitude (e.g. `40.712776`) |
| `lng` | double | Longitude (e.g. `-74.005974`) |
| `alt` | double | Altitude in meters |
| `speed` | double | Speed in km/h |
| `accuracy` | double | GPS accuracy radius in meters |
| `method` | string | `gps` or `network` |
| `provider` | string | Raw provider name |
| `fixed` | int | `1` if GPS fix obtained, `0` if waiting |
| `device` | string | Device model name |
| `android` | int | Android API level |

### Example URL
```
http://gps.ctrlall.com/save.php?username=user01&time=2024-01-15+14%3A30%3A00&lat=40.712776&lng=-74.005974&alt=10.50&speed=0.00&accuracy=5.00&method=gps&provider=gps&fixed=1&device=Pixel+7&android=34
```

---

## Battery & Background Behavior

On first run, the app requests **battery optimization exemption**. This is essential for reliable background operation.

On Android 12+, if the service is killed:
- `START_STICKY` ensures Android restarts it automatically
- The `BootReceiver` ensures it restarts after a reboot

For **guaranteed delivery** on aggressive OEMs (Xiaomi, Huawei, Samsung), also manually:
- Add the app to "Protected apps" or "Autostart" in system settings
- Disable battery optimization for the app

---

## Server-Side (PHP Example)

Your `save.php` can receive data like this:

```php
<?php
$username  = $_GET['username'] ?? '';
$time      = $_GET['time'] ?? date('Y-m-d H:i:s');
$lat       = floatval($_GET['lat'] ?? 0);
$lng       = floatval($_GET['lng'] ?? 0);
$alt       = floatval($_GET['alt'] ?? 0);
$speed     = floatval($_GET['speed'] ?? 0);
$accuracy  = floatval($_GET['accuracy'] ?? 0);
$method    = $_GET['method'] ?? 'unknown';
$provider  = $_GET['provider'] ?? 'unknown';
$fixed     = intval($_GET['fixed'] ?? 0);
$device    = $_GET['device'] ?? 'unknown';
$android   = intval($_GET['android'] ?? 0);

// Store to database or log file...
$line = implode(',', [$username,$time,$lat,$lng,$alt,$speed,$accuracy,$method,$fixed,$device])."\n";
file_put_contents('locations.csv', $line, FILE_APPEND);

echo "OK";
?>
```

---

## Minimum Android Version

**Android 5.0 (API 21)** — supports ~99% of active Android devices.
