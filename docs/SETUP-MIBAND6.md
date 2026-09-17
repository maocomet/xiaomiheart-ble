# Mi Band 6 → Gadgetbridge 迁移与配置

本文记录手环从官方 App 迁移到 Gadgetbridge 的关键步骤，以及本链路依赖的两个设置。

---

## 1. 背景：为什么需要 auth_key

Mi Band 6 属于「需要服务端配对」的设备。它的 BLE 鉴权密钥（`auth_key`）在**官方 App 首次绑定手环时生成并存到厂商云端**，之后官方 App 每次连接都会用云端下发的这个密钥完成握手。

Gadgetbridge 拿不到云端下发的流程，所以必须**手工把 `auth_key` 填进去**，否则手环会连上但拒绝提供数据。

> ⚠️ 本项目最终采用的方案**不使用 auth_key**（见第 4 节），但 Gadgetbridge 本身仍然需要它才能建立连接。

---

## 2. 相关 App 的包名

| App | 包名 | 说明 |
|---|---|---|
| 小米运动健康（国内版 Mi Health） | `com.mi.health` | 国内版 |
| Mi Fitness（国际版，原 Xiaomi Wear） | `com.xiaomi.wearable` | 国际版 |
| Zepp Life（原 Mi Fit） | `com.xiaomi.hm.health` | Mi Band 7 及更早机型常用 |

国内版与国际版**不是同一个应用**，因存在同名组件而互相冲突，无法共存。

---

## 3. 迁移步骤

### 3.1 保持手环在官方 App 里处于已绑定状态

**不要先在官方 App 里解绑手环。** 解绑会让云端轮换 `auth_key`，已提取的密钥立即失效。

官方 App 可以和 Gadgetbridge **并存**，只要不动绑定关系。

### 3.2 提取 auth_key

本机已验证可用的方式是 `huami-token`（项目 `argrento/huami-token`）：

```bash
huami-token --method xiaomi --email <你的小米账号> --bt_keys
```

`--method xiaomi` 走的是小米运动健康（`sid=miothealth`）的接口，与本项目其余部分一致。

输出形如：

```
Device 0: 小米手环6
  MAC: XX:XX:XX:XX:XX:XX
  Key: 0x<32位十六进制>
```

> ⚠️ **不要把真实的 auth_key 写进任何文件、提交进 Git、或贴进聊天记录。**

### 3.3 在 Gadgetbridge 中配对

1. 在系统蓝牙设置里**取消**手环的配对（不影响官方 App 里的绑定关系）
2. Gadgetbridge 里扫描，长按找到的手环
3. 填入 **Auth Key**（带 `0x` 前缀）
4. **启用 New Auth Protocol** —— Mi Band 6 属于新协议设备，不启用会连不上，或手环提示「请先更新 App」
5. 连接

### 3.4 已知现象

- 手环提示「Update the app to the latest version first」通常就是协议选项不对（New Auth Protocol 该开没开）
- 手环**硬重置会改变蓝牙 MAC 并让 auth_key 失效**，需要重新提取
- 从官方 App 解绑同样会让密钥失效

---

## 4. 本链路依赖的两个设置

**是手环自己在对外提供心率服务**，所以必须在 Gadgetbridge 里打开这两个开关（否则第三方 App 连上手环也读不到数据）。

路径：Gadgetbridge → 你的 Mi Band 6 → 设备设置

| 设置项 | 对应键 | 值 |
|---|---|---|
| **3rd party realtime HR access** | `expose_hr_thirdparty` | 开 |
| **Visible while connected** | `bt_connected_advertisement` | 开 |

说明：

- 第一项在 Gadgetbridge 里的实现**只是往手环写一条厂商配置命令**（`COMMAND_ENBALE_HR_CONNECTION`），让手环开始对第三方暴露心率服务；Gadgetbridge 自身不转发任何数据
- 第二项让手环在已连接状态下仍然可被发现，否则第三方 App 扫描不到它
- 改完**需要重连一次手环**（配置命令只在连接时下发）

---

## 5. ⛔ 不要开启通用 BLE Intent API

Gadgetbridge 还有一个 **BLE Intent API**（把 BLE 通知以广播形式推给第三方 App）。本项目**不使用**它：

- 本方案不需要它（我们走的是 BLE central 直连）
- 有记录显示，开启后 Mi Band 6 出现过重连异常

它位于设备设置里，包含这两项，**保持关闭**：

- "Broadcast GATT notification Intents through BLE Intent API"
- "BLE API package"

---

## 6. 最终数据路径

```
Mi Band 6 ──BLE──▶ Android App (BLE central)
                    └─ 直接订阅标准 Heart Rate Service
                       service        0000180d-0000-1000-8000-00805f9b34fb
                       characteristic 00002a37-0000-1000-8000-00805f9b34fb
```

**Android App 直接作为 BLE central 读取，不经过 Gadgetbridge 转发。**

Gadgetbridge 在本方案里的作用仅限于：

1. 提供 auth_key 完成与手环的配对（这是它一开始就存在的连接）
2. 通过上面两个设置，命令手环对外暴露心率服务

手环支持多连接，因此 Gadgetbridge 与我们的 App 可以**同时连着**。

---

## 7. 心率数据的格式

标准 BLE Heart Rate Measurement（0x2A37）：

| 字节 | 含义 |
|---|---|
| `[0]` | flags；bit0 = 0 → uint8 值，bit0 = 1 → uint16 小端 |
| `[1]` 或 `[1..2]` | BPM |
| 之后 | 视 flags：能量消耗（2 字节）、RR 间期（每个 2 字节）|

Mi Band 6 实测发的是 **2 字节**：`[0] = 0x00`（flags），`[1]` = BPM。

解析实现见 `app/src/main/java/com/example/hrble/HrParser.java`，两种格式都兼容。

---

## 8. 相关文档

- [ARCHITECTURE.md](ARCHITECTURE.md) — 完整链路
- [RECOVERY-CHECKLIST.md](RECOVERY-CHECKLIST.md) — 恢复核查清单
- [SECURITY.md](SECURITY.md) — 什么不能进仓库
