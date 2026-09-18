package com.example.hrble;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
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
 * Controls and status display for {@link HrMonitorService}. It deliberately owns no
 * BLE and no networking: the service does all of that, and keeps doing it after this
 * Activity is gone.
 *
 * What is left here is exactly what needs a screen: picking the band once, starting and
 * stopping monitoring, and rendering whatever the service reports. The Activity binds
 * for as long as it is visible and clears its listener on the way out, so it never
 * keeps the service alive and the service never holds a dead Activity.
 */
public class MainActivity extends Activity implements HrMonitorService.UiListener {

    private static final int REQUEST_PERMISSIONS = 1;

    private HrMonitorService service;
    private boolean bound;

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TextView serviceView;
    private TextView scanStateView;
    private TextView deviceView;
    private TextView connectionView;
    private TextView bpmView;
    private TextView updatedView;
    private TextView countView;
    private TextView rateView;
    private TextView realtimeView;
    private TextView uploadStatusView;
    private TextView calibrationView;
    private EditText urlInput;
    private EditText tokenInput;
    private Button startButton;
    private Button stopButton;
    private Button scanButton;
    private ListView candidateList;
    private ArrayAdapter<String> candidateAdapter;

    private final List<BleHrClient.Candidate> candidates = new ArrayList<>();

    /** Arrival times used to show the rate the band is actually delivering. */
    private final ArrayDeque<Long> recentReadings = new ArrayDeque<>();
    private static final long RATE_WINDOW_MS = 30_000L;
    private long lastRateSample;
    private String rateText = "rate: --";

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((HrMonitorService.LocalBinder) binder).getService();
            bound = true;

            urlInput.setText(service.configuredUrl());
            tokenInput.setText(service.configuredToken());

            // Also pushes the current scan state, candidate list and status, so
            // re-opening the Activity shows the live picture instead of a blank one.
            service.setListener(MainActivity.this);

            if (!service.hasSavedDevice()) {
                service.startScan();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        int smallGap = (int) (4 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        serviceView = new TextView(this);
        serviceView.setTextSize(13);

        candidateAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>());
        candidateList = new ListView(this);
        candidateList.setAdapter(candidateAdapter);
        candidateList.setOnItemClickListener((parent, view, position, id) -> {
            if (service != null) {
                service.selectDevice(candidates.get(position));
            }
        });

        scanStateView = new TextView(this);
        scanStateView.setTextSize(13);

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

        rateView = new TextView(this);
        rateView.setTextSize(13);

        realtimeView = new TextView(this);
        realtimeView.setTextSize(12);

        scanButton = new Button(this);
        scanButton.setText("Scan");
        scanButton.setOnClickListener(v -> {
            if (service != null) {
                service.startScan();
            }
        });

        startButton = new Button(this);
        startButton.setText("Start monitoring");
        startButton.setOnClickListener(v -> {
            if (service != null) {
                service.startMonitoring();
            }
        });

        stopButton = new Button(this);
        stopButton.setText("Stop monitoring");
        stopButton.setOnClickListener(v -> {
            if (service != null) {
                service.stopMonitoring();
            }
        });

        LinearLayout scanRow = new LinearLayout(this);
        scanRow.setOrientation(LinearLayout.HORIZONTAL);
        scanRow.addView(scanButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scanRow.addView(startButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scanRow.addView(stopButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button hrStartButton = new Button(this);
        hrStartButton.setText("Start realtime HR");
        hrStartButton.setOnClickListener(v -> {
            if (service != null) {
                service.triggerRealtimeHr();
            }
        });

        Button hrStopButton = new Button(this);
        hrStopButton.setText("Stop realtime HR");
        hrStopButton.setOnClickListener(v -> {
            if (service != null) {
                service.stopRealtimeHr();
            }
        });

        LinearLayout realtimeButtons = new LinearLayout(this);
        realtimeButtons.setOrientation(LinearLayout.HORIZONTAL);
        realtimeButtons.addView(hrStartButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        realtimeButtons.addView(hrStopButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // ---- upload configuration ----

        urlInput = new EditText(this);
        urlInput.setHint("https://.../wearable/heart-rate");
        urlInput.setTextSize(12);

        tokenInput = new EditText(this);
        tokenInput.setHint("bearer token");
        tokenInput.setTextSize(12);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        Button saveButton = new Button(this);
        saveButton.setText("Save");
        saveButton.setOnClickListener(v -> {
            if (service != null) {
                service.configureUpload(urlInput.getText().toString(),
                        tokenInput.getText().toString());
            }
        });

        Button testButton = new Button(this);
        testButton.setText("Test upload");
        testButton.setOnClickListener(v -> {
            if (service != null) {
                service.submitTestUpload();
            }
        });

        Button calibrateButton = new Button(this);
        calibrateButton.setText("Calibrate");
        calibrateButton.setOnClickListener(v -> {
            if (service != null) {
                service.calibrate();
            }
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

        root.addView(serviceView);
        root.addView(scanRow);
        root.addView(scanStateView);
        root.addView(candidateList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(deviceView);
        root.addView(connectionView);
        root.addView(bpmView);
        root.addView(updatedView);
        root.addView(countView);
        root.addView(rateView);
        root.addView(spacer(smallGap));
        root.addView(realtimeButtons);
        root.addView(realtimeView);
        root.addView(spacer(smallGap));
        root.addView(urlInput);
        root.addView(tokenInput);
        root.addView(uploadButtons);
        root.addView(uploadStatusView);
        root.addView(calibrationView);

        setContentView(root);

        requestMissingPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Binding creates the service if it is not running; if monitoring is on it is
        // already running and this just re-attaches.
        bindService(new Intent(this, HrMonitorService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (bound) {
            // Clear before unbinding. The service outlives this Activity whenever
            // monitoring is on, and must not hold a reference to a dead one.
            if (service != null) {
                service.setListener(null);
            }
            unbindService(serviceConnection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    private android.view.View spacer(int height) {
        android.view.View view = new android.view.View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        return view;
    }

    // ------------------------------------------------------------------ permissions

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : BleHrClient.requiredPermissions()) {
            if (checkSelfPermission(permission)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        // Being denied this only hides the notification; the service still runs, so a
        // refusal is not treated as fatal.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            missing.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && !BleHrClient.hasScanPermission(this)) {
            scanStateView.setText("Bluetooth permissions denied — grant them in Settings "
                    + "(on Android 12+: Nearby devices).");
        }
    }

    // ------------------------------------------------------------------ Listener

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
    public void onStatus(HrMonitorService.Status status) {
        runOnUiThread(() -> {
            serviceView.setText(status.monitoring
                    ? "service: running (foreground) — survives this screen closing"
                    : "service: idle — nothing is being read");
            deviceView.setText("device: " + status.deviceLabel);
            connectionView.setText("status: " + status.connection);
            bpmView.setText(status.bpm >= 0 ? String.valueOf(status.bpm) : "--");
            updatedView.setText(status.measuredAt > 0
                    ? "updated: " + timeFormat.format(new Date(status.measuredAt))
                    : "updated: --");
            countView.setText("notifications received: " + status.notifications);
            rateView.setText(describeRate(status.measuredAt));
            realtimeView.setText(status.realtime);
            uploadStatusView.setText(status.upload);
            calibrationView.setText(status.calibration);

            startButton.setEnabled(!status.monitoring);
            stopButton.setEnabled(status.monitoring);
        });
    }

    /**
     * Arrivals per second over a trailing window, computed from the timestamps the
     * readings carry. Repeated status updates that report the same reading do not
     * disturb it.
     */
    private String describeRate(long measuredAt) {
        if (measuredAt <= 0 || measuredAt == lastRateSample) {
            return rateText;
        }
        lastRateSample = measuredAt;

        recentReadings.addLast(measuredAt);
        while (!recentReadings.isEmpty()
                && measuredAt - recentReadings.peekFirst() > RATE_WINDOW_MS) {
            recentReadings.removeFirst();
        }

        int count = recentReadings.size();
        long span = count < 2 ? 0 : measuredAt - recentReadings.peekFirst();
        rateText = span <= 0
                ? "rate: just started (" + count + " reading)"
                : String.format(Locale.US, "rate: %.2f/s  (%d readings in %ds)",
                        count * 1000.0 / span, count, span / 1000);
        return rateText;
    }
}
