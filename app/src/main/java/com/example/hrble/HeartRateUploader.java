package com.example.hrble;

import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
            String body = "{\"heart_rate\":" + reading.bpm
                    + ",\"measured_at\":\"" + Instant.ofEpochMilli(reading.timestamp) + "\""
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

    public void shutdown() {
        executor.shutdownNow();
    }
}
