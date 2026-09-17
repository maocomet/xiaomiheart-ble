# 从零恢复：逐项核查清单

按顺序往下走，**每一项都要确认通过再进入下一项**。任何一项不过，后面的都不可能通。

---

## 0. 开始前需要准备好的东西

| 需要 | 在哪 |
|---|---|
| Mi Band 6 的 `auth_key` | Gadgetbridge 里已填；丢失需重新提取（见 [SETUP-MIBAND6.md](SETUP-MIBAND6.md)）|
| VPS 的 `WEARABLE_TOKEN` | `ssh fuxia-vps "grep ^WEARABLE_TOKEN= /etc/wearable-receiver.env"` |
| signing keystore + 密码 | `android-signing/xiaomiheart-ble/`（异地备份）|
| 一个能连 MCP 的 AI 客户端 | 已配置 `https://funf.maomao.im/mcp` |

---

## A. 手环侧

### ☐ A1. Gadgetbridge 能连上 Mi Band 6

打开 Gadgetbridge，手环显示为已连接、能同步步数。

**不过怎么办**：确认 auth_key 填对（带 `0x` 前缀、小写 x），且**已启用 New Auth Protocol**。

---

### ☐ A2. New Auth Protocol 正常

Mi Band 6 属于新协议设备。若手环提示 "Update the app to the latest version first"，通常就是这个选项不对。

---

### ☐ A3. `3rd party realtime HR access` 已开

Gadgetbridge → 你的手环 → 设备设置 → **3rd party realtime HR access** → 开

配置键：`expose_hr_thirdparty`

---

### ☐ A4. `Visible while connected` 已开

同一页面 → **Visible while connected** → 开

配置键：`bt_connected_advertisement`

> ⚠️ 这两个设置**改了之后要重连一次手环**（配置命令只在建立连接时下发）。

---

### ☐ A5. 通用 BLE Intent API 保持关闭

同页面下这两项保持**关**：

- "Broadcast GATT notification Intents through BLE Intent API"
- "BLE API package"

本方案不需要它，且有记录显示开启后 Mi Band 6 会重连异常。

---

## B. Android 侧

### ☐ B1. App 能扫描并显示 BPM

装 `com.example.hrble`，授权蓝牙权限，等扫描列表，点像手环的那一项。

**通过标准**：界面上 BPM 数字开始跳动，`notifications received` 计数递增。

**不过怎么办**：列表里没有手环 → 回查 A3/A4 是否真的开了并重连过。

---

### ☐ B2. logcat 能打出心率

```bash
adb logcat -s HRBLE
```

应看到：

```
I/HRBLE: service 0000180d-… (Heart Rate service)
I/HRBLE:    char 00002a37-… (Heart Rate Measurement)  props=NOTIFY
I/HRBLE: CCCD write status=0
I/HRBLE: HR=72 timestamp=1789618106711
```

> 本机 adb 用雷电自带的：`D:\leidian\LDPlayer9\adb.exe`

---

### ☐ B3. Test upload 返回 HTTP 200

App 底部：填 URL + token → Save → **Test upload**

**通过标准**：状态行显示 `upload: OK http=200`。

**不过怎么办**：`401` → token 填错；`404` → URL 路径写错。

---

## C. 服务端

### ☐ C1. 两个服务都在跑

```bash
ssh fuxia-vps 'systemctl is-active wearable-receiver funf-mcp caddy'
```

三项都应是 `active`。

---

### ☐ C2. receiver 能返回真实数据

```powershell
$T = (ssh fuxia-vps "grep ^WEARABLE_TOKEN= /etc/wearable-receiver.env | cut -d= -f2-").Trim()
Invoke-RestMethod -Uri "https://funf.maomao.im/wearable/heart-rate/latest" -Headers @{ Authorization = "Bearer $T" }
```

应返回：

```json
{
  "heart_rate": 86,
  "measured_at": "...",
  "sent_at": "...",
  "received_at": "...",
  "age_seconds": 2,
  "fresh": true,
  "source": "mi_band_6"
}
```

**通过标准**：`fresh` 为 `true`，且 `heart_rate` 与手环上显示的一致。

**不过怎么办**：

| 症状 | 原因 |
|---|---|
| 404 `no heart rate received yet` | receiver 刚重启（内存态清空），或 App 没在上报 → 回查 B3 |
| 401 | token 不对 |
| 全对但 `fresh: false` | 手环没在测量 → 在手上测一次心率 |

---

### ☐ C3. 时钟偏差正常（可选但强烈建议）

```powershell
$T = (ssh fuxia-vps "grep ^WEARABLE_TOKEN= /etc/wearable-receiver.env | cut -d= -f2-").Trim()
Invoke-RestMethod -Uri "https://funf.maomao.im/wearable/time" -Headers @{ Authorization = "Bearer $T" }
```

**通过标准**：把上一步返回的 `sent_at` 与 `received_at` 相减，应在**秒级**。

若是几十秒，说明手机时钟漂了 → 见 [TIME-CALIBRATION.md](TIME-CALIBRATION.md)。

---

## D. MCP

### ☐ D1. `get_current_heart_rate` 出现在工具列表

用你的 MCP 客户端拉一次 `tools/list`。

**通过标准**：列表中同时有 `get_current_heart_rate` 和既有的 6 个工具（`ping`、`get_device_status`、`list_presets`、`set_control`、`apply_preset`、`stop_all`），共 **7 个**。

> 服务端只存访问令牌的 SHA-256、无法反查，所以这一步必须由你用自己的客户端完成。

---

### ☐ D2. AI 实际调用工具成功

让 AI 调一次 `get_current_heart_rate`。

**通过标准**：返回 `status: "ok"`，`heart_rate` 是手环的真实读数。

**四种可能的状态**：

| `status` | 含义 | 下一步 |
|---|---|---|
| `ok` | 正常 | ✅ 完成 |
| `stale` | 有数据但已过期 | 手环没在测量，测一次即可；数据本身没错 |
| `no_data` | receiver 从没收到过数据 | 回查 B3、C2 |
| `unavailable` | 读不到 receiver | 回查 C1；若报 token 未配置，检查 `funf-mcp.service` 里的 `EnvironmentFile` 那行还在不在 |

---

## E. 端到端

### ☐ E1. 手环上测心率，AI 侧立刻能读到

在手上触发一次测量，然后让 AI 调 `get_current_heart_rate`。

**通过标准**：`fresh: true`，读数与手环一致，`age_seconds` 个位数。

到这里整条链路就通了。

---

## 故障速查

| 症状 | 先查 |
|---|---|
| 手环连不上 Gadgetbridge | A1 / A2（auth_key、New Auth Protocol）|
| App 扫不到手环 | A4（Visible while connected）|
| App 连上但收不到心率 | A3（3rd party realtime HR access）+ 是否重连过手环 |
| 上传 401 | App 里的 token |
| 上传 404 | App 里的 URL 路径 |
| `latest` 返回 404 | receiver 刚重启 / App 没上报 |
| `fresh: false` 但数据在 | 手环没在测量 |
| `age_seconds` 很大 | 同上 |
| `sent_at` 与 `received_at` 差几十秒 | 手机时钟 → [TIME-CALIBRATION.md](TIME-CALIBRATION.md) |
| MCP 里没有这个工具 | `funf-mcp` 是否重启过、代码是否部署 |
| 工具返回 `unavailable` | `funf-mcp.service` 的 `EnvironmentFile` 行、receiver 是否在跑 |
| APK 装不上（签名冲突）| 见 [SECURITY.md](SECURITY.md) 第 5 节 |

---

## 相关文档

- [ARCHITECTURE.md](ARCHITECTURE.md) — 完整链路
- [SETUP-MIBAND6.md](SETUP-MIBAND6.md) — 手环与 Gadgetbridge
- [SERVER-MCP.md](SERVER-MCP.md) — 服务端接口与 MCP 工具
- [TIME-CALIBRATION.md](TIME-CALIBRATION.md) — 时钟问题
- [SECURITY.md](SECURITY.md) — 凭据与签名
