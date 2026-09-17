package com.example.hrble;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fire-and-forget HTTPS POST of each heart-rate reading.
 *
 * Two hard rules:
 *
 *  1. It must never disturb the BLE path. {@link #submit} only stores the latest
 *     reading and returns; every network call happens on a background thread and
 *     all exceptions are swallowed here rather than propagated.
 *
 *  2. It must not build a backlog. If an upload is still in flight when a newer
 *     reading arrives, the older one is dropped — only the newest is kept. A slow
 *     or dead network therefore cannot queue up unbounded work.
 *
 * Nothing device-identifying is sent: no MAC, no auth key. The payload is exactly
 * {heart_rate, measured_at, source}.
 */
public class HeartRateUploader {

    private static final String TAG = "HRBLE";
    private static final String SOURCE = "mi_band_6";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    public interface Listener {
        /** Called on the upload thread — the UI is responsible for marshalling. */
        void onUploadResult(boolean ok, String detail);

        /** Clock calibration succeeded. All values are milliseconds. */
        void onCalibrationResult(long clockOffsetMs, long rttMs, long uncertaintyMs);

        void onCalibrationFailed(String detail);
    }

    /** One reading waiting to be sent. */
    private static final class Pending {
        final int bpm;
        final long timestamp;

        Pending(int bpm, long timestamp) {
            this.bpm = bpm;
            this.timestamp = timestamp;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hr-upload");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Separate from the upload executor so a calibration request never queues
     * behind the (possibly slow) heart-rate POSTs. That matters for latency, not
     * correctness: t0/t1 bracket only the calibration call itself.
     */
    private final ExecutorService calibrateExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hr-calibrate");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private final Listener listener;

    private volatile String url;
    private volatile String token;

    public HeartRateUploader(Listener listener) {
        this.listener = listener;
    }

    public void configure(String url, String token) {
        this.url = (url == null || url.trim().isEmpty()) ? null : url.trim();
        this.token = (token == null) ? null : token.trim();
    }

    public boolean isConfigured() {
        return url != null && token != null && !token.isEmpty();
    }

    /** Non-blocking. Safe to call straight from the BLE notification callback. */
    public void submit(int bpm, long timestamp) {
        if (!isConfigured()) {
            return;
        }
        pending.set(new Pending(bpm, timestamp));
        pump();
    }

    /** Sends a fixed value so the setup can be verified without waiting for a reading. */
    public void submitTest() {
        if (!isConfigured()) {
            listener.onUploadResult(false, "not configured");
            return;
        }
        pending.set(new Pending(80, System.currentTimeMillis()));
        pump();
    }

    private void pump() {
        if (!inFlight.compareAndSet(false, true)) {
            return; // a drain loop is already running; it will pick up `pending`
        }
        executor.execute(this::drain);
    }

    private void drain() {
        try {
            Pending next;
            while ((next = pending.getAndSet(null)) != null) {
                doUpload(next);
            }
        } finally {
            inFlight.set(false);
            // A reading may have landed after the loop's last null check.
            if (pending.get() != null) {
                pump();
            }
        }
    }

    private void doUpload(Pending reading) {
        HttpURLConnection connection = null;
        try {
            // Taken here, not in submit(), so it includes any time the reading spent
            // queued behind an earlier upload. Both this and measured_at come from the
            // phone's clock, so their difference is free of cross-device clock skew.
            long sentAt = System.currentTimeMillis();

            String body = "{\"heart_rate\":" + reading.bpm
                    + ",\"measured_at\":\"" + Instant.ofEpochMilli(reading.timestamp) + "\""
                    + ",\"sent_at\":\"" + Instant.ofEpochMilli(sentAt) + "\""
                    + ",\"source\":\"" + SOURCE + "\"}";

            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);

            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = connection.getResponseCode();
            boolean ok = code >= 200 && code < 300;
            Log.i(TAG, "upload heart_rate=" + reading.bpm + " http=" + code);
            listener.onUploadResult(ok, "http=" + code);
        } catch (Exception e) {
            // Swallowed on purpose: an upload failure must never affect BLE reading.
            Log.w(TAG, "upload failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            listener.onUploadResult(false, e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ calibration

    private static final Pattern SERVER_TIME =
            Pattern.compile("\"server_time\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * The time endpoint sits beside the heart-rate one, so it is derived rather
     * than configured separately: ".../wearable/heart-rate" -> ".../wearable/time".
     */
    private String timeEndpoint() {
        String configured = url;
        if (configured == null) {
            return null;
        }
        int lastSlash = configured.lastIndexOf('/');
        return lastSlash < 0 ? configured : configured.substring(0, lastSlash + 1) + "time";
    }

    public void calibrate() {
        if (!isConfigured()) {
            listener.onCalibrationFailed("not configured");
            return;
        }
        calibrateExecutor.execute(this::doCalibrate);
    }

    private void doCalibrate() {
        HttpURLConnection connection = null;
        try {
            String endpoint = timeEndpoint();
            if (endpoint == null) {
                listener.onCalibrationFailed("no upload URL configured");
                return;
            }

            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + token);

            // openConnection() does no I/O, so t0 brackets the actual round trip.
            long t0 = System.currentTimeMillis();
            int code = connection.getResponseCode();
            String body = readBody(connection);
            long t1 = System.currentTimeMillis();

            if (code != 200) {
                listener.onCalibrationFailed("http=" + code);
                return;
            }

            Matcher matcher = SERVER_TIME.matcher(body);
            if (!matcher.find()) {
                listener.onCalibrationFailed("no server_time in response");
                return;
            }

            long serverMs = Instant.parse(matcher.group(1)).toEpochMilli();
            long rtt = t1 - t0;
            long clockOffset = serverMs - (t0 + rtt / 2);

            Log.i(TAG, "calibrate endpoint=" + endpoint
                    + " clock_offset=" + clockOffset + "ms"
                    + " rtt=" + rtt + "ms"
                    + " uncertainty=±" + (rtt / 2) + "ms");
            listener.onCalibrationResult(clockOffset, rtt, rtt / 2);
        } catch (Exception e) {
            Log.w(TAG, "calibrate failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            listener.onCalibrationFailed(e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readBody(HttpURLConnection connection) throws Exception {
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        calibrateExecutor.shutdownNow();
    }
}
