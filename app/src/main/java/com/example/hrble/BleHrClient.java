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
import android.bluetooth.BluetoothStatusCodes;
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

import java.util.ArrayDeque;
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

        /**
         * Realtime-HR control messages, kept separate from onConnectionState so a
         * control-point result never overwrites the connection status line.
         */
        void onRealtimeHr(String state);

        /**
         * Structured link state: true once the heart-rate subscription is actually
         * live, false whenever the link drops or the subscription fails.
         *
         * Separate from {@link #onConnectionState} because that one is prose meant for
         * a status line. The foreground service drives its reconnect backoff from this,
         * and parsing display text to decide whether to retry would be a trap.
         */
        void onLinkState(boolean up, String detail);
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

    // ------------------------------------------------------------ realtime HR control

    /**
     * 0x2A39 Heart Rate Control Point, taken from the discovered 0x180D service.
     * Null until discoverServices() succeeds, or when the band does not expose it.
     */
    private BluetoothGattCharacteristic hrControlPoint;

    /**
     * Huami HR control-point payloads, mirrored from Gadgetbridge:
     *   service/devices/huami/HuamiSupport.java:590-593
     *   devices/miband/MiBandService.java:186-187  (COMMAND_SET__HR_CONTINUOUS = 0x01)
     *
     * These are plain GATT writes — Gadgetbridge applies no encryption or session
     * layer to them. Whether the *firmware* accepts them on a link that never
     * completed the Huami auth handshake is exactly what this PoC measures.
     */
    private static final byte[] HR_START_CONTINUOUS = {0x15, 0x01, 0x01};
    private static final byte[] HR_STOP_CONTINUOUS = {0x15, 0x01, 0x00};

    /** Names the write currently in flight so its callback can report which one it was. */
    private volatile String pendingHrWrite;
    private volatile boolean pendingHrWriteIsStart;

    /**
     * Set when a start command is queued, consumed by the first usable BPM.
     * Measures how long the band takes to converge after being told to measure.
     */
    private volatile long realtimeStartSentAt;

    /** Arrival times of recent notifications, for reporting the rate actually delivered. */
    private final ArrayDeque<Long> recentNotifyAt = new ArrayDeque<>();
    private static final long RATE_WINDOW_MS = 30_000L;

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
        String label = (candidate.name == null || candidate.name.isEmpty())
                ? candidate.address : candidate.name;
        connectDevice(candidate.device, label);
    }

    /**
     * Connects to a band whose address was stored earlier, without scanning.
     *
     * The foreground service uses this when reconnecting after a drop: there is no UI
     * to pick from, and re-running a 20 s scan on every retry would be both slower and
     * harder on the battery. The address is whatever the user picked in the scan list —
     * nothing is compiled in.
     */
    public void connectToAddress(String address) {
        stopScan();

        if (address == null || address.trim().isEmpty()) {
            linkDown("no saved device address");
            return;
        }

        BluetoothManager manager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            linkDown("Bluetooth unavailable");
            return;
        }

        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address.trim());
        } catch (IllegalArgumentException e) {
            linkDown("not a valid device address: " + address);
            return;
        }
        connectDevice(device, address.trim());
    }

    private void connectDevice(BluetoothDevice device, String label) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            linkDown("missing connect permission");
            return;
        }

        closeGatt();

        listener.onConnectionState("connecting to " + label + " ...");
        Log.i(TAG, "connectGatt " + label);

        gatt = device.connectGatt(context, false, gattCallback,
                BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) {
            linkDown("connectGatt() returned null");
        }
    }

    public void disconnect() {
        closeGatt();
        linkDown("disconnected by user");
    }

    /**
     * The two halves are emitted together everywhere the link genuinely comes up or
     * goes down, so a caller can never see one without the other.
     */
    private void linkUp(String detail) {
        listener.onConnectionState(detail);
        listener.onLinkState(true, detail);
    }

    private void linkDown(String detail) {
        listener.onConnectionState(detail);
        listener.onLinkState(false, detail);
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
        // The characteristic belongs to the GATT handle that just went away; keeping
        // the reference would let a later write be queued against a dead connection.
        hrControlPoint = null;
        pendingHrWrite = null;
        pendingHrWriteIsStart = false;
        realtimeStartSentAt = 0L;
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
                linkDown(state);
                closeGatt();
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onConnectionState("connected — discovering services...");
                boolean started = g.discoverServices();
                if (!started) {
                    linkDown("discoverServices() returned false");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                linkDown("disconnected");
                closeGatt();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            // Always dump the full map — this is the evidence collected when things
            // do not work out, and it costs nothing when they do.
            dumpServices(g, status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                linkDown("service discovery failed: status=" + status);
                return;
            }

            BluetoothGattService hrService =
                    g.getService(UUID.fromString(HrParser.UUID_HR_SERVICE));
            if (hrService == null) {
                linkDown("Heart Rate service 0x180D NOT found — full service map in logcat");
                return;
            }

            BluetoothGattCharacteristic hrCharacteristic =
                    hrService.getCharacteristic(UUID.fromString(HrParser.UUID_HR_MEASUREMENT));
            if (hrCharacteristic == null) {
                linkDown("0x2A37 not present inside 0x180D");
                return;
            }

            reportHrControlPoint(hrService);
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
                // The subscription is live: this, not the connection, is what the
                // service treats as "up".
                linkUp("CCCD written — waiting for heart rate notifications");
            } else {
                linkDown("CCCD write failed: status=" + status
                        + " (" + gattStatusName(status) + ")");
            }
        }

        /**
         * The verdict on a realtime-HR command. A GATT_SUCCESS here means the band's
         * GATT server accepted the write; it does not yet mean the firmware acted on
         * it — the notification rate is what settles that.
         */
        @Override
        public void onCharacteristicWrite(BluetoothGatt g,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            if (!HrParser.UUID_HR_CONTROL_POINT.equalsIgnoreCase(
                    characteristic.getUuid().toString())) {
                return;
            }

            String label = pendingHrWrite == null ? "?" : pendingHrWrite;
            boolean isStart = pendingHrWriteIsStart;
            pendingHrWrite = null;
            pendingHrWriteIsStart = false;

            Log.i(TAG, "0x2A39 write callback " + label + " status=" + status
                    + " (" + gattStatusName(status) + ")");
            listener.onRealtimeHr(label + " write -> status=" + status
                    + " (" + gattStatusName(status) + ")");

            if (isStart) {
                realtimeStartSentAt = (status == BluetoothGatt.GATT_SUCCESS)
                        ? System.currentTimeMillis() : 0L;
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
            linkDown("0x2A37 supports neither NOTIFY nor INDICATE: "
                    + HrParser.describeProperties(properties));
            return;
        }

        boolean enabled = g.setCharacteristicNotification(hrCharacteristic, true);
        Log.i(TAG, "setCharacteristicNotification -> " + enabled);
        if (!enabled) {
            linkDown("setCharacteristicNotification() returned false");
            return;
        }

        BluetoothGattDescriptor cccd =
                hrCharacteristic.getDescriptor(UUID.fromString(HrParser.UUID_CCCD));
        if (cccd == null) {
            linkDown("CCCD (0x2902) missing on 0x2A37 — cannot subscribe");
            return;
        }

        byte[] value = supportsNotify
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;

        boolean queued;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Careful: the API 33+ overload returns a BluetoothStatusCodes int,
            // unlike the deprecated boolean-returning single-argument form.
            int status = g.writeDescriptor(cccd, value);
            queued = (status == BluetoothStatusCodes.SUCCESS);
            Log.i(TAG, "writeDescriptor(CCCD, " + (supportsNotify ? "NOTIFY" : "INDICATE")
                    + ", len=" + value.length + ") -> status=" + status);
        } else {
            cccd.setValue(value);
            queued = g.writeDescriptor(cccd);
            Log.i(TAG, "writeDescriptor(CCCD, " + (supportsNotify ? "NOTIFY" : "INDICATE")
                    + ") -> " + queued);
        }
        if (!queued) {
            // No callback is coming if the write never left, so the link is definitely
            // not up — say so rather than waiting for a notification that will not come.
            linkDown("CCCD write could not be queued");
        }
    }

    // ---------------------------------------------------------------- realtime HR control

    /**
     * Records whether 0x2A39 exists and is writable, and says so out loud. The full
     * service map is dumped separately; this one line is the answer to "is the
     * realtime-HR route even available on this firmware".
     */
    private void reportHrControlPoint(BluetoothGattService hrService) {
        hrControlPoint = hrService.getCharacteristic(
                UUID.fromString(HrParser.UUID_HR_CONTROL_POINT));

        if (hrControlPoint == null) {
            Log.w(TAG, "0x2A39 NOT present in 0x180D — realtime HR control unavailable");
            listener.onRealtimeHr("0x2A39 NOT present in 0x180D — no realtime HR control");
            return;
        }

        int properties = hrControlPoint.getProperties();
        boolean writable = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                || (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;

        String detail = "0x2A39 present: " + HrParser.describeProperties(properties)
                + (writable ? "" : "  — NOT WRITABLE");
        Log.i(TAG, detail);
        listener.onRealtimeHr(detail);
    }

    /** Huami "start continuous heart-rate measurement": 15 01 01 to 0x2A39. */
    public void startRealtimeHr() {
        writeHrControlPoint(HR_START_CONTINUOUS, "start", true);
    }

    /** Huami "stop continuous heart-rate measurement": 15 01 00 to 0x2A39. */
    public void stopRealtimeHr() {
        writeHrControlPoint(HR_STOP_CONTINUOUS, "stop", false);
    }

    private void writeHrControlPoint(byte[] payload, String label, boolean isStart) {
        if (gatt == null) {
            listener.onRealtimeHr(label + ": no GATT connection");
            return;
        }
        if (hrControlPoint == null) {
            listener.onRealtimeHr(label + ": 0x2A39 unavailable — connect to the band first");
            return;
        }

        int properties = hrControlPoint.getProperties();
        boolean withResponse = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
        boolean withoutResponse =
                (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;

        if (!withResponse && !withoutResponse) {
            listener.onRealtimeHr(label + ": 0x2A39 advertises no write property");
            return;
        }

        // Prefer write-with-response: its callback status is the evidence we are after.
        // Gadgetbridge never calls setWriteType, so DEFAULT is also what it gets.
        int writeType = withResponse
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        String typeName = withResponse ? "DEFAULT" : "NO_RESPONSE";
        String hex = HrParser.toHex(payload);

        pendingHrWrite = label;
        pendingHrWriteIsStart = isStart;
        if (isStart) {
            // Cleared until the write is confirmed, so a rejected write cannot be
            // mistaken for a slow one when the latency is finally reported.
            realtimeStartSentAt = 0L;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ passes the value explicitly and returns a BluetoothStatusCodes int
            // rather than the deprecated boolean-returning single-argument form.
            int status = gatt.writeCharacteristic(hrControlPoint, payload, writeType);
            Log.i(TAG, "0x2A39 write " + label + " payload=" + hex
                    + " type=" + typeName + " -> status=" + status);
            listener.onRealtimeHr(label + " " + hex + " queued, status=" + status
                    + (status == BluetoothStatusCodes.SUCCESS ? "" : "  (REJECTED)"));
        } else {
            hrControlPoint.setWriteType(writeType);
            hrControlPoint.setValue(payload);
            boolean queued = gatt.writeCharacteristic(hrControlPoint);
            Log.i(TAG, "0x2A39 write " + label + " payload=" + hex
                    + " type=" + typeName + " -> " + queued);
            listener.onRealtimeHr(label + " " + hex + " queued=" + queued);
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

        logNotifyRate(timestamp);
        reportStartLatency(timestamp);

        Log.i(TAG, "HR=" + bpm + " timestamp=" + timestamp);
        listener.onHeartRate(bpm, timestamp);
    }

    /**
     * Rolling estimate of how often 0x2A37 actually fires. Moving this from
     * "occasional" to about once per second is the whole point of the start command,
     * so it is the number that decides whether the write did anything.
     *
     * Log only — the UI derives its own rate from the readings it already receives.
     */
    private void logNotifyRate(long now) {
        recentNotifyAt.addLast(now);
        while (!recentNotifyAt.isEmpty() && now - recentNotifyAt.peekFirst() > RATE_WINDOW_MS) {
            recentNotifyAt.removeFirst();
        }

        int count = recentNotifyAt.size();
        long span = count < 2 ? 0 : now - recentNotifyAt.peekFirst();
        if (span <= 0) {
            return;
        }
        Log.i(TAG, "notify rate: " + count + " in " + span + "ms = "
                + String.format(Locale.US, "%.2f", count * 1000.0 / span) + "/s");
    }

    /** Time from the accepted start command to the first usable BPM; reported once. */
    private void reportStartLatency(long now) {
        long sentAt = realtimeStartSentAt;
        if (sentAt == 0L) {
            return;
        }
        realtimeStartSentAt = 0L;

        long delta = now - sentAt;
        Log.i(TAG, "start -> first BPM latency: " + delta + "ms");
        listener.onRealtimeHr("first BPM " + delta + "ms after start");
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
