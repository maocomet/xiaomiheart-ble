package com.example.hrble;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Minimal BLE central UI: scan, pick a device, watch the heart rate, and
 * optionally forward each reading to a server.
 *
 * The upload URL and bearer token are entered here and kept in app-private
 * SharedPreferences. They are deliberately NOT compiled in: this project is
 * built by CI, and no secret should ever be in the repository.
 */
public class MainActivity extends Activity implements BleHrClient.Listener,
        HeartRateUploader.Listener {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String PREFS = "hrble";
    private static final String KEY_URL = "upload_url";
    private static final String KEY_TOKEN = "upload_token";

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private BleHrClient client;
    private HeartRateUploader uploader;

    private TextView scanStateView;
    private TextView deviceView;
    private TextView connectionView;
    private TextView bpmView;
    private TextView updatedView;
    private TextView countView;
    private EditText urlInput;
    private EditText tokenInput;
    private TextView uploadStatusView;
    private TextView calibrationView;
    private TextView realtimeView;
    private TextView rateView;
    private Button repeatStartButton;
    private ListView candidateList;
    private ArrayAdapter<String> candidateAdapter;

    private final List<BleHrClient.Candidate> candidates = new ArrayList<>();
    private int notificationCount;
    private int uploadOk;
    private int uploadFail;

    // ------------------------------------------------------------ realtime HR experiment

    /**
     * Gadgetbridge re-sends the start command once a second while its Live Activity
     * screen is open ("have to enable it again and again to keep it measuring" —
     * activities/charts/LiveActivityFragment.java:351). Whether one write is enough
     * on this firmware is one of the things this PoC has to answer, so the repeat is
     * a toggle rather than baked in.
     */
    private boolean repeatingStart;
    private static final long REPEAT_INTERVAL_MS = 1_000L;

    private final android.os.Handler repeatHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable repeatStart = new Runnable() {
        @Override
        public void run() {
            if (!repeatingStart) {
                return;
            }
            client.startRealtimeHr();
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS);
        }
    };

    /** Arrival times used to show the notification rate the band is actually delivering. */
    private final ArrayDeque<Long> recentReadings = new ArrayDeque<>();
    private static final long RATE_WINDOW_MS = 30_000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        int smallGap = (int) (4 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        scanStateView = new TextView(this);
        scanStateView.setTextSize(13);

        candidateAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>());
        candidateList = new ListView(this);
        candidateList.setAdapter(candidateAdapter);
        candidateList.setOnItemClickListener((parent, view, position, id) ->
                client.connect(candidates.get(position)));

        deviceView = new TextView(this);
        deviceView.setTextSize(13);

        connectionView = new TextView(this);
        connectionView.setTextSize(13);

        bpmView = new TextView(this);
        bpmView.setTextSize(42);
        bpmView.setText("--");

        updatedView = new TextView(this);
        updatedView.setTextSize(13);

        countView = new TextView(this);
        countView.setTextSize(13);

        realtimeView = new TextView(this);
        realtimeView.setTextSize(12);

        rateView = new TextView(this);
        rateView.setTextSize(13);

        Button hrStartButton = new Button(this);
        hrStartButton.setText("Start realtime HR");
        hrStartButton.setOnClickListener(v -> client.startRealtimeHr());

        Button hrStopButton = new Button(this);
        hrStopButton.setText("Stop realtime HR");
        hrStopButton.setOnClickListener(v -> {
            stopRepeatingStart();
            client.stopRealtimeHr();
        });

        repeatStartButton = new Button(this);
        repeatStartButton.setText("Repeat start every 1s: OFF");
        repeatStartButton.setOnClickListener(v -> {
            if (repeatingStart) {
                stopRepeatingStart();
                realtimeView.setText("repeat start: OFF");
            } else {
                repeatingStart = true;
                repeatStartButton.setText("Repeat start every 1s: ON");
                realtimeView.setText("repeat start: ON — re-sending 15 01 01 every second");
                repeatHandler.post(repeatStart);
            }
        });

        LinearLayout realtimeButtons = new LinearLayout(this);
        realtimeButtons.setOrientation(LinearLayout.HORIZONTAL);
        realtimeButtons.addView(hrStartButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        realtimeButtons.addView(hrStopButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button scanButton = new Button(this);
        scanButton.setText("Scan");
        scanButton.setOnClickListener(v -> client.startScan());

        Button disconnectButton = new Button(this);
        disconnectButton.setText("Disconnect");
        disconnectButton.setOnClickListener(v -> {
            stopRepeatingStart();
            client.disconnect();
        });

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(scanButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(disconnectButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // ---- upload configuration ----

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        urlInput = new EditText(this);
        urlInput.setHint("https://.../wearable/heart-rate");
        urlInput.setTextSize(12);
        urlInput.setText(prefs.getString(KEY_URL, ""));

        tokenInput = new EditText(this);
        tokenInput.setHint("bearer token");
        tokenInput.setTextSize(12);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setText(prefs.getString(KEY_TOKEN, ""));

        Button saveButton = new Button(this);
        saveButton.setText("Save");
        saveButton.setOnClickListener(v -> saveUploadConfig());

        Button testButton = new Button(this);
        testButton.setText("Test upload");
        testButton.setOnClickListener(v -> {
            applyUploadConfig();
            uploader.submitTest();
        });

        Button calibrateButton = new Button(this);
        calibrateButton.setText("Calibrate");
        calibrateButton.setOnClickListener(v -> {
            applyUploadConfig();
            calibrationView.setText("calibrating...");
            uploader.calibrate();
        });

        LinearLayout uploadButtons = new LinearLayout(this);
        uploadButtons.setOrientation(LinearLayout.HORIZONTAL);
        uploadButtons.addView(saveButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        uploadButtons.addView(testButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        uploadButtons.addView(calibrateButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        uploadStatusView = new TextView(this);
        uploadStatusView.setTextSize(12);

        calibrationView = new TextView(this);
        calibrationView.setTextSize(12);

        root.addView(scanStateView);
        root.addView(candidateList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buttons);
        root.addView(deviceView);
        root.addView(connectionView);
        root.addView(bpmView);
        root.addView(updatedView);
        root.addView(countView);
        root.addView(spacer(smallGap));
        root.addView(realtimeButtons);
        root.addView(repeatStartButton);
        root.addView(realtimeView);
        root.addView(rateView);
        root.addView(spacer(smallGap));
        root.addView(urlInput);
        root.addView(tokenInput);
        root.addView(uploadButtons);
        root.addView(uploadStatusView);
        root.addView(calibrationView);

        setContentView(root);

        uploader = new HeartRateUploader(this);
        applyUploadConfig();

        client = new BleHrClient(this, this);
        reset("Idle. Tap Scan.");

        requestMissingPermissions();
    }

    private android.view.View spacer(int height) {
        android.view.View view = new android.view.View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        return view;
    }

    private void saveUploadConfig() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, urlInput.getText().toString().trim())
                .putString(KEY_TOKEN, tokenInput.getText().toString().trim())
                .apply();
        applyUploadConfig();
        uploadStatusView.setText(uploader.isConfigured()
                ? "upload: configured — tap Test upload"
                : "upload: disabled (enter both URL and token, then Save)");
    }

    private void applyUploadConfig() {
        uploader.configure(urlInput.getText().toString(), tokenInput.getText().toString());
    }

    private void reset(String scanState) {
        deviceView.setText("device: (none)");
        connectionView.setText("status: idle");
        bpmView.setText("--");
        updatedView.setText("updated: --");
        countView.setText("notifications received: 0");
        scanStateView.setText(scanState);
        uploadStatusView.setText(uploader.isConfigured()
                ? "upload: configured"
                : "upload: disabled (enter both URL and token, then Save)");
        realtimeView.setText("realtime HR: connect to the band to see 0x2A39 status");
        rateView.setText("rate: --");
        notificationCount = 0;
        uploadOk = 0;
        uploadFail = 0;
        recentReadings.clear();
    }

    private void stopRepeatingStart() {
        if (!repeatingStart) {
            return;
        }
        repeatingStart = false;
        repeatHandler.removeCallbacks(repeatStart);
        repeatStartButton.setText("Repeat start every 1s: OFF");
    }

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : BleHrClient.requiredPermissions()) {
            if (checkSelfPermission(permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            client.startScan();
        } else {
            scanStateView.setText("Requesting permissions...");
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            client.startScan();
        } else {
            scanStateView.setText("Bluetooth permissions denied — grant them in Settings "
                    + "(on Android 12+: Nearby devices).");
        }
    }

    // ------------------------------------------------------------------ BleHrClient.Listener

    @Override
    public void onScanState(String state) {
        runOnUiThread(() -> scanStateView.setText("scan: " + state));
    }

    @Override
    public void onCandidates(List<BleHrClient.Candidate> list) {
        runOnUiThread(() -> {
            candidates.clear();
            candidates.addAll(list);
            candidateAdapter.clear();
            for (BleHrClient.Candidate candidate : list) {
                candidateAdapter.add(candidate.toString());
            }
            candidateAdapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onConnectionState(String state) {
        runOnUiThread(() -> connectionView.setText("status: " + state));
    }

    @Override
    public void onHeartRate(int bpm, long timestamp) {
        runOnUiThread(() -> {
            notificationCount++;
            bpmView.setText(String.valueOf(bpm));
            updatedView.setText("updated: " + timeFormat.format(new Date(timestamp)));
            countView.setText("notifications received: " + notificationCount);
            rateView.setText("rate: " + describeRate(timestamp));
        });

        // Fire-and-forget; returns immediately and never touches the BLE path.
        uploader.submit(bpm, timestamp);
    }

    /**
     * How often 0x2A37 is actually arriving, over a trailing window. This is the
     * number the whole experiment turns on: occasional means the band is measuring
     * on its own schedule, roughly 1/s means the start command took effect.
     */
    private String describeRate(long now) {
        recentReadings.addLast(now);
        while (!recentReadings.isEmpty()
                && now - recentReadings.peekFirst() > RATE_WINDOW_MS) {
            recentReadings.removeFirst();
        }

        int count = recentReadings.size();
        long span = count < 2 ? 0 : now - recentReadings.peekFirst();
        if (span <= 0) {
            return "just started (" + count + " reading)";
        }
        return String.format(Locale.US, "%.2f/s  (%d readings in %ds)",
                count * 1000.0 / span, count, span / 1000);
    }

    @Override
    public void onRealtimeHr(String state) {
        runOnUiThread(() -> realtimeView.setText("realtime HR: " + state));
    }

    // ------------------------------------------------------- HeartRateUploader.Listener

    @Override
    public void onUploadResult(boolean ok, String detail) {
        runOnUiThread(() -> {
            if (ok) {
                uploadOk++;
            } else {
                uploadFail++;
            }
            uploadStatusView.setText("upload: " + (ok ? "OK" : "FAILED") + " " + detail
                    + "   [ok=" + uploadOk + " fail=" + uploadFail + "]");
        });
    }

    @Override
    public void onCalibrationResult(long clockOffsetMs, long rttMs, long uncertaintyMs) {
        runOnUiThread(() -> calibrationView.setText(String.format(Locale.US,
                "clock_offset = %d ms (%+.2f s)\nrtt = %d ms\nuncertainty = ± %d ms",
                clockOffsetMs, clockOffsetMs / 1000.0, rttMs, uncertaintyMs)));
    }

    @Override
    public void onCalibrationFailed(String detail) {
        runOnUiThread(() -> calibrationView.setText("calibration failed: " + detail));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onDestroy() {
        stopRepeatingStart();
        repeatHandler.removeCallbacksAndMessages(null);
        if (client != null) {
            client.shutdown();
        }
        if (uploader != null) {
            uploader.shutdown();
        }
        super.onDestroy();
    }
}
