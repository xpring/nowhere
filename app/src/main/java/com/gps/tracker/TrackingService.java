package com.gps.tracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrackingService extends Service {

    private static final String TAG = "TrackingService";
    private static final String CHANNEL_ID = "gps_tracker_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String TARGET_URL = "http://gps.ctrlall.com/save.php";
    private static final String PREFS_NAME = "GPSTrackerPrefs";

    // Binder for Activity binding
    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        TrackingService getService() { return TrackingService.this; }
    }

    private SharedPreferences prefs;
    private LocationManager locationManager;
    private Handler handler;
    private ExecutorService networkExecutor;

    private String username = "user01";
    private int intervalSeconds = 600;
    private Location lastLocation = null;
    private String lastLocationString = null;
    private String lastSentString = null;
    private boolean gpsFixed = false;

    private Runnable trackingRunnable = new Runnable() {
        @Override
        public void run() {
            sendLocationData();
            handler.postDelayed(this, intervalSeconds * 1000L);
        }
    };

    private LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastLocation = location;
            gpsFixed = true;
            String provider = location.getProvider();
            lastLocationString = String.format(Locale.US,
                "Lat: %.6f, Lng: %.6f (±%.0fm) [%s]",
                location.getLatitude(), location.getLongitude(),
                location.getAccuracy(), provider);
            Log.d(TAG, "Location updated: " + lastLocationString);
            updateNotification("GPS Fixed: " + lastLocationString);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {
            Log.d(TAG, "Provider enabled: " + provider);
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.d(TAG, "Provider disabled: " + provider);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());
        networkExecutor = Executors.newSingleThreadExecutor();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createNotificationChannel();
        Log.d(TAG, "TrackingService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loadSettings();
        startForeground(NOTIFICATION_ID, buildNotification("Starting GPS tracking..."));
        startLocationUpdates();
        handler.removeCallbacks(trackingRunnable);
        handler.post(trackingRunnable);
        Log.d(TAG, "TrackingService started. Interval: " + intervalSeconds + "s, User: " + username);
        return START_STICKY; // Restart if killed
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(trackingRunnable);
        stopLocationUpdates();
        networkExecutor.shutdown();
        prefs.edit().putBoolean("is_running", false).apply();
        Log.d(TAG, "TrackingService destroyed");
    }

    // ---- Public API for Activity ----

    public void updateSettings(String newUsername, int newInterval) {
        username = newUsername;
        intervalSeconds = newInterval;
        prefs.edit()
            .putString("username", username)
            .putInt("interval_seconds", intervalSeconds)
            .apply();
        // Restart timer with new interval
        handler.removeCallbacks(trackingRunnable);
        handler.postDelayed(trackingRunnable, intervalSeconds * 1000L);
        Log.d(TAG, "Settings updated: user=" + username + ", interval=" + intervalSeconds);
    }

    public void sendNow() {
        sendLocationData();
    }

    public String getLastLocationString() { return lastLocationString; }
    public String getLastSentString() { return lastSentString; }

    // ---- Internal ----

    private void loadSettings() {
        username = prefs.getString("username", "user01");
        intervalSeconds = prefs.getInt("interval_seconds", 600);
    }

    @SuppressWarnings({"MissingPermission"})
    private void startLocationUpdates() {
        try {
            // Request from both GPS and Network providers
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L,    // min time: 5 seconds
                    0f,       // min distance: 0 meters
                    locationListener,
                    Looper.getMainLooper()
                );
                // Get last known immediately
                Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last != null) locationListener.onLocationChanged(last);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                );
                Location last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (last != null && (lastLocation == null ||
                        last.getTime() > lastLocation.getTime())) {
                    locationListener.onLocationChanged(last);
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission denied", e);
        }
    }

    private void stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping location updates", e);
        }
    }

    private void sendLocationData() {
        networkExecutor.execute(() -> {
            try {
                String now = getCurrentTimestamp();
                double lat = 0.0, lng = 0.0, alt = 0.0, speed = 0.0, accuracy = 0.0;
                String method = "none";
                String provider = "N/A";

                if (lastLocation != null) {
                    lat      = lastLocation.getLatitude();
                    lng      = lastLocation.getLongitude();
                    alt      = lastLocation.getAltitude();
                    speed    = lastLocation.getSpeed() * 3.6;
                    accuracy = lastLocation.getAccuracy();
                    provider = lastLocation.getProvider() != null ? lastLocation.getProvider() : "unknown";
                    method   = provider.equals(LocationManager.GPS_PROVIDER) ? "gps" : "network";
                }

                int battery = getBatteryLevel();

                String params = "username=" + urlEncode(username)
                        + "&time="     + urlEncode(now)
                        + "&lat="      + lat
                        + "&lng="      + lng
                        + "&alt="      + String.format(Locale.US, "%.2f", alt)
                        + "&speed="    + String.format(Locale.US, "%.2f", speed)
                        + "&accuracy=" + String.format(Locale.US, "%.2f", accuracy)
                        + "&method="   + urlEncode(method)
                        + "&provider=" + urlEncode(provider)
                        + "&fixed="    + (gpsFixed ? "1" : "0")
                        + "&device="   + urlEncode(Build.MODEL)
                        + "&android="  + Build.VERSION.SDK_INT
                        + "&battery="  + battery;

                String fullUrl = TARGET_URL + "?" + params;
                Log.d(TAG, "Sending: " + fullUrl);

                URL url = new URL(fullUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "GPSTracker/1.0 Android");

                int responseCode = conn.getResponseCode();
                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(responseCode >= 200 && responseCode < 300
                                ? conn.getInputStream() : conn.getErrorStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) response.append(line);
                }
                conn.disconnect();

                lastSentString = now + " → HTTP " + responseCode;
                Log.d(TAG, "Response " + responseCode + ": " + response);

                updateNotification(gpsFixed
                        ? String.format(Locale.US, "Tracking: %.5f, %.5f | 🔋%d%%", lat, lng, battery)
                        : "Tracking active (waiting for GPS fix)");

            } catch (Exception e) {
                Log.e(TAG, "Error sending location", e);
                lastSentString = "Error: " + e.getMessage();
                updateNotification("Network error: " + e.getMessage());
            }
        });
    }

    private void sendPost(String params, String timestamp) {
        try {
            URL url = new URL(TARGET_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "GPSTracker/1.0 Android");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(params.getBytes("UTF-8"));
            }

            int responseCode = conn.getResponseCode();
            lastSentString = timestamp + " → POST HTTP " + responseCode;
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "POST error", e);
        }
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date());
    }

    private int getBatteryLevel() {
        try {
            android.content.Intent batteryIntent = registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    return (int)((level / (float) scale) * 100);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Battery read error", e);
        }
        return -1;
    }
    
    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    // ---- Notification ----

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "GPS Tracker",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("GPS tracking service notification");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(
            this, 0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent stopIntent = PendingIntent.getService(
            this, 1,
            new Intent(this, TrackingService.class).setAction("STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracker — " + username)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
