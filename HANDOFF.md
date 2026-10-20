# 春夏秋手机安全助手 打包与 OpenCode 交接说明

更新日期：2026-10-20

## 当前状态

- OpenCode 工作区位于 `/workspaces/Sshd4a`；`local.properties` 指向 `/tmp/opencode/android-sdk`，但该 SDK 当前不存在，`ANDROID_HOME`/`ANDROID_SDK_ROOT` 也未设置。
- `local.properties` 已被 `.gitignore` 忽略，SDK 路径属于本机配置，不应提交或复制到其他机器。
- 2026-10-17 曾为当时源码生成 debug/release APK；这些 APK 不包含后续添加的 WebDAV Provider。
- `LICENSE` 已于 2026-10-19 提交 d602c5b 恢复为 MIT License。
- 本次 WebDAV 变更未完成 Android 编译或真机验证；`get_errors` 静态诊断与 `git diff --check` 通过。
- 此前源码的 Android 资源处理及 debug/release 组装曾通过；未进行真机功能测试或 CI 运行。

## 已确定的后续需求

### SSH 服务端更新与 Android 集成

目标是移除 Dropbear 服务端并迁移到 OpenSSH Portable 10.6p1（已核对上游 tag `V_10_6_P1`）；自研范围是 Android/JNI 生命周期、App 内配置、认证与权限适配，不从零实现 SSH 协议或密码学。

当前边界：

- 实际监听 SSH 连接的是 Dropbear 2026.94，通过 `jni-dropbear` JNI 启动；`SshdService` 依赖其参数、pid、日志和 watchdog 行为。
- 仓库含 OpenSSH 10.4p1 源码，但当前仅把 `sftp-server` 编入 APK；`BUILDING-UPDATES` 对 OpenSSH 使用 `--without-openssl` 配置，不能据此认为 OpenSSH sshd 已可用。当前 OpenSSH 源码版本低于上游最新 10.6p1。
- 隔离试构建 OpenSSH 10.6p1 sshd（Android NDK 30、`--without-openssl`）时，在 `sshbuf-misc.c` 处理 Bionic `unistd.h` 的 `__sentinel__(1)` 声明处失败；本地 10.4p1 的同文件 NDK 语法探针通过。根因尚未确认，不能据此改动第三方兼容宏。
- Dropbear 承载本项目的授权公钥、主密码、一次性密码、shell 注入、Root 登录和服务 shell 访问定制。直接替换 CMake target 或改版本字符串不会迁移这些行为。
- Dropbear 上游最新 tag 为 `DROPBEAR_2026.94`，与本地一致；OpenSSH Portable 上游最新 tag 为 `V_10_6_P1`，本地 sftp-server 源码仍是 10.4p1。

迁移顺序：先比较 OpenSSH 10.4/10.6 的 configure 宏、编译参数及头文件顺序，定位 sentinel 错误；确认 crypto backend 与许可；建立独立 OpenSSH sshd Android 构建目标和启动/停止原型；逐项移植并测试 host key、authorized_keys、主密码/一次性密码、普通 shell、Root/ADB、scp/rsync/sftp、日志与重启；通过真机和多 ABI 验证后再移除 Dropbear 源码及构建目标。迁移期间保持现有 Dropbear 主路径可用，避免半迁移导致无法登录或认证回归。

### 远程 ADB 命令与持久化

此项是明确的后续产品需求：支持从远程控制端发起命令，并由已配对的 Android Wireless Debugging ADB 连接执行；同时定义连接、后台运行、任务状态和输出的持久化行为。

当前实现边界：

- `AdbManager.exec()` 支持应用内发起一次性 `shell:` 命令；没有 SSH/其他远程入口调用 ADB 的桥接层。
- ADB host/port 设置已保存；ADB 配对密钥/证书存于 `files/adb`。连接本身为内存态，没有确认进程重启后的自动重连。
- 内置终端输出/历史存在 ViewModel 内存中，ADB 命令不支持可靠取消、交互式 stdin 或进程死亡后的任务恢复。
- frpc、Tailscale、Nezha 的前台服务不负责 ADB 命令任务；Nezha 远程命令执行与 ADB shell 是不同权限/通道，不能混为一谈。

默认决策：以 SSH 登录作为远程入口，通过明确授权的 SSH 身份调用专用 ADB 命令通道；ADB 未连接时明确失败，不回退到普通 app shell 或 Root。Nezha Agent 远程命令与 ADB shell 保持为不同权限通道，不默认开放任意命令。持久任务至少保存状态和有界输出，可在后台继续运行；进程死亡时标记为中断，不自动重放命令，避免重复执行非幂等操作。范围是需求已定，远程桥接、任务存储/生命周期和真机安全测试仍未实现。

### App 内配置要求

这是全项目的硬性产品约束：功能所需的用户配置必须能在 App 设置页或对应的 App 管理面板中完成；用户不应被要求编辑 TOML/YAML/XML/Gradle 配置文件、环境变量或通过 ADB shell 手工写配置。应用内部可以根据 App 设置生成并管理私有配置文件，但不得把手工改文件作为正常配置流程。

现有 frpc、Tailscale、Nezha 的服务端地址/凭据/模式/启动策略，以及 ADB 配对地址和终端权限模式，已有 App 内设置入口。远程 ADB 功能实施时，也必须提供 App 内可见、可撤销的配置项，至少包括：远程 ADB 功能总开关、获准执行的 SSH 身份/密钥、命令权限策略、ADB 连接恢复策略、后台任务行为，以及任务状态/输出的保留上限。高风险能力默认关闭；授权变更应在界面中明确提示，不能依赖隐藏偏好键或手改文件。

验收时应逐项从全新安装后的 UI 完成配置、保存、修改、撤销并验证生效；README 的设置说明也须与实际 App 控件保持一致。安装调试工具或构建 APK 所需的开发者命令不属于终端用户功能配置。

### 文件同步与 WebDAV

当前边界：设备端集成 rsync 3.4.4、SFTP 和 SCP；电脑/NAS 可通过 SSH 对手机发起单次 rsync 传输。已加入只读 WebDAV SAF Provider：HTTPS + Basic auth、目录列举与文件流式读取；OkHttp 4.12.0（Apache-2.0），凭据通过 App 设置保存在 app-private SharedPreferences，备份规则排除了偏好目录。它不是本地路径挂载，也不支持上传/删除/重命名。Tailscale 只负责网络连通，不是同步引擎。

**2026-10-19 更新：同步 MVP 已实现（未编译/真机验证）。** 新增 `com.tencentx.pinyin.sync` 包：

- `SyncTask` / `SyncTaskStore`：任务模型与 JSON 持久化（`files/sync/tasks.json`，已排除备份）。
- `SyncManager`：rclone 命令构建、`rclone obscure` 密码处理、`--progress` 输出解析；复用 `BinaryManager`。
- `SyncService`：前台服务，自管 `Process`（解析进度、写日志、更新状态、广播），任务完成后自动停止；**完成时发送结果通知**（独立通道，成功/失败）。
- `SyncFragment` / `SyncViewModel`：任务列表 UI（卡片含状态/进度/日志预览）、新建/编辑对话框（含调度配置）、启停/删除。
- `SyncInstallHelper`：rclone v1.69.3 下载/导入（与 frpc 等同模式）。
- `SyncScheduler` / `SyncTriggerReceiver` / `SyncConditions`：定时调度引擎（AlarmManager `setAndAllowWhileIdle`），支持间隔、仅 Wi-Fi、仅充电条件触发。
- `BootReceiver`：设备重启/应用更新后自动恢复所有定时调度（`SyncScheduler.rescheduleAll`）。
- 接入：`AndroidManifest` 注册 `SyncService` + `SyncTriggerReceiver`、`main_menu.xml` 加「文件同步」、`SettingsFragment` 加「文件同步 rclone」分组、`Prefs.SYNC_BINARY` 键、`backup_rules.xml`/`data_extraction_rules.xml` 排除 `./sync`、`App.onCreate` 恢复调度。
- 兼容性：minSdk 从 30 降至 26（Android 8.0+），存储权限版本感知（API 30+ `MANAGE_EXTERNAL_STORAGE` / API 26-29 `WRITE_EXTERNAL_STORAGE`）。
- 单元测试：3 个测试文件，90 个 `@Test`，覆盖率 93.6%–100%（javac+JUnit+JaCoCo 验证通过）。

MVP 范围：单向 copy（DOWNLOAD/UPLOAD）、SFTP + WebDAV 两种远端、手动触发 + **定时调度（含 Wi-Fi/充电条件）**、进度/日志/取消、任务 CRUD、**重启恢复调度**、**完成通知**。**明确不做**：双向同步、删除传播、任务历史保留上限、SAF content URI 作为本地路径（rclone 需 POSIX 路径，本地路径用文本输入如 `/sdcard/Photos`，依赖已有的 `MANAGE_EXTERNAL_STORAGE`）。

剩余工作（按 HANDOFF 原推荐分阶段）：

2. 后台任务增强：重试和任务历史保留上限；遵从 Android 后台执行限制，不承诺精确准点运行。定时双向同步及删除传播需单独设计冲突恢复和用户确认。
3. WebDAV 访问：当前实现只读 Android `DocumentsProvider`/SAF；下一步先补真实 WebDAV 服务互操作测试，再评估上传/改名等写能力；通用 FUSE/系统路径挂载不作为普通设备的承诺，Root 版另行评估。
4. 真机验证：当前 Android SDK 不可用，尚未 Gradle 编译或真机验证；rclone 许可证（MIT，与 GPL-3.0 兼容）、Android ABI（官方提供 arm64/arm/386/amd64 静态二进制）已核对，但命令注入面（rclone 配置 INI 注入）与凭据保护需在真机测试中确认。

App 内至少可配置远端 URL/协议、凭据、TLS 校验、路径、本地目录授权、只读/写入策略、同步方向/冲突策略、调度条件和缓存上限；应用自动生成内部配置，不要求手改 rclone TOML 或其他配置文件。第三方组件需先核对许可证、维护状态、ABI 与供应链安全。

参考方向：

- [rclone](https://github.com/rclone/rclone)：SFTP/WebDAV、copy/sync/bisync；MVP 默认只选非删除式 copy。
- [Android DocumentsProvider](https://developer.android.com/guide/topics/providers/document-provider)：无 Root 远端文档树接入系统文件选择器的官方扩展点。
- WebDAV 客户端库须在选型时单独核实许可证、Android API 和 TLS 行为；不要把 WebDAV server 与客户端挂载混为一谈。


### 项目名称更改

当前品牌为 **春夏秋手机安全助手**，Gradle root project 与 APK/Actions artifact 前缀为 `PhoneSecurityAssistant`；Android `applicationId` 与源码 namespace 保持 `com.tencentx.pinyin`，避免变更应用身份、升级路径和现有数据。工作区路径为 `/workspaces/Sshd4a`，且未配置 Git remote；GitHub 仓库名/URL 尚未更改。

## 构建验证结果

- 通过：native 预构建脚本及 debug/release 组装；资源处理任务也通过。
- release 使用 `app/proguard-rules.pro` 中针对 Conscrypt 旧平台适配器的精准 `-dontwarn` 规则，R8 与完整组装通过。
- 历史产物：`app/build/outputs/apk/debug/PhoneSecurityAssistant-1.13.2-debug.apk`（约 55 MiB）；`app/build/outputs/apk/release/PhoneSecurityAssistant-1.13.2.apk`（约 39 MiB）。这些是 WebDAV Provider 加入前的产物。
- 历史 release 未配置 keystore；`apksigner verify` 返回 `DOES NOT VERIFY`，即未签名，不能作为正式发布 APK。
- 本地 `jniLibs` 当前只有 arm64 Go 辅助程序；实测 release 包内 frpc、Tailscale、Nezha 也只包含 `arm64-v8a`。其他原生 ABI 有 SSH/rsync 库，但缺少这三类 Go 工具。CI workflow 默认已改为下载全部目标 ABI，需运行 CI 再确认。
- Gradle/NDK 输出包含上游 C 源码宏重定义、弃用等 warning；这些告警目前保留，未影响构建。

## 打包方式

### OpenCode 容器内本地构建

1. 恢复 Android SDK、`sdkmanager`、CMake、NDK 和 Java 环境。CI 工作流使用 JDK 25、SDK 36、NDK `30.0.14904198`、CMake `4.1.2`；版本调整应与 `gradle/libs.versions.toml` 保持一致。
2. 如 APK 需要包含 frpc、Tailscale 和哪吒 agent，先下载对应 ABI 的官方二进制：

   ```bash
   bash tools/fetch-go-binaries.sh arm64-v8a
   ```

   可按需增加 `armeabi-v7a`、`x86_64` 或 `x86`。注意：当前 `build.sh` 不会调用这个下载脚本；CI 工作流会下载并缓存这些文件。
3. 从仓库根目录执行 `build.sh`，或手动执行等效步骤：

   ```bash
   bash app/src/main/cpp/prebuild-rsyn.sh
   bash app/src/main/cpp/prebuild-dropbear.sh
   ./gradlew --no-daemon assembleRelease
   ```

4. release APK 预期位于 `app/build/outputs/apk/release/`。构建后检查 APK 文件存在，并使用 Android SDK 的 `apksigner verify --verbose --print-certs <apk>` 确认签名状态。

### Release 签名

- 本地签名配置见 `BUILDING-SIGNING` 和 `app/build.gradle`：在用户级 `~/.gradle/gradle.properties` 中设置 `Sshd4a.properties=/安全路径/Sshd4a.properties`，属性文件提供 keystore 路径、密码、别名和 key 密码。
- 不要把 keystore、密码或签名属性文件加入仓库。
- GitHub Actions 使用 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` secrets。未配置时仍可构建，但 release APK 为未签名；fork PR 无法读取这些 secrets。

## GitHub Actions 打包

工作流 `.github/workflows/build-apk.yml` 配置为在 `main`/`master` push、PR、`v*` 标签和手动触发时构建。它会下载 Go 辅助二进制、生成 native 预构建文件、执行 `assembleDebug assembleRelease`，并上传 APK artifacts；推送 `v*` 标签时还会创建 GitHub Release。签名是可选的。

该工作流已于 2026-10-19 提交（d602c5b / 6449d38），但 GitHub Actions 4 次运行均失败在 `Assemble debug + release APKs` 步骤，APK 未成功构建。构建通过前不应把产物或发布流程描述为已验证。

## 后续处理清单

1. 在 App 设置/UI 中先提供远程 ADB 开关、SSH 身份授权、命令权限策略和连接恢复选项，再实现 SSH 身份认证后的专用 ADB 命令通道；ADB 未连接时失败，不降级到 app shell/Root。
2. 为 ADB 任务增加 App 可配置的后台执行、任务状态与有界日志保留；进程死亡时标记中断，不自动重放，并补连接恢复、取消和权限边界测试。
3. 在 SDK 恢复后编译并验证 WebDAV Provider；随后实现 App 管理的单向同步 MVP，再评估定时同步。同步及 WebDAV 写能力完成并真机验证前不得在 README 中列为现有能力。
4. 如需本地多 ABI APK，运行 `tools/fetch-go-binaries.sh armeabi-v7a arm64-v8a x86_64 x86` 并核验；x86 没有上游 frpc。
5. 配置正式 release keystore/secrets，使用 `apksigner verify --verbose --print-certs` 验证签名。
6. 修复 CI 构建失败（`Assemble` 步骤，4 次连续 failure），使 GitHub Actions 转绿；`LICENSE` 已恢复、workflow 已提交、remote 已配置。

## 相关文件

- `build.sh`：本地 release 构建入口；生成 native 文件后调用 Gradle，不负责下载 Go 辅助二进制。
- `BUILDING`、`BUILDING-SIGNING`：环境准备和本地签名说明。
- `app/build.gradle`：Android 变体、ABI 和签名配置。
- `tools/fetch-go-binaries.sh`：下载并打包 Go 辅助二进制。
- `.github/workflows/build-apk.yml`：GitHub Actions 自动构建、上传 artifacts 和标签发布。