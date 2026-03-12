package com.gps.tracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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

    // 最小位移（米），小于此距离不重复上报
    private static final float MIN_DISPLACEMENT_METERS = 30.0f;

    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        TrackingService getService() { return TrackingService.this; }
    }

    private SharedPreferences prefs;
    private LocationManager locationManager;
    private Handler handler;
    private ExecutorService networkExecutor;
    private PowerManager.WakeLock wakeLock;

    private String username = "user01";
    private int intervalSeconds = 600;

    private Location lastLocation = null;
    private Location lastReportedLocation = null;  // 上次上报的位置
    private String lastLocationString = null;
    private String lastSentString = null;
    private boolean gpsFixed = false;

    private Runnable trackingRunnable = new Runnable() {
        @Override
        public void run() {
            // 判断是否需要上报（位移过小则跳过）
            if (shouldReport()) {
                sendLocationData();
            } else {
                Log.d(TAG, "Skipped: displacement too small");
                lastSentString = getCurrentTimestamp() + " → 位移过小，跳过";
            }
            handler.postDelayed(this, intervalSeconds * 1000L);
        }
    };

    // 位置监听：使用被动模式 + GPS 混合
    private LocationListener gpsListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            // 精度过差则忽略
            if (location.getAccuracy() > 200) return;
            if (lastLocation == null || isBetterLocation(location, lastLocation)) {
                lastLocation = location;
                gpsFixed = true;
                String provider = location.getProvider() != null ? location.getProvider() : "unknown";
                lastLocationString = String.format(Locale.US,
                    "Lat: %.6f, Lng: %.6f (±%.0fm) [%s]",
                    location.getLatitude(), location.getLongitude(),
                    location.getAccuracy(), provider);
            }
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private LocationListener networkListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (lastLocation == null || isBetterLocation(location, lastLocation)) {
                lastLocation = location;
                String provider = location.getProvider() != null ? location.getProvider() : "network";
                lastLocationString = String.format(Locale.US,
                    "Lat: %.6f, Lng: %.6f (±%.0fm) [%s]",
                    location.getLatitude(), location.getLongitude(),
                    location.getAccuracy(), provider);
            }
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());
        networkExecutor = Executors.newSingleThreadExecutor();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loadSettings();
        startForeground(NOTIFICATION_ID, buildNotification("GPS Tracker 运行中"));
        startLocationUpdates();
        handler.removeCallbacks(trackingRunnable);
        handler.postDelayed(trackingRunnable, 10000L); // 启动后10秒发第一次
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(trackingRunnable);
        stopLocationUpdates();
        releaseWakeLock();
        networkExecutor.shutdown();
        prefs.edit().putBoolean("is_running", false).apply();
    }

    public void updateSettings(String newUsername, int newInterval) {
        username = newUsername;
        intervalSeconds = newInterval;
        prefs.edit()
            .putString("username", username)
            .putInt("interval_seconds", intervalSeconds)
            .apply();
        handler.removeCallbacks(trackingRunnable);
        handler.postDelayed(trackingRunnable, intervalSeconds * 1000L);
        // 重新注册定位，使用新间隔
        stopLocationUpdates();
        startLocationUpdates();
    }

    public void sendNow() { sendLocationData(); }
    public String getLastLocationString() { return lastLocationString; }
    public String getLastSentString() { return lastSentString; }

    private void loadSettings() {
        username = prefs.getString("username", "user01");
        intervalSeconds = prefs.getInt("interval_seconds", 600);
    }

    @SuppressWarnings("MissingPermission")
    private void startLocationUpdates() {
        try {
            // 关键优化：minTime 设为间隔的一半，minDistance 设为30米
            // 避免 GPS 持续开启，只在需要时唤醒
            long minTime = (intervalSeconds * 1000L) / 2;
            float minDistance = MIN_DISPLACEMENT_METERS;

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTime,
                    minDistance,
                    gpsListener,
                    Looper.getMainLooper()
                );
                // 立即获取上次缓存位置
                Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last != null) gpsListener.onLocationChanged(last);
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    minTime,
                    minDistance,
                    networkListener,
                    Looper.getMainLooper()
                );
                Location last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (last != null) networkListener.onLocationChanged(last);
            }

            // 被动定位：复用其他 APP 的定位结果，完全不耗电
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
                locationManager.getAllProviders().contains(LocationManager.PASSIVE_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    0, 0,
                    gpsListener,
                    Looper.getMainLooper()
                );
            }

        } catch (SecurityException e) {
            Log.e(TAG, "Location permission denied", e);
        }
    }

    private void stopLocationUpdates() {
        try {
            locationManager.removeUpdates(gpsListener);
            locationManager.removeUpdates(networkListener);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping location updates", e);
        }
    }

    // 判断是否需要上报（静止时减少上报）
    private boolean shouldReport() {
        if (lastLocation == null) return true;       // 无位置时仍上报（fixed=0）
        if (lastReportedLocation == null) return true; // 首次必须上报
        float distance = lastLocation.distanceTo(lastReportedLocation);
        // 静止（位移 < 30米）时仍然上报，只是跳过完全相同的坐标
        return distance >= 1.0f || lastReportedLocation == null;
    }

    private void sendLocationData() {
        // 短暂唤醒 CPU 保证网络请求完成
        acquireWakeLock();

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

                URL url = new URL(TARGET_URL + "?" + params);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "GPSTracker/1.0 Android");

                int responseCode = conn.getResponseCode();
                conn.disconnect();

                if (responseCode == 200) {
                    lastReportedLocation = lastLocation; // 记录本次上报位置
                }

                lastSentString = now + " → HTTP " + responseCode
                        + " 🔋" + battery + "%";
                Log.d(TAG, "Sent OK: " + responseCode);

                updateNotification(gpsFixed
                    ? String.format(Locale.US, "%.5f, %.5f | 🔋%d%%", lat, lng, battery)
                    : "等待 GPS 信号... | 🔋" + battery + "%");

            } catch (Exception e) {
                Log.e(TAG, "Send error", e);
                lastSentString = "Error: " + e.getMessage();
            } finally {
                releaseWakeLock();
            }
        });
    }

    // 判断新位置是否优于旧位置
    private boolean isBetterLocation(Location newLoc, Location currentLoc) {
        if (currentLoc == null) return true;
        long timeDelta = newLoc.getTime() - currentLoc.getTime();
        if (timeDelta > 60000) return true;   // 超过1分钟的新位置优先
        if (timeDelta < -60000) return false;
        float accuracyDelta = newLoc.getAccuracy() - currentLoc.getAccuracy();
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSameProvider = newLoc.getProvider() != null
                && newLoc.getProvider().equals(currentLoc.getProvider());
        if (isMoreAccurate) return true;
        if (accuracyDelta <= 50 && isSameProvider) return true;
        return false;
    }

    private int getBatteryLevel() {
        try {
            Intent batteryIntent = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) return (int)((level / (float) scale) * 100);
            }
        } catch (Exception e) { Log.e(TAG, "Battery error", e); }
        return -1;
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GPSTracker:send");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(15000L); // 最多持锁15秒
        } catch (Exception e) { Log.e(TAG, "WakeLock error", e); }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception e) { Log.e(TAG, "WakeLock release error", e); }
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date());
    }

    private String urlEncode(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); }
        catch (Exception e) { return value; }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "GPS Tracker", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("GPS tracking service");
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.enableLights(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Tracker — " + username)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
