package com.example.healthmonitor;

import android.app.Application;
import android.util.Log;

/**
 * Application class — khởi động một lần khi app mở.
 * Được khai báo android:name=".App" trong Manifest.
 */
public class App extends Application {
    private static final String TAG = "App";

    @Override
    public void onCreate() {
        super.onCreate();
        // Khởi tạo DataManager với context
        AppCore.DataManager.getInstance().init(this);
        Log.d(TAG, "Application started");
    }
}