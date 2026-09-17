# 时钟偏差的定位与修复

本文记录一个曾长期误导排查方向的故障：**约 22 秒的固定时间差**。

---

## 1. 现象

上报到 VPS 的每条心率，`received_at − sent_at` 都稳定在 **约 22 秒**，跨度只有几百毫秒：

| # | `sent_at − measured_at` | `received_at − sent_at` |
|---|---|---|
| 1 | 0.957 s | 29.238 s |
| 2 | 1.167 s | 22.392 s |
| 3 | 0.374 s | 26.027 s |
| 4 | 0.727 s | 22.303 s |
| 5 | 0.004 s | 22.081 s |

第一列（客户端侧）很小，说明 App 从收到心率到发出请求只用了几毫秒到 1 秒——**上传实现不是瓶颈**。

问题全在第二列。

---

## 2. 为什么"稳定"不能作为结论

最初的推理是「差值这么稳定，肯定不是网络延迟，那就是时钟偏差」。

**这个推理是错的，或者说不完整。** 一个稳定的排队延迟同样能造成稳定的差值。仅凭这两个时间戳，无法区分：

- 稳定的网络/排队延迟
- 跨设备时钟偏差

`sent_at` 字段就是为了切开这一点而加的：

| 差值 | 含什么 |
|---|---|
| `sent_at − measured_at` | 纯客户端延迟（同一台手机的时钟，跨设备偏差**自动抵消**）|
| `received_at − sent_at` | 网络传输 **+** 跨设备时钟偏差 |

上表第一列只有毫秒级，于是**排除了客户端排队**这一可能。

---

## 3. 决定性证据

分离剩余两个成分，靠的是**校准**和一个**不受时钟偏差影响的量：RTT**。

### 3.1 RTT 是干净的

```
RTT = t1 − t0        （t0/t1 都由同一台机器的时钟读取）
```

往返时延只用本机时钟测量，**完全不含跨设备偏差**。实测本机到 VPS 的 RTT 只有 **239 ms**——网络根本不慢。

### 3.2 服务器时钟是准的

在 VPS 上用三个独立信源交叉验证：

```
VPS date -u              2026-09-17T07:56:37.198Z
google.com   Date 头     Thu, 17 Sep 2026 07:56:37 GMT
cloudflare.com  Date 头  Thu, 17 Sep 2026 07:56:37 GMT
microsoft.com   Date 头  Thu, 17 Sep 2026 07:56:37 GMT
```

三者一致，服务器时钟可信。（`timedatectl` 也报告 `System clock synchronized: yes`。）

### 3.3 结论：是设备时钟偏差

RTT 只有 239 ms（干净的测量），而偏移量高达 18,931 ms，误差带仅 **±119 ms**——偏差是误差带的 159 倍，**不可能是噪声**。

再用一个完全不经过服务端的方法复核：本机时钟直接对 Google 的 `Date` 响应头，得出本机**落后约 21.2 秒**。两种独立方法互相印证。

**那 22 秒是设备系统时钟偏差，不是网络。**

---

## 4. 校准公式

```
t0              = 客户端发请求前的本地时间
                        → GET /wearable/time → {"server_time": "..."}
t1              = 客户端收到响应后的本地时间

RTT             = t1 − t0
clock_offset    ≈ server_time − (t0 + t1) / 2
uncertainty     = RTT / 2
```

`clock_offset` 为正表示**服务器时钟读数更大**，即**客户端时钟偏慢**。

**误差上限是 RTT/2**，所以只要 RTT 小，这个结论就是决定性的。反过来说，如果 RTT 本身很大（比如 25 秒 → 误差带 ±12.5 秒），单次校准的精度可能不足以区分——这时应取 **RTT 最小的那次**，误差带最窄。

App 里的 **Calibrate** 按钮实现的就是上面这个计算。

---

## 5. vivo / OriginOS 的特殊之处

vivo 的系统界面把「自动设置时间」和「自动设置时区」**合并成了一个开关**。

但 **Android 底层仍然是两个独立的设置**：

| Android 设置键 | 含义 |
|---|---|
| `auto_time` | 是否自动校准时钟 |
| `auto_time_zone` | 是否自动切换时区 |

所以即使 UI 不允许分开，仍然可以通过 ADB 单独设置。

### 5.1 只读检查

```bash
adb shell settings get global auto_time
adb shell settings get global auto_time_zone
adb shell getprop persist.sys.timezone
```

实测结果（修复前）：

```
auto_time         0          ← 关闭，这就是漂移的根源
auto_time_zone    0
persist.sys.timezone  America/Los_Angeles
```

### 5.2 修复

只需要改 `auto_time` 这一个键（另外两个本来就是要的值，不动）：

```bash
adb shell settings put global auto_time 1
```

最终状态：

```
auto_time          = 1
auto_time_zone     = 0
persist.sys.timezone = America/Los_Angeles
```

> ⚠️ **不要在 vivo 设置界面里拨那个合并开关。** 既然是合并的，拨它可能把 `auto_time_zone` 也置成 1，那就又开始自动改时区了。要调整就用 ADB 单独改 `auto_time`。

---

## 6. 修复效果

修复前（用夹逼法测量）：手机比权威时间**慢约 26 秒**。

修复后：

```
measured_at : 08:35:47.426Z
sent_at     : 08:35:47.433Z   →  sent − measured = 0.007 s   （毫秒级）
received_at : 08:35:48.549Z   →  received − sent  = 1.116 s   （约 1 秒）
```

`received_at − sent_at` **从 22 秒降到 1.1 秒**。

这同时反过来确认了第 3 节的结论：网络传输本身就只有约 1 秒，之前那 22 秒全部是时钟偏差。

---

## 7. 为什么 `fresh` 不受影响

`age_seconds` 与 `fresh` 是用**服务器的 `received_at`** 计算的，不碰手机的 `measured_at`。

所以即使手机时钟再次漂移，也不会出现"过期数据被误判为新鲜"。

**但反过来要注意**：`measured_at` 来自手机时钟，如果手机时钟不准，这个字段在真实时间轴上是偏移的。做时间序列分析前必须先确认手机校时正常。

---

## 8. 排查类似问题的顺序

1. 看 `sent_at − measured_at`：大 → 客户端排队问题；小 → 继续
2. 测 RTT（本机时钟即可）：大 → 网络问题；小 → 继续
3. 做时钟校准，看 `clock_offset` 是否远大于 `uncertainty`
4. 用独立信源（外部 HTTP `Date` 头）验证服务器时钟是否可信
5. 两边都排除后，再怀疑应用层

**不要用"差值稳定"直接推断成因。**

---

## 9. 相关文档

- [SERVER-MCP.md](SERVER-MCP.md) — `/wearable/time` 接口
- [ARCHITECTURE.md](ARCHITECTURE.md) — 三种时间戳的分工
