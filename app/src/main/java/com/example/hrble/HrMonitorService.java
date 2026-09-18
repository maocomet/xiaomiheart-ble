package com.example.hrble;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Foreground service that owns the whole heart-rate pipeline: scanning, connecting,
 * subscribing, reconnecting, and uploading.
 *
 * Everything lives here rather than in the Activity because the Activity is not a
 * reliable place to keep a BLE link. Before this existed, closing the Activity stopped
 * the readings — {@code MainActivity.onDestroy} shut both the client and the uploader
 * down — so the band was only ever observed while somebody was looking at the screen.
 *
 * The split with the Activity is:
 *
 *   Activity  picks the device once, starts and stops monitoring, renders state
 *   Service   everything else, and it keeps doing it when no Activity exists
 *
 * Delivery of readings to the server is unchanged; {@link HeartRateUploader} is moved
 * here as-is.
 */
public class HrMonitorService extends Service
        implements BleHrClient.Listener, HeartRateUploader.Listener {

    private static final String TAG = BleHrClient.TAG;

    public static final String ACTION_START =
            "com.example.hrble.action.START_MONITORING";
    public static final String ACTION_STOP =
            "com.example.hrble.action.STOP_MONITORING";
    public static final String ACTION_TRIGGER_REALTIME_HR =
            "com.example.hrble.action.TRIGGER_REALTIME_HR";
    public static final String ACTION_STOP_REALTIME_HR =
            "com.example.hrble.action.STOP_REALTIME_HR";

    /** The chosen band's address. Set from the Activity's scan list, never compiled in. */
    public static final String EXTRA_MAC = "mac";

    // Shared with MainActivity, which writes the upload configuration into the same file.
    public static final String PREFS = "hrble";
    public static final String KEY_URL = "upload_url";
    public static final String KEY_TOKEN = "upload_token";
    public static final String KEY_DEVICE_MAC = "device_mac";
    public static final String KEY_DEVICE_LABEL = "device_label";
    /** Persisted so a system restart can tell "was monitoring" from "was stopped". */
    public static final String KEY_MONITORING = "monitoring_enabled";

    private static final String CHANNEL_ID = "hr_monitor";
    private static final int NOTIFICATION_ID = 1;

    private static final long RECONNECT_BASE_MS = 2_000L;
    private static final long RECONNECT_MAX_MS = 60_000L;

    // ------------------------------------------------------------------ UI contract

    /** An immutable snapshot of everything the Activity renders. */
    public static final class Status {
        public final boolean monitoring;
        public final String deviceLabel;
        public final String connection;
        public final int bpm;
        public final long measuredAt;
        public final int notifications;
        public final int uploadOk;
        public final int uploadFail;
        public final String upload;
        public final String calibration;
        public final String realtime;

        Status(boolean monitoring, String deviceLabel, String connection, int bpm,
               long measuredAt, int notifications, int uploadOk, int uploadFail,
               String upload, String calibration, String realtime) {
            this.monitoring = monitoring;
            this.deviceLabel = deviceLabel;
            this.connection = connection;
            this.bpm = bpm;
            this.measuredAt = measuredAt;
            this.notifications = notifications;
            this.uploadOk = uploadOk;
            this.uploadFail = uploadFail;
            this.upload = upload;
            this.calibration = calibration;
            this.realtime = realtime;
        }
    }

    /**
     * Implemented by the Activity. Only ever one at a time: the Activity clears it before
     * unbinding so a stopped Activity cannot be held alive by a running service.
     */
    public interface UiListener {
        void onScanState(String state);

        void onCandidates(List<BleHrClient.Candidate> candidates);

        void onStatus(Status status);
    }

    public class LocalBinder extends Binder {
        public HrMonitorService getService() {
            return HrMonitorService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BleHrClient client;
    private HeartRateUploader uploader;

    private volatile UiListener uiListener;

    // ---- status, all of it published through publish()
    private volatile boolean monitoring;
    private String deviceLabel = "(none)";
    private String connection = "idle";
    private int bpm = -1;
    private long measuredAt;
    private int notifications;
    private int uploadOk;
    private int uploadFail;
    private String upload = "upload: idle";
    private String calibration = "";
    private String realtime = "realtime HR: not requested yet";
    private List<BleHrClient.Candidate> lastCandidates = Collections.emptyList();
    private String lastScanState = "idle";

    private int reconnectAttempts;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        client = new BleHrClient(this, this);
        uploader = new HeartRateUploader(this);
        applyUploadConfig();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();

        if (action == null) {
            // START_STICKY restart after the system killed us. There is no Intent to
            // read, so the persisted flag decides whether resuming is even wanted.
            if (prefs().getBoolean(KEY_MONITORING, false)) {
                Log.i(TAG, "restarted by the system — resuming monitoring");
                if (!enterForeground()) {
                    stopSelf();
                    return START_NOT_STICKY;
                }
                beginMonitoring();
                return START_STICKY;
            }
            Log.i(TAG, "restarted by the system but monitoring was off — stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        switch (action) {
            case ACTION_START: {
                // Promote to foreground before validating anything. The caller used
                // startForegroundService(), and returning without ever calling
                // startForeground() is the case Android punishes with a
                // RemoteServiceException — so no early return may happen first.
                if (!enterForeground()) {
                    stopSelf();
                    return START_NOT_STICKY;
                }

                String mac = intent.getStringExtra(EXTRA_MAC);
                if (mac != null && !mac.trim().isEmpty()) {
                    prefs().edit()
                            .putString(KEY_DEVICE_MAC, mac.trim())
                            .putString(KEY_DEVICE_LABEL,
                                    intent.getStringExtra(KEY_DEVICE_LABEL))
                            .apply();
                }
                if (prefs().getString(KEY_DEVICE_MAC, null) == null) {
                    Log.w(TAG, "start requested with no device chosen");
                    connection = "no device chosen — pick one in the app";
                    publish();
                    stopForegroundCompat();
                    stopSelf();
                    return START_NOT_STICKY;
                }
                beginMonitoring();
                return START_STICKY;
            }

            case ACTION_STOP:
                stopMonitoring();
                return START_NOT_STICKY;

            case ACTION_TRIGGER_REALTIME_HR:
                if (!monitoring) {
                    // Only reachable if the notification outlived monitoring. Without
                    // this the service would sit started but not foreground, which the
                    // system kills anyway — better to end it deliberately.
                    stopSelf();
                    return START_NOT_STICKY;
                }
                triggerRealtimeHr();
                return START_STICKY;

            case ACTION_STOP_REALTIME_HR:
                if (monitoring) {
                    stopRealtimeHr();
                    return START_STICKY;
                }
                stopSelf();
                return START_NOT_STICKY;

            default:
                Log.w(TAG, "unhandled action: " + action);
                return START_STICKY;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // The Activity is going away; drop the reference so a running service cannot
        // keep it (and its whole view tree) alive.
        uiListener = null;
        return false;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (client != null) {
            client.shutdown();
        }
        if (uploader != null) {
            uploader.shutdown();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Activity API

    public void setListener(UiListener value) {
        uiListener = value;
        if (value == null) {
            return;
        }
        // Push the current state straight away. Without this, re-opening the Activity
        // would show a blank screen until the next reading happened to arrive.
        value.onScanState(lastScanState);
        value.onCandidates(lastCandidates);
        value.onStatus(snapshot());
    }

    public Status getStatus() {
        return snapshot();
    }

    /** Whether a band has ever been picked, so the Activity knows to scan on first run. */
    public boolean hasSavedDevice() {
        String mac = prefs().getString(KEY_DEVICE_MAC, null);
        return mac != null && !mac.trim().isEmpty();
    }

    public void startScan() {
        client.startScan();
    }

    /**
     * Picks the band to use from now on and starts monitoring it. The address is
     * persisted, which is what lets a later reconnect happen without a scan and without
     * anybody present to pick from a list.
     */
    public void selectDevice(BleHrClient.Candidate candidate) {
        if (candidate == null) {
            return;
        }
        String label = (candidate.name == null || candidate.name.isEmpty())
                ? candidate.address : candidate.name;

        prefs().edit()
                .putString(KEY_DEVICE_MAC, candidate.address)
                .putString(KEY_DEVICE_LABEL, label)
                .apply();

        deviceLabel = label;
        startMonitoring();
    }

    public void startMonitoring() {
        Intent intent = new Intent(this, HrMonitorService.class)
                .setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    public void stopMonitoring() {
        Log.i(TAG, "stopping monitoring");
        monitoring = false;
        prefs().edit().putBoolean(KEY_MONITORING, false).apply();

        handler.removeCallbacks(reconnect);
        reconnectAttempts = 0;
        client.shutdown();

        connection = "stopped";
        realtime = "realtime HR: idle";
        publish();

        stopForegroundCompat();
        stopSelf();
    }

    /**
     * Asks the band to measure continuously. One command is enough: the band stops on
     * its own after roughly 27 s, measured on a Mi Band 6, which already sits inside the
     * "measure on demand for 30-60 s" window this project wants. Nothing here re-sends
     * it — see {@link BleHrClient#startRealtimeHr}.
     */
    public void triggerRealtimeHr() {
        if (!monitoring) {
            realtime = "realtime HR: not monitoring";
            publish();
            return;
        }
        client.startRealtimeHr();
    }

    public void stopRealtimeHr() {
        client.stopRealtimeHr();
    }

    public void calibrate() {
        uploader.calibrate();
    }

    public void submitTestUpload() {
        uploader.submitTest();
    }

    public void configureUpload(String url, String token) {
        prefs().edit()
                .putString(KEY_URL, url == null ? "" : url.trim())
                .putString(KEY_TOKEN, token == null ? "" : token.trim())
                .apply();
        applyUploadConfig();
        upload = uploader.isConfigured()
                ? "upload: configured"
                : "upload: disabled (enter both URL and token, then Save)";
        publish();
    }

    public String configuredUrl() {
        return prefs().getString(KEY_URL, "");
    }

    public String configuredToken() {
        return prefs().getString(KEY_TOKEN, "");
    }

    // ------------------------------------------------------------------ monitoring

    private void beginMonitoring() {
        monitoring = true;

        // A reconnect may already be queued from a previous session. Resetting the
        // counter without cancelling it would leave two connection attempts racing.
        handler.removeCallbacks(reconnect);
        reconnectAttempts = 0;

        notifications = 0;
        uploadOk = 0;
        uploadFail = 0;
        bpm = -1;
        measuredAt = 0L;

        deviceLabel = prefs().getString(KEY_DEVICE_LABEL, null);
        final String mac = prefs().getString(KEY_DEVICE_MAC, null);
        if (deviceLabel == null || deviceLabel.isEmpty()) {
            deviceLabel = mac == null ? "(none)" : mac;
        }

        prefs().edit().putBoolean(KEY_MONITORING, true).apply();

        connection = "connecting to " + deviceLabel + " ...";
        publish();
        client.connectToAddress(mac);
    }

    private final Runnable reconnect = new Runnable() {
        @Override
        public void run() {
            if (!monitoring) {
                return;
            }
            final String mac = prefs().getString(KEY_DEVICE_MAC, null);
            if (mac == null) {
                stopMonitoring();
                return;
            }
            Log.i(TAG, "reconnect attempt " + (reconnectAttempts + 1));
            client.connectToAddress(mac);
        }
    };

    private void scheduleReconnect(String why) {
        if (!monitoring) {
            return;
        }
        handler.removeCallbacks(reconnect);

        // 2s, 4s, 8s, 16s, 32s, then 60s forever. Capped because an unattended service
        // that retries every 2 s through an hours-long outage is just a battery drain.
        final long delay = Math.min(RECONNECT_MAX_MS,
                RECONNECT_BASE_MS << Math.min(reconnectAttempts, 5));
        reconnectAttempts++;

        Log.i(TAG, "reconnect in " + delay + "ms (" + why + ")");
        connection = "disconnected — retrying in " + (delay / 1000) + "s";
        publish();
        handler.postDelayed(reconnect, delay);
    }

    // ------------------------------------------------------------------ BleHrClient.Listener

    @Override
    public void onScanState(String state) {
        lastScanState = state;
        UiListener target = uiListener;
        if (target != null) {
            target.onScanState(state);
        }
    }

    @Override
    public void onCandidates(List<BleHrClient.Candidate> candidates) {
        lastCandidates = new ArrayList<>(candidates);
        UiListener target = uiListener;
        if (target != null) {
            target.onCandidates(lastCandidates);
        }
    }

    @Override
    public void onConnectionState(String state) {
        connection = state;
        publish();
    }

    @Override
    public void onHeartRate(int bpm, long timestamp) {
        this.bpm = bpm;
        this.measuredAt = timestamp;
        notifications++;

        // Unchanged from the Activity version: fire-and-forget, never blocks BLE.
        uploader.submit(bpm, timestamp);
        publish();
    }

    @Override
    public void onRealtimeHr(String state) {
        realtime = state;
        publish();
    }

    @Override
    public void onLinkState(boolean up, String detail) {
        if (up) {
            reconnectAttempts = 0;
            handler.removeCallbacks(reconnect);
        } else {
            scheduleReconnect(detail);
        }
        publish();
    }

    // ------------------------------------------------------- HeartRateUploader.Listener

    @Override
    public void onUploadResult(boolean ok, String detail) {
        if (ok) {
            uploadOk++;
        } else {
            uploadFail++;
        }
        upload = "upload: " + (ok ? "OK" : "FAILED") + " " + detail
                + "   [ok=" + uploadOk + " fail=" + uploadFail + "]";
        publish();
    }

    @Override
    public void onCalibrationResult(long clockOffsetMs, long rttMs, long uncertaintyMs) {
        calibration = String.format(Locale.US,
                "clock_offset = %d ms (%+.2f s)\nrtt = %d ms\nuncertainty = ± %d ms",
                clockOffsetMs, clockOffsetMs / 1000.0, rttMs, uncertaintyMs);
        publish();
    }

    @Override
    public void onCalibrationFailed(String detail) {
        calibration = "calibration failed: " + detail;
        publish();
    }

    // ------------------------------------------------------------------ publishing

    /**
     * Coalesces bursts of state changes into one notification update and one UI callback.
     * Removing and re-posting means several changes in the same main-loop turn collapse
     * into the last one, which is all anybody can render anyway.
     */
    private void publish() {
        handler.removeCallbacks(publishTask);
        handler.post(publishTask);
    }

    private final Runnable publishTask = new Runnable() {
        @Override
        public void run() {
            if (monitoring) {
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.notify(NOTIFICATION_ID, buildNotification());
                }
            }
            UiListener target = uiListener;
            if (target != null) {
                target.onStatus(snapshot());
            }
        }
    };

    private Status snapshot() {
        return new Status(monitoring, deviceLabel, connection, bpm, measuredAt,
                notifications, uploadOk, uploadFail, upload, calibration, realtime);
    }

    // ------------------------------------------------------------------ foreground

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Heart rate monitoring", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(
                "Keeps the band connection alive and shows the latest reading");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    /**
     * @return false when the system refused to promote us to foreground.
     */
    private boolean enterForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            return true;
        } catch (Exception e) {
            // Android 12+ refuses a foreground start from the background, and that is
            // exactly what a START_STICKY restart after a kill looks like. It has been
            // reported to misfire on 14 and 15 even for services that were already in
            // the foreground. Losing the service is recoverable; crashing the process
            // takes the whole app with it, so this is swallowed deliberately.
            Log.w(TAG, "startForeground refused: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            return false;
        }
    }

    private void stopForegroundCompat() {
        // STOP_FOREGROUND_REMOVE is API 24; minSdk is 26.
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
    }

    private Notification buildNotification() {
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent realtimeIntent = PendingIntent.getService(this, 1,
                new Intent(this, HrMonitorService.class)
                        .setAction(ACTION_TRIGGER_REALTIME_HR),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent stopIntent = PendingIntent.getService(this, 2,
                new Intent(this, HrMonitorService.class)
                        .setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // Action icons are not shown in the shade on modern Android, but the builder
        // still wants one.
        Icon actionIcon = Icon.createWithResource(this,
                android.R.drawable.stat_sys_data_bluetooth);

        String title = bpm >= 0 ? bpm + " bpm" : "Heart rate monitoring";
        String text = deviceLabel + " · " + connection;
        String subText = "upload ok " + uploadOk + " / fail " + uploadFail;

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(subText)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(actionIcon, "Realtime HR",
                        realtimeIntent).build())
                .addAction(new Notification.Action.Builder(actionIcon, "Stop",
                        stopIntent).build())
                .build();
    }

    // ------------------------------------------------------------------ helpers

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void applyUploadConfig() {
        uploader.configure(prefs().getString(KEY_URL, ""),
                prefs().getString(KEY_TOKEN, ""));
        upload = uploader.isConfigured()
                ? "upload: configured"
                : "upload: disabled (enter both URL and token, then Save)";
    }
}
