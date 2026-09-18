# GB HR BLE PoC

Minimal Android app that acts as a **BLE central**, connects **directly to a Mi Band 6**,
subscribes to the standard Heart Rate Measurement characteristic and logs:

```
HR=<value> timestamp=<time>
```

It does **not** go through Gadgetbridge at runtime, does not use the BLE Intent API, and
performs **no Huami authentication handshake**.

## The full chain

This app is one link in a longer path:

```
Mi Band 6
  → Android app (this repo, BLE central)
  → https://funf.maomao.im/wearable/heart-rate    HTTPS POST + Bearer token
  → wearable receiver     127.0.0.1:18005         keeps the latest reading in memory
  → funf-mcp              127.0.0.1:18004         read-only, over localhost
  → https://funf.maomao.im/mcp                    the only MCP entry point
  → AI
```

Only the Android link lives in this repository; everything to the right of it is
documented under [`docs/`](docs/). The rest of this file covers the app itself.

## Documentation

| Document | Covers |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | End-to-end architecture and component boundaries |
| [docs/SETUP-MIBAND6.md](docs/SETUP-MIBAND6.md) | Migrating the band from the official app to Gadgetbridge |
| [docs/SERVER-MCP.md](docs/SERVER-MCP.md) | VPS services, HTTP API, and the `get_current_heart_rate` MCP tool |
| [docs/TIME-CALIBRATION.md](docs/TIME-CALIBRATION.md) | Diagnosing and fixing the ~22 s device clock offset |
| [docs/SECURITY.md](docs/SECURITY.md) | What must never be committed; signing and credentials |
| [docs/RECOVERY-CHECKLIST.md](docs/RECOVERY-CHECKLIST.md) | Step-by-step checklist to rebuild the chain from scratch |

## The question this stage answers

Gadgetbridge's per-device *"3rd party realtime HR access"* setting only writes a vendor
config command to the band:

```java
// Gadgetbridge HuamiSupport.setExposeHRThirdParty()
writeToConfiguration(builder, HuamiService.COMMAND_ENBALE_HR_CONNECTION);
```

That is supposed to make the **band itself** serve heart rate over standard BLE, so another
app can connect concurrently. Community reports say no authentication is needed — this PoC
verifies that. **Deliberately no `auth_key` is used anywhere.** If notifications never
arrive, that assumption is what failed, and the service map dumped to logcat is the
evidence.

## Gadgetbridge prerequisites

On the Mi Band 6's device settings, with Gadgetbridge connected at least once:

- **3rd party realtime HR access** → ON
- **Visible while connected** → ON *(otherwise the band may not be discoverable)*

Do **not** enable the BLE Intent API; this app does not use it.

Heart rate also has to actually be *measured* for anything to arrive — start an activity on
the band, open its heart-rate screen, or turn on whole-day HR measurement.

## Permissions

| Android | Permissions |
|---|---|
| 12+ (API 31+) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` |
| 6–11 (API 23–30) | `ACCESS_FINE_LOCATION` (+ legacy `BLUETOOTH`, `BLUETOOTH_ADMIN`) |

`BLUETOOTH_SCAN` carries `neverForLocation` — the app makes no location claim, and the
location permission is kept off Android 12+ entirely.

> If the band never shows up in the scan, the documented fallback is to drop
> `neverForLocation` and request `ACCESS_FINE_LOCATION` on 12+ as well.

## How a device is chosen

No MAC address is hardcoded. The scan runs **unfiltered** on purpose: a service-UUID
`ScanFilter` would hide the band entirely if it does not advertise `0x180D`. Candidates are
ranked client-side instead:

1. advertises the Heart Rate service `0000180d-…`
2. name looks like a band (`mi band`, `mi smart band`, `xiaomi`, `amazfit`, `zepp`, …)
3. any other named device
4. unnamed devices

Tap a row to connect.

## Build

JDK 17, Gradle 8.10.2 (wrapper included), AGP 8.5.2, compileSdk 34, minSdk 26.

```bash
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

CI builds it on every push and uploads the APK as the **`app-debug-apk`** artifact.

## Run

1. Install the APK and open it. Grant the Bluetooth permissions.
2. It scans for 20 s automatically. Wait for the list, then tap the band.
3. Watch the screen and logcat:

```bash
adb logcat -s HRBLE
```

Expected on success:

```
I/HRBLE: onConnectionStateChange status=0 newState=2
I/HRBLE: service 0000180d-0000-1000-8000-00805f9b34fb  (Heart Rate service)
I/HRBLE:    char 00002a37-0000-1000-8000-00805f9b34fb  (Heart Rate Measurement)  props=NOTIFY
I/HRBLE: CCCD write status=0
I/HRBLE: 0x2A37 payload=0048 [uint16=false contactSupported=true contactDetected=true ...]
I/HRBLE: HR=72 timestamp=1789618106711
```

## What the screen shows

Scan state · candidate list · connected device name · connection status · latest BPM ·
update time · number of notifications received.

The notification counter is the quickest way to tell "subscribed but silent" apart from
"never subscribed".

## Starting continuous heart-rate measurement from the app

The band does not measure continuously on its own — notifications only trickle in during a
manual measurement, a workout, or periodic monitoring. **Start realtime HR** asks the band
to start measuring continuously.

It writes three plain bytes to the **standard** Heart Rate Control Point `0x2A39`, mirroring
what Gadgetbridge does for the Mi Band 6:

| Action | Payload | Source |
|---|---|---|
| Start continuous | `15 01 01` | `HuamiSupport.java:592` |
| Stop continuous | `15 01 00` | `HuamiSupport.java:593` |

`0x15` is the Mi Band command prefix; `0x01` is `COMMAND_SET__HR_CONTINUOUS`
(`MiBandService.java:186`). Gadgetbridge applies **no encryption or session layer** to this
write — it is an ordinary GATT write to a characteristic inside `0x180D`, the same service
this app already subscribes to.

### What the source could not settle

Proven from source: the command format is plaintext and lives on the standard service.

**Not** provable from source: whether the band's *firmware* accepts that write on a link
that never completed the Huami authentication handshake. Gadgetbridge only ever sends it
after `performInitialized()`, so its source cannot settle the question. That is what this
experiment measured.

### Result: it works, and no `auth_key` is needed

Measured on the Mi Band 6 (vivo V2162A, Android 14):

- `0x2A39 present: READ WRITE` — the control point exists inside `0x180D` and is writable.
- The write is **accepted on a plain, unauthenticated link**: `write callback start
  status=0`, and the band starts measuring. **No `auth_key` appears anywhere in this app.**
- One `15 01 01` produces the first usable BPM in **~1.1 s**.
- Baseline with no command is **0 notifications**; after the command, readings flow.
- `15 01 00` stops it: **0 notifications in the following 40 s**.

A rejection would have said so directly, so this route never needs guessing:

| `onCharacteristicWrite` status | Reading |
|---|---|
| `0` (`GATT_SUCCESS`) | Accepted — then the notification rate decides whether it acted |
| `3` / `5` / `133` | Firmware rejected it; this route would need the Huami session |

### One start does not last — but re-sending buys time, not rate

The band measures for about **27 s** after a single start command and then stops on its own
(last reading 27.4 s after the write, then 69 s of silence). That is precisely why
Gadgetbridge re-sends the command every second. Re-sending does **not** make it measure
faster:

| Mode | Start writes | Notifications |
|---|---|---|
| Single start | 1 | ~0.35/s for ~27 s, then silent |
| **Repeat start every 1s** | 30 in 30 s | ~0.30/s, **still running** |

The raw rate is **~0.35–0.40/s** — one reading every 2.5–3 s, and irregular (gaps ranged
from 1.3 s to 5.3 s). That is noticeably slower than the ~1/s a first reading of
Gadgetbridge's Live Activity screen suggests.

For the "measure on demand for 30–60 s" design this is convenient: **the band's own ~27 s
timeout already lands inside the desired window**, so a single start may need no timer of
our own.

### Running it

1. Connect to the band and check the status line reports `0x2A39 present: READ WRITE`. If it
   says `NOT present`, this route is closed and the experiment ends there.
2. Tap **Start realtime HR** and watch `rate:` — arrivals per second over a trailing 30 s
   window. It climbs to roughly `0.35/s`, then falls back to zero about 30 s later.
3. The status line reports the write status, then `first BPM <N>ms after start`.
4. Tap **Stop realtime HR** and confirm the rate falls away immediately.

**Repeat start every 1s** holds the measurement open indefinitely, at the cost of a write
every second. It is off by default so a single write can be judged on its own first.

This is a diagnostic screen, not the product: nothing triggers it from the server yet.

## Uploading readings to a server

Each reading can be forwarded by HTTPS POST:

```json
{
  "heart_rate": 86,
  "measured_at": "2026-09-17T07:08:41.789Z",
  "sent_at":    "2026-09-17T07:08:41.802Z",
  "source": "mi_band_6"
}
```

No MAC, no auth key, nothing else device-identifying is sent.

`sent_at` is taken at the moment the request is actually issued, so it includes any time
the reading spent queued behind an earlier upload. It exists to separate two effects that
`measured_at` and the server's `received_at` alone cannot tell apart:

| Difference | Contains |
|---|---|
| `sent_at - measured_at` | client-side delay only — both ends are the phone's clock, so any cross-device skew cancels out |
| `received_at - sent_at` | network transit **plus** cross-device clock skew |

A large first difference means the app is queueing (slow link, coalescing); a large second
difference with a near-zero first one points at clock skew instead. The server treats
`sent_at` as optional, so builds predating it keep working.

**Configuration is runtime, not compile-time.** Enter the upload URL and bearer token on
the app screen and tap **Save**; they go into app-private `SharedPreferences`. They are
deliberately *not* injected at build time — this project is built by CI, and no secret
belongs in the repository or in a public build log. The token field is masked.

Two invariants, both enforced in `HeartRateUploader`:

- **An upload can never disturb BLE reading.** `submit()` only stores the newest reading
  and returns; the network call runs on a background single-thread executor and every
  exception is swallowed there.
- **A slow or dead network cannot build a backlog.** If an upload is still in flight when a
  newer reading arrives, the older one is dropped — only the newest is kept.

**Test upload** sends a fixed value of 80 so the setup can be checked without waiting for a
reading. The status line shows the last HTTP code plus running ok/fail counts.

> `SharedPreferences` is app-private but not encrypted. That is fine for a PoC on a
> non-rooted device; use `EncryptedSharedPreferences` if that is not good enough.

## Clock calibration

`sent_at - measured_at` and `received_at - sent_at` split the total delay in two, but they
still cannot say whether a large second value is a slow network or a phone clock that is
offset from the server's. **Calibrate** settles it with a bounded error.

The app brackets a `GET /wearable/time` call with its own timestamps:

```
t0 = now (before request)
      → GET /wearable/time  →  {"server_time": "..."}
t1 = now (after response)

rtt          = t1 - t0
clock_offset = server_time - (t0 + t1) / 2
uncertainty  = rtt / 2
```

`clock_offset` with a small `rtt` is decisive: if the offset is ~22 s while the uncertainty
is under a second, the phone's clock is genuinely behind. If the offset is near zero, the
delay is all network.

**This only means anything if the server clock is accurate** — check `timedatectl` reports
`System clock synchronized: yes`. The endpoint path is derived from the configured upload
URL (`.../wearable/heart-rate` → `.../wearable/time`) rather than configured separately.

## Troubleshooting

| Symptom | Meaning |
|---|---|
| No candidates at all | BLE off, permissions denied, or `neverForLocation` filtering — see the fallback above |
| `status=133` on connect | The notorious generic Android GATT error; usually a stale link. Tap Scan and retry |
| Connected, `0x180D` not in the service map | "3rd party realtime HR access" was not applied — reconnect the band in Gadgetbridge so the command is pushed |
| Connected, `0x180D` found, CCCD write OK, **0 notifications** | The band is not measuring — use **Start realtime HR**, or start an activity / enable whole-day HR on the band |
| `0x2A39 NOT present in 0x180D` | The firmware does not expose the control point; continuous measurement cannot be started from here |
| Start write returns `0` but **no** readings follow | The write reached the GATT server but the firmware did not act on it — try **Repeat start every 1s** |
| Readings stop ~27 s after a single start | Expected: the band's own measurement timeout. Use **Repeat start every 1s**, or just start again |
| Rate is ~0.35/s rather than ~1/s | Expected on this band — see the measured table above |
| Notifications stop after a while | The band went idle, or Gadgetbridge reconnected and took the link |

In every failure case the app dumps the full service/characteristic map to logcat. It does
not attempt authentication or any other workaround — that is a deliberate scope boundary
for this stage.

## Layout

```
app/src/main/java/com/example/hrble/
  MainActivity.java        UI + runtime permission flow + upload configuration + realtime HR controls
  BleHrClient.java         scan / connect / discoverServices / subscribe / parse dispatch / 0x2A39 writes
  HrParser.java            UUID constants, 0x2A37 flag parsing, pretty-printing
  HeartRateUploader.java   coalescing fire-and-forget HTTPS POST
```

Package is `com.example.hrble`, deliberately different from the broadcast-based PoC
(`com.example.hrpoc`) so both APKs can be installed side by side while comparing approaches.
