# 安全与凭据管理

本文列出本项目中**任何情况下都不得进入 Git 仓库**的内容，以及各项凭据的存放位置。

---

## 1. 绝不进仓库的内容

| 内容 | 为什么 |
|---|---|
| **Mi Band 6 的 `auth_key`** | 它是手环的长期蓝牙鉴权密钥，泄露＝他人可以伪装成已配对设备连接你的手环 |
| **小米账号登录 cookie / session / token**（`ssecurity`、`serviceToken`、`cUserId` 等） | 等同于账号会话，可直接调用云端接口 |
| **wearable 的 Bearer token**（`WEARABLE_TOKEN`） | 拿到就能向你的 receiver 写入伪造心率、或读取你的数据 |
| **signing keystore 原件**（`.jks`）及密码 | 拿到就能签发"看起来是你发的" APK 更新 |
| **设备 MAC 地址** | 属于设备标识；文档示例一律用 `AA:BB:CC:DD:EE:FF` 之类的占位值 |
| **账号密码、手机号、邮箱** | 显而易见的 |

本仓库的另一处相关背景：早期曾从小米云端提取过设备凭据（`blt_get_beaconkey` 路线失败，后经 Mi Fitness 接口取得 `auth_key`）。**那些值从未、也不会进入本仓库。**

---

## 2. 各项凭据的存放位置

| 凭据 | 存放位置 | 备注 |
|---|---|---|
| Mi Band 6 `auth_key` | 只填在 Gadgetbridge 里 | 另需离线备份（手环硬重置或官方 App 解绑都会使其失效）|
| `WEARABLE_TOKEN` | VPS 的 `/etc/wearable-receiver.env`（`chmod 600`，root 独占） | 同时被 `funf-mcp.service` 通过 `EnvironmentFile` 复用 |
| Android App 的上传 URL + token | App **私有** `SharedPreferences` | **运行时**填写，不参与构建 |
| signing keystore + 密码 | 本地 `android-signing/`，另存于 GitHub Secrets | **必须异地备份** |
| funf-mcp 访问令牌 | 你自己的 MCP 客户端 | 服务端只存 SHA-256，无法反查 |

---

## 3. 为什么 Android 端 token 是运行时配置

本机没有 Android SDK，APK 全部由 GitHub Actions 构建。

因此**不可能**（也**不应该**）在编译期注入 token：

- CI 环境里没有你的 token，也不该有
- 一旦写进源码或构建配置，就会永久留在 Git 历史里
- 公开仓库的构建日志同样是公开的

所以 App 界面上提供两个输入框，保存到 App 私有存储。这样做同时也意味着：

- CI 构建出的 APK 是「干净」的，谁都能构建
- 卸载 App 会清空这些配置，需要重填

> ⚠️ `SharedPreferences` 是**应用私有但未加密**的。在未 root 的设备上这已足够；如需更强保护，可改用 `EncryptedSharedPreferences`（需引入 androidx 依赖）。

---

## 4. CI 中的密钥处理

`.github/workflows/android.yml` 的做法：

| 环节 | 做法 |
|---|---|
| 存放 | GitHub Secrets（4 个：`SIGNING_KEYSTORE_BASE64` / `_PASSWORD` / `_ALIAS` / `_PASSWORD`）|
| 传给构建 | **环境变量**，不做脚本内插值，因此不会出现在命令行或日志里 |
| 落盘 | 解码到 `$RUNNER_TEMP`，`chmod 600` |
| 清理 | **`if: always()` 删除**——即使构建失败也会删 |
| 校验 | `apksigner verify --print-certs` 比对指纹，不一致直接构建失败 |

**指纹本身不是秘密**（它嵌在每一个 APK 里），所以可以安全地硬编码在工作流中作为断言。

---

## 5. 签名为什么必须固定

Android 的**升级安装要求签名一致**。

早期未固定签名时，每个 CI runner 都是全新的，AGP 会现场生成一把新的 debug 密钥，于是**每次构建的签名都不同**，覆盖安装全部失败。两个连续构建的实测证书确实不同：

```
run 35196508083 -> ecca1f43…df28
run 35195170740 -> 8ddc6614…2b4b
```

现已改为固定密钥，期望指纹：

```
0B:5D:08:30:1A:2D:87:6F:56:C8:C2:87:DD:95:78:A6:88:81:E1:62:8A:9F:C6:E3:3D:74:0A:4C:95:54:D1:2D
```

**不要退回使用 runner 的临时 debug keystore。**

---

## 6. 受攻击面

| 暴露点 | 防护 |
|---|---|
| `https://funf.maomao.im/wearable/*` | Bearer token（常量时间比较）|
| `https://funf.maomao.im/mcp` | OAuth / Bearer（既有机制）|
| VPS 防火墙 | nftables，默认 drop，仅放行 22 / 80 / 443 |
| receiver 监听地址 | `127.0.0.1:18005`，不对外 |

> `/wearable/*` 目前是**公网可达**的，仅靠 token 保护。若想进一步收敛，可在 Caddy 层加 IP 白名单或限速——当前未实现。

---

## 7. 文档写作规范

在 `docs/` 下写任何内容时：

- 用占位值代替真实标识：MAC 用 `AA:BB:CC:DD:EE:FF`，token 只写存放位置不写值
- 引用凭据时只写「在哪个文件的哪个键」，**不写值**
- 示例时间戳、心率数值可以用真实量级，但不能是可反查的凭据

---

## 8. 相关文档

- [SETUP-MIBAND6.md](SETUP-MIBAND6.md) — auth_key 的获取与失效条件
- [SERVER-MCP.md](SERVER-MCP.md) — 服务端凭据布局
- [RECOVERY-CHECKLIST.md](RECOVERY-CHECKLIST.md) — 恢复流程
