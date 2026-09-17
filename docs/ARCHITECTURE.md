# 端到端架构

本文描述整条链路的组件划分、职责边界与数据流向。所有内容以**已经真实验证通过**的实现为准。

---

## 1. 全景图

```
┌──────────────────────────────────────────────────────────────────────────┐
│  手环侧                                                                   │
│                                                                          │
│   Mi Band 6                                                              │
│     │  BLE（标准 Heart Rate Service）                                     │
│     │  service        0000180d-0000-1000-8000-00805f9b34fb               │
│     │  characteristic 00002a37-0000-1000-8000-00805f9b34fb               │
│     │  payload        [flags][bpm]  （flags bit0 = 0 → uint8）             │
│     ▼                                                                    │
│   Android App  (com.example.hrble)                                       │
│     · 作为 BLE central 直连手环，自行订阅心率特征                            │
│     · logcat 输出 HR=<value> timestamp=<time>                             │
│     · 界面上传 URL / Bearer token（运行时配置，存 App 私有存储）               │
└──────────────────────────────────────────────────────────────────────────┘
                              │
                              │ HTTPS POST + Bearer
                              ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  VPS 侧（ssh fuxia-vps）                                                   │
│                                                                          │
│   Caddy  :443  ── handle /wearable/* ──▶  127.0.0.1:18005                │
│   （TLS 终结；对外唯一入口是 https://funf.maomao.im）                        │
│                              │                                           │
│                              ▼                                           │
│   wearable receiver   127.0.0.1:18005                                    │
│     · 只做采集与暂存：内存里保存「最新一条」心率                               │
│     · 无数据库、无历史、无 WebSocket                                        │
│                              │                                           │
│                              │ http://127.0.0.1:18005（localhost）         │
│                              ▼                                           │
│   funf-mcp            127.0.0.1:18004                                    │
│     · 对外工具 get_current_heart_rate（只读）                               │
│     · 通过 localhost 读取 receiver，不接触手机                               │
│     · 同一个进程还提供既有的 FUNF 设备控制工具                                 │
└──────────────────────────────────────────────────────────────────────────┘
                              │
                              │ https://funf.maomao.im/mcp  （唯一 MCP 入口）
                              ▼
                           AI 客户端
```

一句话版本：

> Mi Band 6 → Android App → `funf.maomao.im/wearable/*` → wearable-receiver:18005 → funf-mcp:18004 → `https://funf.maomao.im/mcp` → AI

---

## 2. 组件职责

| 组件 | 职责 | 明确**不做**的事 |
|---|---|---|
| **Mi Band 6** | 测量心率，通过标准 BLE 心率服务对外提供 | — |
| **Android App** | 扫描、连接、订阅 0x2A37、解析、上传 | 不做历史存储、不做本地持久化 |
| **Caddy** | TLS 终结、路径路由 | 不做鉴权（鉴权在应用层） |
| **wearable receiver** | 接收上报，保存最新一条，对外查询 | 不做历史、不做聚合、不做推送 |
| **funf-mcp** | 把心率包装成一个 MCP 工具 | **不直接访问手机**，不复制 receiver 的存储逻辑 |
| **AI 客户端** | 调用 MCP 工具 | — |

---

## 3. 数据边界（重要）

### 3.1 MCP 不接触手机

`funf-mcp` **只**通过 `http://127.0.0.1:18005` 读 receiver。它不知道也不关心手机的存在。

这样切分的好处：修改 Android 端的采集方式（重连策略、扫描逻辑）不会影响 MCP；反之亦然。

### 3.2 receiver 是唯一的数据持有者

心率数据只在 receiver 的进程内存里存在**一份**。funf-mcp 每次调用都重新去读，不做缓存、不复制。

代价：**receiver 重启会丢掉最新值**，`GET latest` 会返回 `no_data`，直到下一条心率到达。这是第一阶段有意为之（不做数据库）。

### 3.3 三种时间戳的分工

| 字段 | 产生方 | 时钟 | 用途 |
|---|---|---|---|
| `measured_at` | Android | 手机时钟 | 测量发生的时刻 |
| `sent_at` | Android | 手机时钟 | 请求发出的时刻 |
| `received_at` | receiver | **服务器时钟** | 请求到达的时刻 |

关键推论：

- `sent_at − measured_at`：两者同一时钟，**跨设备偏差自动抵消**，纯粹反映客户端排队延迟
- `received_at − sent_at`：网络传输 **+** 跨设备时钟偏差，两者混在一起
- 要分离后者的两个成分，用 `/wearable/time` 做校准（见 [TIME-CALIBRATION.md](TIME-CALIBRATION.md)）

`age_seconds` 与 `fresh` **锚定服务器的 `received_at`**，因此手机时钟偏移不会让过期数据被误判为新鲜。

---

## 4. 数据流时序

```
1. 手环测心率
2. BLE 通知推到 Android App（0x2A37，2 字节）
3. App 解析出 BPM，打 logcat，更新界面
4. App 组装 JSON + Bearer，POST 到 funf.maomao.im/wearable/heart-rate
   （异步、合并：上传中若来了新读数，只保留最新的那条，避免堆积）
5. Caddy 按 /wearable/* 转发到 127.0.0.1:18005
6. receiver 校验 token，把这条记为「最新」，返回 received_at
7. 之后任何时候，AI 调 get_current_heart_rate
8. funf-mcp 读 127.0.0.1:18005/wearable/heart-rate/latest
9. 包装成 {status, heart_rate, measured_at, sent_at, received_at, age_seconds, fresh, source}
10. 返回给 AI
```

---

## 5. 为什么是 BLE central 直连，而不是经过 Gadgetbridge

Gadgetbridge 提供过一个 **BLE Intent API**（把 BLE 通知以广播形式转发给第三方 App）。本项目**没有采用**它。

采用的方式是：Android App 自己作为 **BLE central** 直接连手环，读标准心率服务。

| | BLE Intent API（未采用） | BLE central 直连（采用） |
|---|---|---|
| 数据路径 | 手环 → Gadgetbridge → 广播 → App | 手环 → App |
| 依赖 Gadgetbridge 进程 | 是 | **否** |
| 需要额外开关 | BLE Intent API + package | 无 |
| 实测结果 | 未采用 | ✅ 已验证可用 |

两者都要求 Gadgetbridge 里开启下面两个设置，因为**是手环自己在对外提供心率服务**：

- **3rd party realtime HR access**
- **Visible while connected**

详见 [SETUP-MIBAND6.md](SETUP-MIBAND6.md)。

---

## 6. 当前范围边界

第一阶段明确**不做**：

- 历史心率 / 时间序列数据库
- WebSocket 或任何推送
- 除 `get_current_heart_rate` 之外的 wearable 工具
- 独立的 wearable MCP 域名（复用现有 `funf.maomao.im/mcp`）
- 在 MCP 侧做聚合、缓存或告警

---

## 7. 相关文档

| 文档 | 内容 |
|---|---|
| [SETUP-MIBAND6.md](SETUP-MIBAND6.md) | 手环从官方 App 迁移到 Gadgetbridge |
| [SERVER-MCP.md](SERVER-MCP.md) | VPS 端服务与 MCP 工具 |
| [TIME-CALIBRATION.md](TIME-CALIBRATION.md) | 时钟偏差的定位与修复 |
| [SECURITY.md](SECURITY.md) | 凭据管理与不得入库的内容 |
| [RECOVERY-CHECKLIST.md](RECOVERY-CHECKLIST.md) | 从零恢复的逐项清单 |
