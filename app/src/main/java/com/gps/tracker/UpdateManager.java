package com.gps.tracker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpdateManager {

    private static final String APK_URL = "http://gps.ctrlall.com/gps.apk";
    private static final String APK_NAME = "gps_update.apk";

    private final Activity activity;
    private final TextView tvStatus;
    private final ProgressBar progressBar;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    public UpdateManager(Activity activity, TextView tvStatus, ProgressBar progressBar) {
        this.activity    = activity;
        this.tvStatus    = tvStatus;
        this.progressBar = progressBar;
    }

    public void startUpdate() {
        setStatus("正在连接服务器...");
        progressBar.setVisibility(android.view.View.VISIBLE);
        progressBar.setProgress(0);

        executor.execute(() -> {
            try {
                URL url = new URL(APK_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "GPSTracker-Updater/1.0");
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    setStatus("服务器错误：HTTP " + responseCode);
                    hideProgress();
                    return;
                }

                int fileSize = conn.getContentLength();
                setStatus("开始下载" + (fileSize > 0
                    ? "（" + (fileSize / 1024 / 1024) + " MB）"
                    : ""));

                // 保存到外部存储 Downloads 目录
                File outFile = getApkFile();
                if (outFile.exists()) outFile.delete();

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(outFile)) {

                    byte[] buf = new byte[8192];
                    int read;
                    long downloaded = 0;

                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                        downloaded += read;
                        if (fileSize > 0) {
                            final int pct = (int)(downloaded * 100 / fileSize);
                            handler.post(() -> progressBar.setProgress(pct));
                        }
                        final long dl = downloaded;
                        setStatus("下载中... " + (dl / 1024) + " KB"
                            + (fileSize > 0 ? " / " + (fileSize/1024) + " KB" : ""));
                    }
                    out.flush();
                }

                conn.disconnect();
                setStatus("下载完成，准备安装...");
                handler.post(() -> {
                    progressBar.setProgress(100);
                    installApk(outFile);
                });

            } catch (Exception e) {
                setStatus("下载失败：" + e.getMessage());
                hideProgress();
            }
        });
    }

    private void installApk(File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri apkUri;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile
                );
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apkUri = Uri.fromFile(apkFile);
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);

        } catch (Exception e) {
            setStatus("安装失败：" + e.getMessage());
            Toast.makeText(activity, "安装失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private File getApkFile() {
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = activity.getFilesDir();
        return new File(dir, APK_NAME);
    }

    private void setStatus(String msg) {
        handler.post(() -> tvStatus.setText(msg));
    }

    private void hideProgress() {
        handler.post(() -> progressBar.setVisibility(android.view.View.GONE));
    }
}
