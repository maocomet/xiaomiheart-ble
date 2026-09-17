# VPS 端：wearable receiver 与 MCP 工具

本文记录服务端的部署形态、接口契约，以及心率能力如何接入现有 MCP。

---

## 1. 登录与目录

```bash
ssh fuxia-vps          # 已配置好的别名，无需 IP / 用户名 / 私钥
```

| 项 | 值 |
|---|---|
| 系统 | Debian 13（root）|
| wearable receiver | `127.0.0.1:18005`（**仅本地监听**）|
| receiver 目录 | `/opt/wearable-receiver/`（源码 `app.py`，虚拟环境 `.venv/`）|
| receiver 服务 | `wearable-receiver.service` |
| receiver 配置 | `/etc/wearable-receiver.env`（`chmod 600`，root 独占）|
| funf-mcp | `127.0.0.1:18004`，源码 `/opt/funf-mcp/funf_mcp_server.py` |
| funf-mcp 服务 | `funf-mcp.service` |
| 反向代理 | Caddy，`/etc/caddy/Caddyfile` |
| 对外入口 | `https://funf.maomao.im` |

防火墙（nftables）**只放行 22 / 80 / 443**，所以 receiver 不可能直接对外暴露——它必须经 Caddy 反代。这同时也强制了 TLS。

---

## 2. 对外暴露方式

Caddyfile 里的这一段是新增的：

```caddyfile
funf.maomao.im {
    encode zstd gzip

    handle /mcp* {
        reverse_proxy 127.0.0.1:18004
    }

    handle /.well-known/oauth-protected-resource* {
        reverse_proxy 127.0.0.1:18004
    }

    handle /wearable/* {                  # ← 本项目新增
        reverse_proxy 127.0.0.1:18005
    }

    handle {                              # ← catch-all，必须留在最后
        reverse_proxy 127.0.0.1:3870 { ... }
    }
}
```

所以对外地址是：

```
https://funf.maomao.im/wearable/heart-rate
https://funf.maomao.im/wearable/heart-rate/latest
https://funf.maomao.im/wearable/time
```

> ⚠️ Caddy 的 `handle` 块**互斥且按书写顺序匹配**。新增的 `/wearable/*` 必须放在 catch-all `handle { }` **之前**，否则会被 catch-all 吃掉，转发到错误的端口。

### 修改 Caddyfile 的流程

```bash
cp -a /etc/caddy/Caddyfile /etc/caddy/Caddyfile.pre-<变更名>-$(date +%Y%m%dT%H%M%SZ)
# ...编辑...
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile   # 必须通过
systemctl reload caddy                                             # 平滑重载，不断连接
```

现有备份：`/etc/caddy/Caddyfile.pre-wearable-20260917T000749Z`

---

## 3. 接口契约

**所有接口都需要 Bearer 鉴权。**

```
Authorization: Bearer <WEARABLE_TOKEN>
```

token 存放在 `/etc/wearable-receiver.env`。**本文件不记录它的值。**

### 3.1 `POST /wearable/heart-rate`

请求体：

```json
{
  "heart_rate": 86,
  "measured_at": "2026-09-17T08:35:47.426Z",
  "sent_at":    "2026-09-17T08:35:47.433Z",
  "source": "mi_band_6"
}
```

- 三个时间字段都是 ISO-8601；`measured_at` 必填
- `sent_at` **可选**（早期版本的上报没有这个字段，服务端仍然接受）
- `heart_rate` 限定 1–300，越界返回 422
- `measured_at` 非法返回 400

响应：

```json
{ "status": "ok", "received_at": "2026-09-17T08:35:48.549Z" }
```

### 3.2 `GET /wearable/heart-rate/latest`

响应：

```json
{
  "heart_rate": 86,
  "measured_at": "2026-09-17T08:35:47.426Z",
  "sent_at":    "2026-09-17T08:35:47.433Z",
  "received_at": "2026-09-17T08:35:48.549Z",
  "age_seconds": 2,
  "fresh": true,
  "source": "mi_band_6"
}
```

| 字段 | 说明 |
|---|---|
| `heart_rate` | BPM |
| `measured_at` | 手机侧测量时刻（**手机时钟**）|
| `sent_at` | 手机侧发送时刻（**手机时钟**），在上报未携带时为 `null` |
| `received_at` | 服务器收到时刻（**服务器时钟**）|
| `age_seconds` | `now(服务器) − received_at`，即**数据到达服务器后的年龄** |
| `fresh` | `age_seconds <= 30` |
| `source` | 上报方标识，当前固定为 `mi_band_6` |

**没有任何数据时返回 404**，`detail` 为 `no heart rate received yet`——不会返回伪造数值。

> `fresh` 的语义：**用服务器的 `received_at` 计算**，与手机时钟无关。这样即使手机时钟有偏移，也不会把过期数据误判为新鲜。窗口当前是 30 秒，由 `/etc/wearable-receiver.env` 里的 `FRESH_WINDOW_SECONDS` 控制。

### 3.3 `GET /wearable/time`

```json
{ "server_time": "2026-09-17T07:46:14.703Z" }
```

用途是让客户端做时钟校准，见 [TIME-CALIBRATION.md](TIME-CALIBRATION.md)。

---

## 4. 服务端状态是内存态

receiver **不做持久化**：最新一条心率保存在进程内存里。

推论：

- `systemctl restart wearable-receiver` 之后，`GET latest` 会返回 404，直到下一条心率到达（通常几秒内，只要手环在测量）
- 运行 `uvicorn` 时**必须是单 worker**，否则每个 worker 会各存一份互不相干的状态
- 内存占用实测约 30 MB（`MemoryMax=128M`）

---

## 5. MCP 集成

### 5.1 没有新建 MCP 域名

心率能力**接入现有的 MCP 入口**：

```
https://funf.maomao.im/mcp
```

没有为 wearable 单开域名、证书、OAuth 或服务发现。理由：心率只是"AI 可调用的一个新能力"，不值得再多维护一套接入层。

### 5.2 新增工具 `get_current_heart_rate`

只读工具，实现在 `/opt/funf-mcp/funf_mcp_server.py` 的 `WearableReceiverClient`。

它**只**通过 `http://127.0.0.1:18005` 读 receiver：

- 不接触手机
- 不复制 receiver 的存储逻辑
- 不缓存

### 5.3 状态语义

工具**任何情况下都返回结构化结果，不抛异常**（这一点与控制类工具的风格不同——那些必须响亮地失败，而这个只是读取）：

| `status` | 含义 |
|---|---|
| `ok` | 取到新鲜读数；含全部字段 |
| `stale` | 有读数但 `fresh=false`；**仍然返回最后一次心率**，同时带 `message` 明确说明"这不是当前心率" |
| `no_data` | receiver 正常运行但还没有收到过任何数据（对应 404）|
| `unavailable` | 读不到 receiver：连接被拒、超时、token 缺失、非 200、响应不是 JSON |

**没有数据时不会伪造数值。**

### 5.4 配置缺失不会拖垮 funf-mcp

`wearable` 的 token 是**非致命读取**的：

```python
WEARABLE_TOKEN = os.environ.get("WEARABLE_TOKEN", "").strip()
```

而**不是**文件里其他配置用的 `required()`（那会在启动时直接 `RuntimeError`）。

理由：心率工具是可选的只读附加项，不该因为它缺配置就让**控制设备**的 MCP 服务起不来。缺 token 时工具返回 `status: unavailable`，其余工具不受影响。

### 5.5 token 的来源：复用，不复制

`funf-mcp.service` 里加了一行：

```ini
EnvironmentFile=/etc/funf-mcp.env
EnvironmentFile=/etc/wearable-receiver.env    # ← 新增
```

systemd 以 root 读取 `EnvironmentFile` 后再降权到 `User=funf-mcp`，所以那个 `chmod 600` 的文件对服务用户本身仍不可读。

这样做的目的是**让 token 只有一处事实来源**，而不是把它复制进第二个文件。

> ⚠️ 这一行不能删，否则工具会返回 `unavailable`。

### 5.6 验证情况

已完成的验证：

- `tools/list` 能看到 `get_current_heart_rate`（连同既有的 6 个工具，共 7 个）
- 四条路径（`ok` / `stale` / `no_data` / `unavailable`）全部通过
- `ok` 路径取到的是手环的真实心率
- 跑完全部异常路径后服务仍然健康、工具注册表未变
- 既有工具与对外路由无回归

验证脚本：`funf-mcp-work/test_heart_rate_tool.py`（独立文件，未并入测试框架——原项目本来就没有测试目录）。

---

## 6. 常用运维命令

```bash
# 服务状态
systemctl is-active wearable-receiver funf-mcp caddy

# 重启 receiver（会丢掉内存里的最新值）
systemctl restart wearable-receiver

# 看日志
journalctl -u wearable-receiver -n 30 --no-pager
journalctl -u funf-mcp -n 30 --no-pager

# 资源占用
systemctl show wearable-receiver -p MemoryCurrent -p MemoryMax --value
```

备份文件：

| 文件 | 说明 |
|---|---|
| `/etc/caddy/Caddyfile.pre-wearable-20260917T000749Z` | Caddy 改动前 |
| `/opt/funf-mcp/funf_mcp_server.py.pre-wearable-20260917T013437Z` | funf-mcp 改动前 |
| `/etc/systemd/system/funf-mcp.service.pre-wearable-20260917T013437Z` | 服务单元改动前 |

---

## 7. 相关文档

- [ARCHITECTURE.md](ARCHITECTURE.md) — 完整链路
- [TIME-CALIBRATION.md](TIME-CALIBRATION.md) — 时钟校准
- [SECURITY.md](SECURITY.md) — token 管理
