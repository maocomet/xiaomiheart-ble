package com.example.hrble;

import java.util.Locale;

/**
 * BLE Heart Rate Measurement (0x2A37) payload parsing.
 *
 * Layout per the Bluetooth SIG spec:
 *   byte[0]  flags
 *              bit0  Heart Rate Value Format: 0 = uint8, 1 = uint16 little-endian
 *              bit1  Sensor Contact Status (2 bits)
 *              bit3  Energy Expended Present
 *              bit4  RR-Interval Present
 *   then     Heart Rate Value        (1 or 2 bytes, per bit0)
 *   then     Energy Expended         (2 bytes, if bit3)
 *   then     RR-Intervals            (2 bytes each, if bit4)
 *
 * The heart rate value is decoded at the correct offset for both formats rather
 * than assuming a fixed 2-byte packet.
 */
public final class HrParser {

    public static final String UUID_HR_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb";
    public static final String UUID_HR_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb";
    public static final String UUID_HR_CONTROL_POINT = "00002a39-0000-1000-8000-00805f9b34fb";
    public static final String UUID_CCCD = "00002902-0000-1000-8000-00805f9b34fb";

    private HrParser() {
    }

    /**
     * @return BPM, or -1 when the payload is not a usable measurement.
     */
    public static int parseBpm(byte[] data) {
        if (data == null || data.length < 2) {
            return -1;
        }
        int flags = data[0] & 0xFF;
        boolean isUint16 = (flags & 0x01) != 0;

        if (isUint16) {
            if (data.length < 3) {
                return -1;
            }
            return (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        }
        return data[1] & 0xFF;
    }

    /** Human-readable dump of the flags byte, for the discovery log. */
    public static String describeFlags(byte[] data) {
        if (data == null || data.length == 0) {
            return "empty";
        }
        int flags = data[0] & 0xFF;
        StringBuilder sb = new StringBuilder();
        sb.append("uint16=").append((flags & 0x01) != 0);
        sb.append(" contactSupported=").append((flags & 0x04) != 0);
        sb.append(" contactDetected=").append((flags & 0x02) != 0);
        sb.append(" energyExpended=").append((flags & 0x08) != 0);
        sb.append(" rrInterval=").append((flags & 0x10) != 0);
        return sb.toString();
    }

    public static String toHex(byte[] data) {
        if (data == null) {
            return "(null)";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    /** Friendly name for the UUIDs this PoC cares about; otherwise a truncated form. */
    public static String prettyUuid(String uuid) {
        if (uuid == null) {
            return "(null)";
        }
        String lower = uuid.toLowerCase(Locale.US);
        switch (lower) {
            case UUID_HR_SERVICE:
                return uuid + "  (Heart Rate service)";
            case UUID_HR_MEASUREMENT:
                return uuid + "  (Heart Rate Measurement)";
            case UUID_HR_CONTROL_POINT:
                return uuid + "  (Heart Rate Control Point)";
            case UUID_CCCD:
                return uuid + "  (Client Characteristic Configuration)";
            default:
                return uuid;
        }
    }

    /** Decode a BluetoothGattCharacteristic property bitmask into readable names. */
    public static String describeProperties(int properties) {
        StringBuilder sb = new StringBuilder();
        if ((properties & 0x02) != 0) sb.append("READ ");
        if ((properties & 0x08) != 0) sb.append("WRITE ");
        if ((properties & 0x04) != 0) sb.append("WRITE_NO_RESPONSE ");
        if ((properties & 0x10) != 0) sb.append("NOTIFY ");
        if ((properties & 0x20) != 0) sb.append("INDICATE ");
        String out = sb.toString().trim();
        return out.isEmpty() ? "(none)" : out;
    }
}
