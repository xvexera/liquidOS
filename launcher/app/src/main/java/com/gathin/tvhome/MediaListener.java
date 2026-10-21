package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets the launcher read the active media sessions (MediaSessionManager.getActiveSessions
 * requires an enabled notification listener). It reads no notifications. Grant it once over adb:
 * {@code cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener}
 */
public class MediaListener extends NotificationListenerService {
}
