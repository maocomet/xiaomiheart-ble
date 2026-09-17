package com.example.hrble;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Minimal BLE central UI: scan, pick a device, watch the heart rate.
 *
 * The screen shows exactly what the PoC is meant to answer — whether a connection
 * can be established and whether HR notifications actually flow without any Huami
 * authentication.
 */
public class MainActivity extends Activity implements BleHrClient.Listener {

    private static final int REQUEST_PERMISSIONS = 1;

    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private BleHrClient client;

    private TextView scanStateView;
    private TextView deviceView;
    private TextView connectionView;
    private TextView bpmView;
    private TextView updatedView;
    private TextView countView;
    private ListView candidateList;
    private ArrayAdapter<String> candidateAdapter;

    private final List<BleHrClient.Candidate> candidates = new ArrayList<>();
    private int notificationCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (12 * getResources().getDisplayMetrics().density);

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

        Button scanButton = new Button(this);
        scanButton.setText("Scan");
        scanButton.setOnClickListener(v -> client.startScan());

        Button disconnectButton = new Button(this);
        disconnectButton.setText("Disconnect");
        disconnectButton.setOnClickListener(v -> client.disconnect());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(scanButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(disconnectButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(scanStateView);
        root.addView(candidateList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buttons);
        root.addView(deviceView);
        root.addView(connectionView);
        root.addView(bpmView);
        root.addView(updatedView);
        root.addView(countView);

        setContentView(root);

        client = new BleHrClient(this, this);
        reset("Idle. Tap Scan.");

        requestMissingPermissions();
    }

    private void reset(String scanState) {
        deviceView.setText("device: (none)");
        connectionView.setText("status: idle");
        bpmView.setText("--");
        updatedView.setText("updated: --");
        countView.setText("notifications received: 0");
        scanStateView.setText(scanState);
        notificationCount = 0;
    }

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : BleHrClient.requiredPermissions()) {
            if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
        });
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onDestroy() {
        if (client != null) {
            client.shutdown();
        }
        super.onDestroy();
    }
}
