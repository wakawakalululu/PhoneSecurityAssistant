# 春夏秋手机安全助手

**把 Android 设备变成一台可通过 SSH 访问的小主机。**

春夏秋手机安全助手 在应用内运行完整的 `sshd` 服务器（基于 Dropbear），并提供 `rsync`、
`sftp` / `scp` 服务。启动服务后，同一局域网内的电脑即可用标准 SSH 客户端
登录设备、传输文件、执行备份。

本仓库是**独立发布的二次开发版本（modified fork）**，在原有能力之上新增了：

- **提权模式**：应用权限（普通）/ Root 权限（su）/ ADB 权限（无线调试）
- **内置终端**：可直接在应用内以不同权限执行命令，实时查看输出
- **内网穿透**：托管官方 `frpc`，把本机 sshd 端口暴露到公网
- **Tailscale 组网**：托管官方 `tailscaled`，加入自己的 tailnet
- **哪吒监控上报**：托管官方 `nezha-agent`，向哪吒面板上报设备状态
- **WebDAV 文件访问**：通过 Android SAF 在系统文件选择器中只读浏览 HTTPS WebDAV 内容；不创建系统挂载路径

| 项 | 值 |
| --- | --- |
| 应用名 | 春夏秋手机安全助手 |
| 包名（applicationId） | `com.tencentx.pinyin` |
| 版本 | 1.13.2（versionCode 23） |
| 最低系统 | Android 8.0（API 26，`minSdk 26`） |
| 目标系统 | `targetSdk 36`（compileSdk 36） |
| 界面语言 | 简体中文（默认）、English、Deutsch、Français、தமிழ் |
| 许可证 | MIT |

> 英文文档：[**README.en.md**](README.en.md)

## 目录

- [项目简介](#项目简介)
- [功能特性](#功能特性)
- [安装](#安装)
- [快速上手](#快速上手)
- [提权模式详解](#提权模式详解)
- [内置终端使用](#内置终端使用)
- [WebDAV 只读文件访问](#webdav-只读文件访问)
- [文件同步使用](#文件同步使用)
- [frpc 内网穿透配置教程](#frpc-内网穿透配置教程)
- [Tailscale 组网教程](#tailscale-组网教程)
- [哪吒监控上报配置教程](#哪吒监控上报配置教程)
- [常见问题 FAQ](#常见问题-faq)
- [安全说明与免责声明](#安全说明与免责声明)
- [开源协议与致谢](#开源协议与致谢)
- [构建说明](#构建说明)

## 项目简介

春夏秋手机安全助手 提供一个带有 shell 访问、rsync 和 scp/sftp 服务的 `sshd` 服务器。
服务组件由以下项目提供：

- [Dropbear](https://github.com/mkj/dropbear)（本版本：2026.94）
- [rsync](https://rsync.samba.org/)（本版本：3.4.4）
- [OpenSSH](https://github.com/openssh/openssh-portable) 的 `sftp-server`（OpenSSH 10.4p1）

> **注意**：应用**不会**启动 rsync daemon。客户端通过 SSH 调用远端 `rsync`
> 完成同步（即常见的 `rsync -e ssh` 用法）。

rsync-over-SSH 由远端客户端发起。只读 WebDAV SAF Provider 与 App 内 rclone 文件同步
均已实现（MVP：WebDAV 只读浏览/流式读取；rclone 单向 copy、定时调度、重启恢复、完成通知），
但尚未通过 Gradle 编译或真机验证（CI 当前在 assemble 步骤失败），不应视为已发布能力。

服务端地址、凭据、权限模式、自启与远程工具启停均可在 App 的「设置」和对应管理页面配置。frpc、Tailscale 与哪吒的运行配置由 App 自动生成，无需手动编辑 TOML/YAML 文件。

## 功能特性

| 分类 | 功能 | 说明 |
| --- | --- | --- |
| SSH 服务 | sshd（Dropbear 2026.94） | 可配置端口（默认 2222）、监听接口、额外命令行选项、环境变量、家目录、登录 Shell |
| 文件传输 | rsync 3.4.4 / sftp / scp | 由 OpenSSH 10.4p1 的 `sftp-server` 提供 sftp/scp 支持；无 rsync 守护进程 |
| WebDAV | SAF 文档提供器 | 在系统文件选择器中只读浏览/打开 HTTPS WebDAV 文件；不创建本地文件系统挂载路径 |
| 登录认证 | 主用户名 + 主密码 | SHA-512 哈希后保存 |
| | 一次性密码 | 允许登录时在应用界面上显示一个只可使用一次的密码 |
| | 公钥登录 | 导入 `authorized_keys`（支持从文件或 https URL 导入、清空） |
| 提权模式 | 应用权限（普通） | 一切以应用自身身份运行（默认） |
| | Root 权限（su） | SSH 会话（shell/scp/sftp/rsync）以 root 身份运行，终端同样可选 |
| | ADB 权限（无线调试） | 与设备自身的无线调试配对，以 adb `shell` 用户身份执行，无需 Root |
| 内置终端 | 命令执行 | 选择 应用/Root/ADB 权限运行命令；实时显示 stdout/stderr；历史、停止、清空 |
| 内网穿透 | frpc（frp v0.65.0） | 自动生成 `frpc.toml`，把本机 sshd 端口映射到公网 frps 服务器 |
| 组网 | Tailscale（tailscaled/tailscale 1.90.6） | 用户态模式（免 Root）或 TUN 模式（需要 Root）；支持授权密钥、hostname、连接/状态/断开 |
| 监控上报 | 哪吒监控 agent（v2.3.5） | 生成 `nezha-agent.yml`，向哪吒面板上报设备状态；可禁止面板远程执行命令；有上报/日志面板 |
| 文件同步 | rclone（v1.69.3） | 单向 copy（SFTP/WebDAV ↔ 本地目录），任务 CRUD、进度/日志/取消；**MVP，待编译/真机验证**；不做双向同步与删除传播 |
| 远程与监控 | 前台服务 | frpc / Tailscale / 哪吒 agent 的独立启停、状态与实时日志；可选择随 sshd 服务一起启动 |
| 后台运行 | 前台服务 | 切到后台后服务继续运行（显示常驻通知） |
| | 开机自启 / 打开应用自启 | 可在设置中开关 |
| | 外部 Intent 启停 | 需在设置中显式允许，见「快速上手」 |
| | 快捷设置磁贴 | 从系统快捷设置面板一键启停 sshd |
| 兼容性 | Android TV | 提供 Leanback 启动器入口与 TV 按钮布局 |
| | 多语言界面 | 简体中文（默认）、English、Deutsch、Français、தமிழ் |

## 安装

- 方式一：使用本仓库的构建产物
  - GitHub Actions 工作流（`.github/workflows/build-apk.yml`）会构建
    debug / release APK；release 文件名为 `PhoneSecurityAssistant-<版本>.apk`，debug 文件名为
    `PhoneSecurityAssistant-<版本>-debug.apk`；
  - 也可以从本仓库的 Releases 页面下载（如果维护者发布了 APK）。
- 方式二：自行编译，见 [构建说明](#构建说明)。

安装注意：

- 包名为 `com.tencentx.pinyin`；**debug 版本的包名为
  `com.tencentx.pinyin.debug`**，可与 release 版本共存。
- **与旧版应用互不兼容**（包名与签名均不同），
  不能直接覆盖安装升级；如需切换，请先卸载旧版本（会丢失其配置）。
- 首次启动时应用会请求「所有文件访问权限」（`MANAGE_EXTERNAL_STORAGE`）。
  要完整使用 rsync/sftp 访问共享存储，必须手动授予；不授予则只能访问
  应用自身的文件目录。

### 应用权限

- `POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`
  —— 作为前台服务运行 sshd 所必需（保证切到后台后继续运行）。
- `RECEIVE_BOOT_COMPLETED` —— 开机自启（可在设置中关闭）。
- `MANAGE_EXTERNAL_STORAGE` —— 让 rsync/sftp 能访问共享存储中的文件。
- `INTERNET` —— 网络访问。

## 快速上手

1. **启动服务**：打开应用 → 点击「开始」。通知栏会显示监听地址
   （例如 `*:2222`），主界面会列出设备当前的所有 IP 地址。
2. **配置端口与自启**：「设置 → Dropbear SSH → 端口号」可修改监听端口，
   允许范围为 **1024–32768**（默认 2222）。「设置 → 自启」中可开启
   「开机时启动服务」「打开应用时启动服务」。
3. **配置登录方式**（可组合使用）：
   - 主用户名 + 主密码：「设置 → SSH 登录 → 主用户名 / 主密码」；
   - 一次性密码：开启后，尝试登录时密码会显示在应用界面上，单次有效；
   - 公钥登录：「设置 → 已授权密钥」中从文件或 https URL 导入
     `authorized_keys`。
4. **在电脑上登录**：

   ```bash
   ssh -p 2222 <用户名>@<设备IP>
   ```

   设备 IP 可在应用主界面查看（例如 `192.168.1.100`）。
5. **文件传输示例**：

   ```bash
   # 使用 rsync 备份（经由 SSH）
   rsync -avP -e "ssh -p 2222" ./photos/ user@192.168.1.100:/sdcard/backup/photos/

   # 使用 scp 上传单个文件
   scp -P 2222 ./file.txt user@192.168.1.100:/sdcard/

   # 使用 sftp 交互式传输
   sftp -P 2222 user@192.168.1.100
   ```
6. **快捷设置磁贴**：从系统快捷设置面板中添加 春夏秋手机安全助手 磁贴，一键启停服务。
7. **通过 Intent 启停**（可选，需先在设置中允许外部 Intent）：

   ```bash
   adb shell am start -a com.tencentx.pinyin.fg.START
   adb shell am start -a com.tencentx.pinyin.fg.STOP
   ```

## 提权模式详解

在「设置 → 权限与提权」中可分别设置 **SSH 登录的提权模式**与
**终端权限模式**：

### 1. 应用权限（普通）

- 所有进程以 **应用自身 UID** 运行，属于普通应用权限。
- 可访问应用私有目录；在授予「所有文件访问权限」后，可通过共享存储访问
  `/sdcard` 等位置。
- 无需 Root，最安全的模式（默认）。

### 2. Root 权限（su）

- 原理：把 sshd 的**登录 Shell 设置为设备的 `su` 二进制**。Dropbear 的
  每个会话（shell、`scp`、`sftp`、`rsync`）都由 `su` 拉起，因此都以
  **root 身份**运行。
- `su` 路径可自动探测，也可以在设置中手动指定（`su 路径`）；探测位置包括
  `/system/bin/su`、`/system/xbin/su`、`/sbin/su`、`/su/bin/su`、
  `/debug_ramdisk/su`、`/data/adb/ksu/bin/su`、`/data/adb/ap/bin/su`、
  `/data/adb/magisk/su`，以及 `PATH` 中的 `su`。
- 兼容 Magisk、KernelSU、APatch 及 SuperSU 风格的实现。
  首次使用时，Root 管理器会在设备上弹出授权请求，请及时确认。
- 「设置 → 权限与提权 → 检测 Root 权限」可用于检测 `su` 是否可用。
- 内置终端同样可以选择 Root 权限执行命令。
- ⚠️ Root 模式下 SSH 会话拥有完整系统权限：请务必使用强密码或公钥登录，
  并只在可信网络中开放服务端口。

### 3. ADB 权限（无线调试）

- 原理：通过 **libadb** 与本机自身的「无线调试」配对并连接，之后命令以
  adb `shell` 用户（uid 2000）身份执行，**无需 Root**。
- 配对步骤：
  1. 打开「设置 → 关于手机」，连续点击「版本号」以启用**开发者选项**；
  2. 进入「开发者选项 → 无线调试」，打开无线调试；
  3. 点击「**使用配对码配对设备**」，记下屏幕上显示的 **配对码** 与
     **配对端口**（以及 IP，通常为 `192.168.x.x:xxxxx`）；
  4. 回到 春夏秋手机安全助手 →「设置 → ADB 无线调试」，点击
     「**使用配对码配对**」，填入主机地址、配对端口与 6 位配对码，确认配对；
  5. 配对成功后点击「连接/状态」进行连接；连接端口默认 `127.0.0.1:5555`，
     也可填写无线调试页面显示的 IP 与端口。
- 注意事项：
  - 配对/连接发生在设备本机回环或局域网内，操作过程请保持网络可用；
  - **设备重启（或关闭再打开无线调试）后需要重新配对**，且配对端口
    每次可能变化；
  - ADB `shell` 权限可以执行不少普通应用无法执行的命令
    （如 `pm`、`am`、`getprop`、部分系统目录读写等），但**并不是 root**；
  - 若提示「认证失败，请重新配对」，请删除配对信息后重新进行配对。

## 内置终端使用

- 入口：主界面菜单/TV 按钮中的「**终端**」。
- 功能：
  - 点击模式按钮在 **应用 / Root / ADB** 权限之间切换（顶部会显示当前模式）；
  - 输入命令后点击「运行」，stdout 与 stderr 会**实时**显示在控制台中；
  - 「停止」可终止正在运行的命令；「清空」清空输出；
  - 使用 ↑ / ↓ 按钮可从历史命令中取回之前输入的命令；
  - 长时间运行的任务会继续在后台运行，可在「远程与监控」屏幕查看状态；
  - 命令结束时显示退出码（ADB 模式的流没有退出码，按成功处理）。
- 示例命令：

  ```bash
  id                                     # 查看当前命令的身份
  getprop ro.product.model               # 查看设备型号（ADB 权限更有效）
  ip addr                                # 查看网络接口
  ps -A | grep dropbear                  # 查看 sshd 进程（Root 权限更完整）
  ls -l /sdcard                          # 查看共享存储
  frpc -c /data/data/com.tencentx.pinyin/files/tools/frpc.toml
  tailscale status
  nezha-agent -c /data/data/com.tencentx.pinyin/files/tools/nezha-agent.yml
  ```
- 说明：选择 Root 权限但未找到 `su` 时会给出提示；选择 ADB 权限但尚未
  配对连接时，会提示先到「远程与监控」完成 ADB 配对与连接。

## WebDAV 只读文件访问

1. 打开「设置 → WebDAV（只读文件访问）」，填写 HTTPS 服务器地址及可选用户名/密码。
2. 在系统文件选择器中选择「春夏秋手机安全助手 WebDAV」根目录，即可浏览目录并打开文件。

当前通过 Android SAF 提供只读 `content://` 文档访问；不是 `/mnt/...` 挂载，命令行程序不能把它当成本地路径使用。当前版本不支持上传、删除、重命名或离线缓存。

## 文件同步使用

通过内置 rclone 在远端（SFTP / WebDAV）与本地目录之间执行**单向复制**（copy）。

1. 安装 rclone：菜单 →「文件同步」→「安装」，选择「在线下载」或「从文件导入」；也可在「设置 → 文件同步 rclone」中填写路径或下载。导入/下载的二进制需 Root 权限执行，内置（jniLibs）的二进制可直接执行。
2. 新建任务：点击「新建任务」，填写任务名称、远端类型（SFTP / WebDAV）、主机、端口、用户名、密码、远端路径、本地路径（如 `/sdcard/Photos`）、方向（下载 ← / 上传 →）。
3. 启动任务：在任务卡片点击「开始」，任务在前台服务中运行，关闭应用后仍会保持运行；可在通知和日志中查看进度。
4. 查看日志：点击「日志」查看 rclone 实时输出；点击「编辑」修改任务；点击「删除」删除任务。

> **范围说明**：当前为 MVP，仅支持单向 copy，**不删除目标多余文件**，**不做双向同步**；这些留作后续迭代。已支持**定时调度**（可配置间隔、仅 Wi-Fi、仅充电）、**设备重启后自动恢复调度**、**同步完成通知**（成功/失败）。远端凭据与任务配置保存在应用私有目录（已排除云备份）。rclone 密码通过 `rclone obscure` 加密后写入临时配置文件。

## frpc 内网穿透配置教程

用途：在没有公网 IP 的情况下，把本机的 sshd 端口通过公网服务器暴露出去，
随时随地 SSH 回家。

### 1. 准备公网 frps 服务器

- 需要一台有公网 IP 的服务器（云主机/VPS）。
- 在服务器上安装 **frp v0.65.0** 的 `frps`。
- 服务器端 `frps.toml` 示例：

  ```toml
  bindPort = 7000

  auth.method = "token"
  auth.token = "your-strong-token"
  ```

- 放行防火墙端口：
  - `7000/tcp`（frp 控制端口，与客户端 `服务器端口` 一致）；
  - 你将要使用的**远程端口**（例如 `6000/tcp`）。
- 启动服务端：`./frps -c frps.toml`（或配置为 systemd 服务）。

### 2. 在应用中配置 frpc

「设置 → 内网穿透 frpc」：

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| 服务器地址 | frps 的 IP 或域名。**建议直接填 IP**（见 FAQ） | 空 |
| 服务器端口 | frps 的 `bindPort` | `7000` |
| 令牌 | 与 frps 的 `auth.token` 相同 | 空 |
| 代理名称 | 自定义的代理名（需在服务端唯一） | `sshd4a` |
| 远程端口 | 公网访问端口，映射到本机 sshd 端口。**留空时默认等于本机 sshd 端口** | 本机 sshd 端口 |
| frpc 可执行文件路径 | 一般留空（使用内置/导入的二进制） | 空 |

然后到「**远程与监控**」屏幕：

1. 点击 frpc 的「安装」→「在线下载」（或「从文件导入」）；
2. 点击「开始」运行 frpc；
3. 点击「日志」查看运行日志（连接是否成功、报错原因等）。

应用会自动生成 `frpc.toml`（位于应用私有目录的 `tools/` 下），内容形如：

```toml
serverAddr = "1.2.3.4"
serverPort = 7000
auth.token = "your-strong-token"

[[proxies]]
name = "sshd4a"
type = "tcp"
localIP = "127.0.0.1"
localPort = 2222
remotePort = 6000
```

### 3. 使用穿透后的 SSH

```bash
ssh -p 6000 <用户名>@<公网服务器IP>
```

注意事项：

- 远程端口需要在服务器上未被占用，且被防火墙放行；部分 frps 配置了
  `allowPorts` 白名单，请确保包含该端口；
- frpc 与 frps 的 token 必须一致，否则日志中会出现认证失败；
- 若在应用中设置了「随远程服务自动启动」、「远程服务随 sshd 一起启动」，
  服务会在后台自动拉起 frpc。

## Tailscale 组网教程

应用内托管官方 `tailscaled` + `tailscale` CLI（版本 1.90.6），让设备加入
你自己的 tailnet。

### 1. 获取授权密钥（auth key）

1. 用浏览器登录 [Tailscale 管理后台](https://login.tailscale.com/admin)；
2. 打开 **Settings → Keys**，点击 **Generate auth key**；
3. 按需选择一次性/可复用、是否临时节点等选项，生成后复制密钥。

> 地址：<https://login.tailscale.com/admin/settings/keys>

### 2. 在应用中配置

「设置 → Tailscale 组网」：

| 配置项 | 说明 |
| --- | --- |
| 自动启动 tailscaled | 随远程服务一起启动后台守护进程 |
| 授权密钥 | 上一步生成的 auth key；如设备已登录可留空 |
| 设备名称（hostname） | 在 tailnet 中显示的设备名，可留空 |
| TUN 模式（需要 Root） | 关闭 = 用户态网络模式（免 Root）；打开 = TUN 模式（需要 Root） |
| tailscaled 可执行文件路径 | 一般留空（使用内置/导入的二进制） |

### 3. 使用

1. 到「远程与监控」→ Tailscale：「安装」→「在线下载」获取二进制；
2. 点击「开始」运行 `tailscaled`；
3. 点击「**连接**」（`tailscale up`，会带上授权密钥与 hostname）；
4. 点击「**状态**」（`tailscale status`）查看结果；
5. 不再使用时点击「**断开**」（`tailscale down`）。

登录状态（`tailscaled.state`）与 IPC socket（`tailscaled.sock`）保存在应用
私有目录中，因此应用重启后登录状态依然保留。

### 4. 两种模式的说明

- **用户态网络模式（默认，无需 Root）**
  - `tailscaled` 由应用本身启动，使用 `--tun=userspace-networking`；
  - 设备出站流量可通过本机 SOCKS5 代理（`127.0.0.1:1055`）访问 tailnet；
  - 其他设备访问本机 Tailscale IP 上的监听端口（例如 sshd 端口）时，
    连接由 `tailscaled` 转发到本机对应端口；
  - **不会**创建系统级 VPN 网卡，本机其他应用无法“透明”走 Tailscale 网络。
- **TUN 模式（需要 Root）**
  - 通过 `su` 启动 `tailscaled`，创建真实的 TUN 虚拟网卡；
  - 设备以与桌面端一致的普通节点身份加入 tailnet，双向访问体验完整；
  - 需要设备已 Root（创建 tun 设备与修改路由需要系统权限）。

注意事项：

- TUN 模式未检测到 `su` 时会启动失败并给出提示（见「提权模式详解」）；
- 使用一次性 auth key 时，节点重新登录需要重新生成密钥；
- 同一台设备重复 `up` 一般不会重复占用节点，若看到异常请先 `down` 再 `up`。

## 哪吒监控上报配置教程

应用内托管官方 [哪吒监控 agent](https://github.com/nezhahq/agent)
（`nezha-agent`，版本 v2.3.5），向你的哪吒面板上报设备状态。

### 1. 从面板获取地址与密钥

1. 登录你的哪吒面板（Dashboard）管理后台；
2. 进入「服务器」页面，点击「添加服务器」（或查看已有服务器）；
3. 复制面板给出的：
   - **面板地址**（形如 `demo.example.com:8008`，即 `host:port`）；
   - **密钥**（`client_secret`，一长串随机字符）。

> 面板的部署方法参见哪吒监控项目文档：
> <https://github.com/nezhahq/agent>

### 2. 在应用中配置

「设置 → 哪吒监控上报」：

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| 后台自动上报 | 随远程服务一起启动 agent | 关 |
| 面板地址（host:port） | 面板的地址与端口 | 空 |
| 密钥（secret） | 面板给出的 `client_secret` | 空 |
| 使用 TLS | 面板启用 HTTPS/gRPC-TLS 时开启 | 开 |
| 禁止面板远程执行命令 | 出于安全考虑默认禁止面板下发命令 | 开 |
| nezha-agent 可执行文件路径 | 一般留空（使用内置/导入的二进制） | 空 |

### 3. 生成配置与运行

1. 到「远程与监控」→ 哪吒监控上报：「安装」→「在线下载」获取二进制；
2. 点击「开始」，应用会自动生成 `nezha-agent.yml`，内容形如：

   ```yaml
   server: "demo.example.com:8008"
   client_secret: "YOUR_CLIENT_SECRET"
   tls: true
   disable_command_execute: true
   ```
3. agent 会持续在后台运行，向面板上报设备状态（CPU、内存、网络等）；
4. 「日志」/「监控上报面板」中可以看到实时的上报活动与错误信息，
   「清空日志」可清理输出。

注意事项：

- 面板地址与密钥必须与面板中该服务器的记录一致，否则面板中看不到上报；
- 如果面板使用 HTTPS（推荐），请保持「使用 TLS」开启；使用自签名证书
  且无法验证时，可临时关闭 TLS 或改用可信证书；
- 「禁止面板远程执行命令」默认开启，可避免面板被入侵后在本机上执行命令；
  确需远程执行时再关闭；
- 设备重启后如需继续上报，请开启「后台自动上报」或「随 sshd 服务一起启动」。

## 常见问题 FAQ

**Q1：为什么在线下载/导入的二进制需要 Root？**

Android 10 及以上**禁止应用执行自身数据目录中的文件**（安全限制）。只有
安装在系统 native 库目录（`nativeLibraryDir`，即 APK 内的
`jniLibs/<abi>/lib<name>.so`）中的文件才可以被应用直接执行。因此：

- 通过 `tools/fetch-go-binaries.sh` 打包进 APK 的官方二进制可以**直接执行**；
- 通过「在线下载」或「从文件导入」得到的二进制位于应用数据目录，
  **只能通过 `su` 以 Root 权限运行**。

**Q2：为什么 frpc 建议在「服务器地址」里直接填 IP？**

官方发布的 Linux 版 `frpc` 是纯 Go 程序，使用 Go 自带的 DNS 解析器；
在部分 Android 环境中它读取不到系统的 DNS 配置（例如 `/etc/resolv.conf`），
会出现“域名无法解析”的错误。**直接把「服务器地址」填成 frps 的 IP**
即可绕过该问题。

**Q3：Tailscale 的 TUN 模式为什么需要 Root？**

TUN 模式需要创建 `/dev/net/tun` 虚拟网卡并修改系统路由表，这需要系统
权限（`CAP_NET_ADMIN`），普通应用无法做到。非 Root 设备请使用默认的
**用户态网络模式**（无需 Root）。

**Q4：端口为什么限制在 1024–32768？**

小于 1024 的端口属于特权端口，普通应用无法绑定。应用对「端口号」输入
做了 `1024–32768` 的校验，超出范围会拒绝保存；如需特殊绑定，可使用
「额外的命令行选项」自行传入 `-p` 参数（由 dropbear 自行校验，风险自负）。

**Q5：Android 10+ / 新版系统还有哪些限制需要注意？**

- 应用数据目录不可执行（见 Q1）；
- 访问共享存储需要手动授予「所有文件访问权限」；
- 后台长期运行必须使用前台服务（本应用已处理，会显示常驻通知）；
- 无线调试的配对信息在设备重启后失效，需要重新配对（见提权模式详解）；
- 部分厂商系统（如 Samsung）需要把应用的电池策略设为「不受限制」，
  否则服务可能被系统杀掉。

**Q6：开启了 Root 模式但没有生效？**

请依次检查：

1. 设备是否已 Root（可用「设置 → 权限与提权 → 检测 Root 权限」）；
2. Root 管理器是否弹出了授权提示、是否已允许；
3. 如 `su` 不在常见路径（如位于 `/debug_ramdisk/su` 之外），请在
   「su 路径」中手动填写完整路径。

**Q7：为什么不能覆盖安装/升级旧版本？**

旧版本与本版本使用不同的包名与签名，属于两个独立的应用；如需切换，
请先卸载旧版本（配置会一并清除）。

**Q8：frpc 的「远程端口」留空会怎样？**

留空时，远程端口默认等于**本机 sshd 端口**（例如都是 2222）。若要区分
公网端口与本机端口，请显式填写「远程端口」。

**Q9：服务启动失败或端口被占用？**

- 在主界面/通知中查看监听地址是否与预期一致；
- 换一个端口（1024–32768 范围内）；
- 检查是否有其他应用占用了同一端口。

## 安全说明与免责声明

- sshd 默认监听所有网络接口（`*:端口`）。请仅在**可信网络**中启用，
  不要在公共 Wi-Fi 上开放；必要时使用防火墙/VPN。
- 建议启用**公钥登录**并使用强密码；不需要的认证方式请关闭
  （注意：关闭全部认证方式时应用会警告）。
- **提权模式（Root / ADB）会让会话获得较高权限**，属于高级功能：
  - Root 模式下 SSH 会话就是 root，任何能登录的人都等于拿到了整机权限；
  - ADB 模式拥有 `shell` 用户的能力（可执行 `pm`/`am` 等命令）；
  - 请自行评估风险并承担后果。
- 免责声明：本软件按“现状”提供，作者不对使用（尤其是提权功能）造成的
  任何数据丢失、设备损坏或安全问题负责。使用前请务必备份重要数据。

## 开源协议与致谢

- 本仓库依据 MIT 协议发布。
- 许可证：[MIT License](LICENSE)。本程序为自由软件：你可以自由使用、复制、修改、
  合并、发布、分发和/或销售本软件的副本。
- 服务组件由以下项目提供：
  - Dropbear：<https://github.com/mkj/dropbear>
  - rsync：<https://rsync.samba.org/>
  - OpenSSH（sftp-server）：<https://github.com/openssh/openssh-portable>
- Android HTTP 客户端：OkHttp（Apache-2.0）：<https://square.github.io/okhttp/>

## 构建说明

### 环境要求

- Android SDK（`compileSdk 36`），并设置 `ANDROID_HOME`；
- **NDK 30.0.14904198** 与 **CMake 4.1.2**（版本号见
  `gradle/libs.versions.toml`；`app/CMakeLists.txt` 负责构建 native 组件）；
- JDK：Gradle 工具链为 **Java 25**（见 `gradle/libs.versions.toml`）；
- 依赖 `com.github.MuntashirAkon:libadb-android`（JitPack 仓库，
  已在 `settings.gradle` 中配置）。

### 本地构建

- 参考仓库中的 `BUILDING`、`BUILDING-SIGNING` 等文档准备环境；
- 便捷脚本 `build.sh`（需要 `ANDROID_HOME` 与 `sdkmanager`）会先执行
  native 预构建脚本，再执行 release 构建；
- 如需把 frpc / Tailscale / 哪吒 agent 随 APK 一起打包，请在 Gradle 构建
  **之前**先运行 `tools/fetch-go-binaries.sh`（见下节）；不运行也能构建出
  可用 APK，缺失的二进制可在应用内「远程与监控」中在线下载或导入：

  ```bash
  # 预构建（build.sh 会自动调用，此处仅为说明）
  app/src/main/cpp/prebuild-rsyn.sh
  app/src/main/cpp/prebuild-dropbear.sh

  # 可选：下载随 APK 打包的官方 Go 辅助二进制（build.sh 不会自动调用）
  tools/fetch-go-binaries.sh armeabi-v7a arm64-v8a x86_64

  # debug / release
  ./gradlew assembleDebug      # 产物：app/build/outputs/apk/debug/PhoneSecurityAssistant-<版本>-debug.apk
  ./gradlew assembleRelease    # 产物：app/build/outputs/apk/release/PhoneSecurityAssistant-<版本>.apk
  ```

- release 签名配置见 `BUILDING-SIGNING` 与 `app/build.gradle` 顶部注释
  （`Sshd4a.properties` / keystore）；未配置时 release APK 会以未签名方式构建。
- ABI：
  - release：`armeabi-v7a`、`arm64-v8a`、`x86_64`；
  - debug：`x86_64`、`x86`（便于模拟器调试）。

### 打包 frpc / Tailscale / nezha-agent 官方二进制

仓库提供下载脚本 `tools/fetch-go-binaries.sh`：从官方发布地址下载
**frpc v0.65.0**、**tailscale(d)/1.90.6**、**nezha-agent v2.3.5**，
并放置为 `app/src/main/jniLibs/<abi>/lib<name>.so`，从而随 APK 一起
安装到 native 库目录、可被直接执行。

```bash
# 默认只下载 arm64-v8a（真机可用）
tools/fetch-go-binaries.sh

# 指定多个 ABI（会显著增大 APK，其他 ABI 主要用于模拟器/测试）
tools/fetch-go-binaries.sh arm64-v8a x86_64

# 强制重新下载
tools/fetch-go-binaries.sh --force arm64-v8a
```

注意：

- `app/src/main/jniLibs/` 已在 `.gitignore` 中忽略，**官方二进制不会提交到
  git 仓库**；
- debug 构建可能不包含这些二进制；此时可在应用内通过
  「远程与监控 → 安装 → 在线下载 / 从文件导入」补充（导入的二进制需要
  Root 才能执行，见 FAQ Q1）；
- frp v0.65.0 没有 32 位 x86（`386`）构建，`x86` ABI 会跳过 `libfrpc.so`。

### CI 构建

- 工作流 `.github/workflows/build-apk.yml` 默认下载 debug/release 所需 ABI 的
  Go 辅助二进制（`armeabi-v7a`、`arm64-v8a`、`x86_64`、`x86`，其中 x86 没有
  上游 frpc 构建），再在 GitHub Actions 上构建 debug/release APK（需要 NDK/CMake）。
- 构建产物（Artifacts）为 `PhoneSecurityAssistant-<版本>.apk`（release）和
  `PhoneSecurityAssistant-<版本>-debug.apk`（debug），可直接安装测试；配置了
  `KEYSTORE_BASE64` 等仓库 secrets 时 release 会签名（见
  `BUILDING-SIGNING`），否则以未签名方式构建。推送 `v*` 标签时还会创建
  GitHub Release 并附上两个 APK。

---

📖 English documentation: [**README.en.md**](README.en.md)
