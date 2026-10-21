package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Holds the night mode overlay: one full-screen window that takes no focus and no touches, so the remote keeps
 * driving whatever app is underneath. A foreground service, so Android does not drop the overlay while a video app
 * fills the memory. The window is a single flat colour (NightLight.color), which costs one blended fill per frame.
 */
public class NightLightService extends Service {
    private static final String CHANNEL = "night_light";
    private static final int NOTIFICATION_ID = 7;
    private static NightLightService running;
    private View overlay;

    /** Re-reads the levels into a running overlay. False when the service is not running yet. */
    static boolean update(Context ctx) {
        NightLightService s = running;
        if (s == null) return false;
        s.refresh();
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.night_mode),
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_moon)
                .setContentTitle(getString(R.string.night_mode))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        refresh();
        return START_STICKY;
    }

    private void refresh() {
        if (!NightLight.on(this) || !NightLight.canDraw(this)) {
            stopSelf();
            return;
        }
        int color = NightLight.color(NightLight.dim(this), NightLight.warm(this));
        if (overlay == null) {
            overlay = new View(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setTitle("NightLight");
            try {
                getSystemService(WindowManager.class).addView(overlay, lp);
            } catch (RuntimeException denied) {
                overlay = null;
                stopSelf();
                return;
            }
        }
        overlay.setBackgroundColor(color);
    }

    @Override
    public void onDestroy() {
        if (overlay != null) {
            try {
                getSystemService(WindowManager.class).removeView(overlay);
            } catch (RuntimeException ignored) {
                // already gone with the window manager
            }
            overlay = null;
        }
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
