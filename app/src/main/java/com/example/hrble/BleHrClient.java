package com.example.hrble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * BLE central that connects directly to the band and subscribes to the standard
 * Heart Rate Measurement characteristic.
 *
 * Deliberately performs NO Huami authentication handshake: the whole question this
 * stage answers is whether the band's HR service is readable without the auth_key
 * once Gadgetbridge's "3rd party realtime HR access" has been switched on.
 *
 * If the connection succeeds but no notifications arrive, this class does not try
 * harder — it dumps the discovered service/characteristic map to logcat and reports
 * the state, which is the evidence needed to decide the next move.
 */
public class BleHrClient {

    public static final String TAG = "HRBLE";

    private static final long SCAN_TIMEOUT_MS = 20_000L;

    public interface Listener {
        void onScanState(String state);

        void onCandidates(List<Candidate> candidates);

        void onConnectionState(String state);

        void onHeartRate(int bpm, long timestamp);
    }

    /** A scan result, ranked so the likeliest band sorts to the top. */
    public static class Candidate {
        public final BluetoothDevice device;
        public final String name;
        public final String address;
        public final int rssi;
        public final boolean advertisesHrService;
        public final int rank;

        Candidate(BluetoothDevice device, String name, String address, int rssi,
                  boolean advertisesHrService, int rank) {
            this.device = device;
            this.name = name;
            this.address = address;
            this.rssi = rssi;
            this.advertisesHrService = advertisesHrService;
            this.rank = rank;
        }

        @Override
        public String toString() {
            String label = (name == null || name.isEmpty()) ? "(unnamed)" : name;
            return label + "   " + address + "   rssi=" + rssi
                    + (advertisesHrService ? "   [advertises 0x180D]" : "");
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Keyed by MAC so repeated scan results update in place. */
    private final Map<String, Candidate> seen = new LinkedHashMap<>();

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning;

    public BleHrClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    // ------------------------------------------------------------------ permissions

    /** Runtime permissions needed on the running OS version. */
    public static String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    public static boolean hasScanPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ scanning

    public void startScan() {
        if (scanning) {
            return;
        }
        if (!hasScanPermission(context)) {
            listener.onScanState("missing scan permission");
            return;
        }

        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onScanState("Bluetooth is off or unavailable");
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onScanState("no BLE scanner available");
            return;
        }

        seen.clear();
        listener.onCandidates(Collections.emptyList());

        // Unfiltered on purpose: the band is not guaranteed to put 0x180D in its
        // advertisement, so a service-UUID ScanFilter could hide it entirely.
        // Ranking happens client-side in addCandidate() instead.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;
        listener.onScanState("scanning...");
        Log.i(TAG, "startScan");
        scanner.startScan(null, settings, scanCallback);

        handler.postDelayed(() -> {
            if (scanning) {
                stopScan();
                listener.onScanState("scan finished — " + seen.size() + " device(s), pick the band below");
            }
        }, SCAN_TIMEOUT_MS);
    }

    public void stopScan() {
        if (!scanning || scanner == null) {
            scanning = false;
            return;
        }
        scanning = false;
        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException e) {
            Log.w(TAG, "stopScan denied: " + e.getMessage());
        }
        Log.i(TAG, "stopScan");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            addCandidate(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                addCandidate(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            String state = "scan failed: errorCode=" + errorCode + " (" + scanErrorName(errorCode) + ")";
            Log.w(TAG, state);
            listener.onScanState(state);
        }
    };

    private void addCandidate(ScanResult result) {
        BluetoothDevice device = result.getDevice();
        if (device == null) {
            return;
        }

        String address;
        String deviceName = null;
        try {
            address = device.getAddress();
            deviceName = device.getName();
        } catch (SecurityException e) {
            return; // BLUETOOTH_CONNECT not granted
        }

        ScanRecord record = result.getScanRecord();
        String advertisedName = record == null ? null : record.getDeviceName();
        String name = (advertisedName != null && !advertisedName.isEmpty()) ? advertisedName : deviceName;

        boolean advertisesHrService = false;
        if (record != null && record.getServiceUuids() != null) {
            for (ParcelUuid parcelUuid : record.getServiceUuids()) {
                if (HrParser.UUID_HR_SERVICE.equalsIgnoreCase(parcelUuid.getUuid().toString())) {
                    advertisesHrService = true;
                    break;
                }
            }
        }

        Candidate candidate = new Candidate(device, name, address, result.getRssi(),
                advertisesHrService, rank(name, advertisesHrService));
        seen.put(address, candidate);
        listener.onCandidates(sortedCandidates());
    }

    /**
     * Lower is better: an advertised HR service is the strongest signal, then a
     * name that looks like a Mi/Xiaomi band, then anything else that at least
     * announced a name.
     */
    private static int rank(String name, boolean advertisesHrService) {
        if (advertisesHrService) {
            return 0;
        }
        if (name != null && looksLikeBand(name)) {
            return 1;
        }
        if (name != null && !name.isEmpty()) {
            return 2;
        }
        return 3;
    }

    private static boolean looksLikeBand(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.contains("mi band") || n.contains("miband") || n.contains("mi smart band")
                || n.contains("smart band") || n.contains("xiaomi")
                || n.contains("amazfit") || n.contains("zepp");
    }

    private List<Candidate> sortedCandidates() {
        List<Candidate> list = new ArrayList<>(seen.values());
        Collections.sort(list, (a, b) -> {
            if (a.rank != b.rank) {
                return Integer.compare(a.rank, b.rank);
            }
            return Integer.compare(b.rssi, a.rssi); // stronger signal first
        });
        return list;
    }

    // ------------------------------------------------------------------ connecting

    public void connect(Candidate candidate) {
        stopScan();

        if (candidate == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            listener.onConnectionState("missing connect permission");
            return;
        }

        closeGatt();

        String label = (candidate.name == null || candidate.name.isEmpty())
                ? candidate.address : candidate.name;
        listener.onConnectionState("connecting to " + label + " ...");
        Log.i(TAG, "connectGatt " + candidate.address);

        gatt = candidate.device.connectGatt(context, false, gattCallback,
                BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) {
            listener.onConnectionState("connectGatt() returned null");
        }
    }

    public void disconnect() {
        closeGatt();
        listener.onConnectionState("disconnected by user");
    }

    private void closeGatt() {
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException e) {
                Log.w(TAG, "close denied: " + e.getMessage());
            }
            gatt = null;
        }
    }

    /** Release everything; call from the Activity's onDestroy. */
    public void shutdown() {
        stopScan();
        closeGatt();
        handler.removeCallbacksAndMessages(null);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.i(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                String state = "connection error: status=" + status
                        + " (" + gattStatusName(status) + "), newState=" + newState;
                Log.w(TAG, state);
                listener.onConnectionState(state);
                closeGatt();
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onConnectionState("connected — discovering services...");
                boolean started = g.discoverServices();
                if (!started) {
                    listener.onConnectionState("discoverServices() returned false");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onConnectionState("disconnected");
                closeGatt();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            // Always dump the full map — this is the evidence collected when things
            // do not work out, and it costs nothing when they do.
            dumpServices(g, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onConnectionState("service discovery failed: status=" + status);
                return;
            }

            BluetoothGattService hrService =
                    g.getService(UUID.fromString(HrParser.UUID_HR_SERVICE));
            if (hrService == null) {
                listener.onConnectionState(
                        "Heart Rate service 0x180D NOT found — full service map in logcat");
                return;
            }

            BluetoothGattCharacteristic hrCharacteristic =
                    hrService.getCharacteristic(UUID.fromString(HrParser.UUID_HR_MEASUREMENT));
            if (hrCharacteristic == null) {
                listener.onConnectionState("0x2A37 not present inside 0x180D");
                return;
            }

            subscribe(g, hrCharacteristic);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor,
                                      int status) {
            if (!HrParser.UUID_CCCD.equalsIgnoreCase(descriptor.getUuid().toString())) {
                return;
            }
            Log.i(TAG, "CCCD write status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onConnectionState(
                        "CCCD written — waiting for heart rate notifications");
            } else {
                listener.onConnectionState("CCCD write failed: status=" + status
                        + " (" + gattStatusName(status) + ")");
            }
        }

        // API 33+ delivers the value directly.
        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            handleNotification(characteristic, value);
        }

        // Pre-33 path (and still invoked by some stacks).
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g,
                                            BluetoothGattCharacteristic characteristic) {
            handleNotification(characteristic, characteristic.getValue());
        }
    };

    private void subscribe(BluetoothGatt g, BluetoothGattCharacteristic hrCharacteristic) {
        int properties = hrCharacteristic.getProperties();
        boolean supportsNotify =
                (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        boolean supportsIndicate =
                (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

        Log.i(TAG, "0x2A37 properties: " + HrParser.describeProperties(properties));

        if (!supportsNotify && !supportsIndicate) {
            listener.onConnectionState("0x2A37 supports neither NOTIFY nor INDICATE: "
                    + HrParser.describeProperties(properties));
            return;
        }

        boolean enabled = g.setCharacteristicNotification(hrCharacteristic, true);
        Log.i(TAG, "setCharacteristicNotification -> " + enabled);
        if (!enabled) {
            listener.onConnectionState("setCharacteristicNotification() returned false");
            return;
        }

        BluetoothGattDescriptor cccd =
                hrCharacteristic.getDescriptor(UUID.fromString(HrParser.UUID_CCCD));
        if (cccd == null) {
            listener.onConnectionState(
                    "CCCD (0x2902) missing on 0x2A37 — cannot subscribe");
            return;
        }

        byte[] value = supportsNotify
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;

        boolean queued;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queued = g.writeDescriptor(cccd, value);
        } else {
            cccd.setValue(value);
            queued = g.writeDescriptor(cccd);
        }

        Log.i(TAG, "writeDescriptor(CCCD, " + (supportsNotify ? "NOTIFY" : "INDICATE")
                + ") -> " + queued);
        if (!queued) {
            listener.onConnectionState("CCCD write could not be queued");
        }
    }

    private void handleNotification(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (!HrParser.UUID_HR_MEASUREMENT.equalsIgnoreCase(
                characteristic.getUuid().toString())) {
            return;
        }

        int bpm = HrParser.parseBpm(value);
        long timestamp = System.currentTimeMillis();

        Log.i(TAG, "0x2A37 payload=" + HrParser.toHex(value)
                + " [" + HrParser.describeFlags(value) + "]");

        if (bpm <= 0) {
            Log.w(TAG, "unparsable heart rate payload");
            return;
        }

        Log.i(TAG, "HR=" + bpm + " timestamp=" + timestamp);
        listener.onHeartRate(bpm, timestamp);
    }

    private void dumpServices(BluetoothGatt g, int status) {
        List<BluetoothGattService> services = g.getServices();
        Log.i(TAG, "=== discoverServices status=" + status
                + " services=" + (services == null ? 0 : services.size()) + " ===");
        if (services == null) {
            return;
        }
        for (BluetoothGattService service : services) {
            Log.i(TAG, "service " + HrParser.prettyUuid(service.getUuid().toString()));
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                Log.i(TAG, "   char " + HrParser.prettyUuid(characteristic.getUuid().toString())
                        + "  props=" + HrParser.describeProperties(characteristic.getProperties()));
                for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
                    Log.i(TAG, "      desc " + HrParser.prettyUuid(descriptor.getUuid().toString()));
                }
            }
        }
        Log.i(TAG, "=== end of service map ===");
    }

    // ------------------------------------------------------------------ error naming

    private static String scanErrorName(int code) {
        switch (code) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                return "already started";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return "app registration failed";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                return "internal error";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                return "feature unsupported";
            case 5:
                return "out of hardware resources";
            case 6:
                return "scanning too frequently";
            default:
                return "unknown";
        }
    }

    /**
     * Names the handful of GATT status codes worth recognising on sight.
     * Only codes whose AOSP value is unambiguous are listed; anything else is
     * passed through rather than guessed at.
     */
    private static String gattStatusName(int status) {
        switch (status) {
            case 8:
                return "GATT_CONN_TIMEOUT (0x08)";
            case 19:
                return "GATT_CONN_TERMINATE_PEER_USER (0x13)";
            case 22:
                return "GATT_CONN_TERMINATE_LOCAL_HOST (0x16)";
            case 34:
                return "GATT_CONN_LMP_TIMEOUT (0x22)";
            case 62:
                return "GATT_CONN_FAIL_ESTABLISH (0x3E)";
            case 133:
                return "GATT_ERROR (0x85 — generic Android failure, usually a stale link; retry)";
            default:
                return "see BluetoothGatt docs";
        }
    }
}
