package com.gps.tracker;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String PREFS_NAME = "GPSTrackerPrefs";

    private EditText etUsername;
    private EditText etInterval;
    private SeekBar seekBarInterval;
    private TextView tvIntervalDisplay;
    private Switch switchTracking;
    private TextView tvStatus;
    private TextView tvLastLocation;
    private TextView tvLastSent;
    private Button btnSaveSettings;
    private Button btnTestNow;
    private LinearLayout layoutStatusCard;

    private SharedPreferences prefs;
    private TrackingService trackingService;
    private boolean serviceBound = false;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TrackingService.LocalBinder binder = (TrackingService.LocalBinder) service;
            trackingService = binder.getService();
            serviceBound = true;
            updateUI();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            trackingService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initViews();
        checkAndRequestPermissions();

        // 首次启动初始化设备标识作为用户名
        if (!prefs.contains("username")) {
            String deviceId = getDeviceIdentifier();
            prefs.edit().putString("username", deviceId).apply();
        }

        // 默认自动开启tracking（首次安装）
        if (!prefs.contains("is_running")) {
            prefs.edit().putBoolean("is_running", true).apply();
        }

        loadSettings();
        setupListeners();

        // 启动时自动开启服务（不触发listener，避免重复）
        if (prefs.getBoolean("is_running", true)) {
            switchTracking.setOnCheckedChangeListener(null); // 临时禁用listener
            switchTracking.setChecked(true);
            setupListeners(); // 重新绑定listener
            startTracking();
            updateStatusCard(true);
        }
    }

    private String getDeviceIdentifier() {
        // 优先尝试获取 IMEI（需要权限）
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                            == PackageManager.PERMISSION_GRANTED) {
                        String imei = null;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            imei = tm.getImei();
                        } else {
                            imei = tm.getDeviceId();
                        }
                        if (imei != null && !imei.isEmpty()) return imei;
                    }
                } else {
                    String imei = tm.getDeviceId();
                    if (imei != null && !imei.isEmpty()) return imei;
                }
            }
        } catch (Exception ignored) {}

        // 次选：Android ID（重置后会变，但无需权限）
        String androidId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId != null && !androidId.isEmpty()
                && !androidId.equals("9774d56d682e549c")) {
            return androidId;
        }

        // 兜底：设备型号+Build序列号
        String serial = "";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                        == PackageManager.PERMISSION_GRANTED) {
                    serial = Build.getSerial();
                }
            } else {
                serial = Build.SERIAL;
            }
        } catch (Exception ignored) {}

        if (!serial.isEmpty() && !serial.equals("unknown")) {
            return Build.MODEL.replaceAll("\\s+", "_") + "_" + serial;
        }

        return Build.MODEL.replaceAll("\\s+", "_") + "_" + Build.ID;
    }

    private void initViews() {
        etUsername = findViewById(R.id.et_username);
        etInterval = findViewById(R.id.et_interval);
        seekBarInterval = findViewById(R.id.seekbar_interval);
        tvIntervalDisplay = findViewById(R.id.tv_interval_display);
        switchTracking = findViewById(R.id.switch_tracking);
        tvStatus = findViewById(R.id.tv_status);
        tvLastLocation = findViewById(R.id.tv_last_location);
        tvLastSent = findViewById(R.id.tv_last_sent);
        btnSaveSettings = findViewById(R.id.btn_save_settings);
        btnTestNow = findViewById(R.id.btn_test_now);
        layoutStatusCard = findViewById(R.id.layout_status_card);
    }

    private void loadSettings() {
        String username = prefs.getString("username", getDeviceIdentifier());
        int interval = prefs.getInt("interval_seconds", 60);
        boolean isRunning = prefs.getBoolean("is_running", true);

        etUsername.setText(username);
        etInterval.setText(String.valueOf(interval));

        int position = intervalToSeekPosition(interval);
        seekBarInterval.setProgress(position);
        tvIntervalDisplay.setText(formatInterval(interval));

        switchTracking.setChecked(isRunning);
        updateStatusCard(isRunning);
    }

    private void setupListeners() {
        seekBarInterval.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int seconds = seekPositionToInterval(progress);
                etInterval.setText(String.valueOf(seconds));
                tvIntervalDisplay.setText(formatInterval(seconds));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        etInterval.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                try {
                    int val = Integer.parseInt(etInterval.getText().toString());
                    val = Math.max(5, Math.min(3600, val));
                    etInterval.setText(String.valueOf(val));
                    seekBarInterval.setProgress(intervalToSeekPosition(val));
                    tvIntervalDisplay.setText(formatInterval(val));
                } catch (NumberFormatException ignored) {}
            }
        });

        switchTracking.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveSettings();
            if (isChecked) {
                startTracking();
            } else {
                stopTracking();
            }
            updateStatusCard(isChecked);
        });

        btnSaveSettings.setOnClickListener(v -> {
            saveSettings();
            if (serviceBound && trackingService != null) {
                trackingService.updateSettings(
                        etUsername.getText().toString().trim(),
                        getIntervalValue()
                );
            }
            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show();
        });

        btnTestNow.setOnClickListener(v -> {
            if (serviceBound && trackingService != null) {
                trackingService.sendNow();
                Toast.makeText(this, "Sending location now...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Service not running. Enable tracking first.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveSettings() {
        String username = etUsername.getText().toString().trim();
        if (username.isEmpty()) username = getDeviceIdentifier();
        int interval = getIntervalValue();
        boolean isRunning = switchTracking.isChecked();
        prefs.edit()
                .putString("username", username)
                .putInt("interval_seconds", interval)
                .putBoolean("is_running", isRunning)
                .apply();
    }

    private int getIntervalValue() {
        try {
            int val = Integer.parseInt(etInterval.getText().toString());
            return Math.max(5, Math.min(3600, val));
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    private void startTracking() {
        if (!hasLocationPermission()) {
            checkAndRequestPermissions();
            switchTracking.setChecked(false);
            return;
        }
        saveSettings();
        Intent serviceIntent = new Intent(this, TrackingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        requestBatteryOptimizationExemption();
    }

    private void stopTracking() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        Intent serviceIntent = new Intent(this, TrackingService.class);
        stopService(serviceIntent);
    }

    private void updateStatusCard(boolean isRunning) {
        if (isRunning) {
            tvStatus.setText("● TRACKING ACTIVE");
            tvStatus.setTextColor(getColor(R.color.green_active));
            layoutStatusCard.setBackgroundResource(R.drawable.card_active);
        } else {
            tvStatus.setText("○ TRACKING STOPPED");
            tvStatus.setTextColor(getColor(R.color.gray_inactive));
            layoutStatusCard.setBackgroundResource(R.drawable.card_inactive);
        }
    }

    private void updateUI() {
        if (serviceBound && trackingService != null) {
            String loc = trackingService.getLastLocationString();
            String sent = trackingService.getLastSentString();
            tvLastLocation.setText(loc != null ? loc : "Waiting for GPS...");
            tvLastSent.setText(sent != null ? sent : "Not sent yet");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.getBoolean("is_running", true) && !serviceBound) {
            Intent serviceIntent = new Intent(this, TrackingService.class);
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
        updateUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void checkAndRequestPermissions() {
        List<String> permsNeeded = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            permsNeeded.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!permsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this)
                        .setTitle("Background Location")
                        .setMessage("Please select 'Allow all the time' for background tracking.")
                        .setPositiveButton("OK", (d, w) -> ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                PERMISSION_REQUEST_CODE + 1))
                        .setNegativeButton("Skip", null)
                        .show();
            }
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try { startActivity(intent); } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private int intervalToSeekPosition(int seconds) {
        if (seconds <= 5) return 0;
        if (seconds >= 3600) return 100;
        double log = Math.log(seconds) / Math.log(3600);
        return (int)(log * 100);
    }

    private int seekPositionToInterval(int progress) {
        double seconds = Math.pow(3600, progress / 100.0);
        seconds = Math.max(5, Math.min(3600, seconds));
        if (seconds < 30) return (int)(Math.round(seconds / 5.0) * 5);
        if (seconds < 120) return (int)(Math.round(seconds / 10.0) * 10);
        if (seconds < 600) return (int)(Math.round(seconds / 30.0) * 30);
        return (int)(Math.round(seconds / 60.0) * 60);
    }

    private String formatInterval(int seconds) {
        if (seconds < 60) return seconds + " seconds";
        if (seconds < 3600) {
            int m = seconds / 60, s = seconds % 60;
            return s == 0 ? m + " min" : m + " min " + s + " sec";
        }
        return (seconds / 3600) + " hour";
    }
}
