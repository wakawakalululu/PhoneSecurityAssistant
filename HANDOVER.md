# 交接文档 — PhoneSecurityAssistant CI 修复

> 生成时间：2026-10-20
> 仓库：`wakawakalululu/PhoneSecurityAssistant`（原名 PinyinSSH）
> 当前 HEAD：`068f39a`
> CI 最新状态：run#11 **failure**（sha=068f39a）

---

## 1. 项目概述

Android SSH 服务器 + rclone 文件同步应用，品牌名"春夏秋手机安全助手"。
- 技术栈：AGP 9.3.2 / JDK 25 / NDK 30.0.16248370 / CMake 4.1.2
- Native 代码：dropbear SSH + libtommath（C）
- CI：GitHub Actions，构建 debug + release APK

## 2. CI 错误诊断方法

**关键发现**：公开仓库的 GitHub API 端点无需认证即可访问：
```
GET /repos/{owner}/{repo}/actions/runs
GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs
GET /repos/{owner}/{repo}/check-runs/{check_run_id}/annotations
```

用 `curl` 调用上述端点获取错误注解（annotations），即可定位 CI 失败根因。
CI 日志端点 `actions/jobs/{id}/logs` 返回 403（需认证），无法直接获取。

## 3. 已完成修复（11 个 commit，已推送）

| Commit | 修复内容 | 文件 |
|--------|---------|------|
| `62f8cee` | CI: 提取 Gradle 错误为 annotations | `.github/workflows/build-apk.yml` |
| `4f6b519` | CI: 格式调整 | workflow |
| `5b16fcc` | CI: pin runners to ubuntu-24.04 | workflow |
| `21174e2` | `android:exported=false` 添加到 AccessibilityService | `AndroidManifest.xml` |
| `61e3154` | CI build + OpenSSH sentinel; ADB bridge Phase 1 | 多文件 |
| `bc4d7cb` | 移除 stale kotlin{} block; NDK beta1→stable | `build.gradle`, `libs.versions.toml` |
| `283b463` | 移除未导入的 `@NonNull`; `Files.readString`→`readAllBytes` | `AdbBridgeShellMain.java`, `SyncTaskStore.java` |
| `defdda9` | lambda 捕获非 final 变量修复 | `SyncService.java` |
| `5cfe963` | 添加缺失的 `rd_accessibility_name` 字符串 | `strings_remote_desktop.xml` (values/ + values-en/) |
| `5836048` | CI: 提取 clang/ninja 错误 | workflow |
| `ba4465f` | CI: 简化错误提取为 tail -50 | workflow |
| `068f39a` | 添加静态 `config.h`（Android NDK 构建） | `app/src/main/cpp/dropbear/src/config.h` |

### 3.1 config.h 说明

- dropbear 的 `config.h` 正常由 `./configure` 生成，但 CMake 构建无此步骤
- CMakeLists.txt 也缺少 `configure_file()` 调用
- `config.h` 被 `.gitignore` 忽略（`app/src/main/cpp/dropbear/.gitignore:23`），需 `git add -f` 强制添加
- 创建的 `config.h` 针对 Android/Bionic libc，定义了 16 个 `HAVE_*` 宏和若干 `DISABLE_*` 宏

## 4. 当前失败原因

CI run#11（sha=068f39a）错误：

```
[5/724] Building C object CMakeFiles/tommath.dir/.../bn_mp_abs.c.o
FAILED: CMakeFiles/tommath.dir/.../bn_mp_abs.c.o

In file included from bn_mp_abs.c:1:
In file included from tommath_private.h:8:
In file included from tommath_class.h:1305:
   21 | #include "localoptions.h"
      |          ^~~~~~~~~~~~~~~~
1 error generated.
```

**根因**：`options.h:21` 在 `LOCALOPTIONS_H_EXISTS` 宏定义下 `#include "localoptions.h"`，
CMakeLists.txt 全局定义了 `LOCALOPTIONS_H_EXISTS`，但 `localoptions.h` 文件不存在。

## 5. 下一步操作

### 5.1 立即修复：创建 `localoptions.h`

在 `app/src/main/cpp/dropbear/src/localoptions.h` 创建空文件或最小配置文件：
```c
#ifndef LOCALOPTIONS_H
#define LOCALOPTIONS_H
#endif
```
然后 `git add -f`（可能也被 .gitignore 忽略）+ commit + push。

### 5.2 验证 CI

推送后等待 ~2-3 分钟，用以下命令检查：
```bash
# 获取最新 run
curl -s "https://api.github.com/repos/wakawakalululu/PhoneSecurityAssistant/actions/runs?per_page=1" | python3 -c "import json,sys; d=json.load(sys.stdin); r=d['workflow_runs'][0]; print(f'sha={r[\"head_sha\"][:7]} status={r[\"status\"]} conclusion={r[\"conclusion\"]}')"

# 获取 annotations（需先获取 job_id）
RUN_ID=<最新run_id>
JOB_ID=$(curl -s "https://api.github.com/repos/wakawakalululu/PhoneSecurityAssistant/actions/runs/$RUN_ID/jobs" | python3 -c "import json,sys; print(json.load(sys.stdin)['jobs'][0]['id'])")
curl -s "https://api.github.com/repos/wakawakalululu/PhoneSecurityAssistant/check-runs/$JOB_ID/annotations" | python3 -c "import json,sys; [print(a.get('message','')[:300]) for a in json.load(sys.stdin)]"
```

### 5.3 可能的后续问题

修复 `localoptions.h` 后可能还会遇到：
1. `config.h` 中缺少某些 `HAVE_*` 宏定义 → 根据 annotation 错误逐一添加
2. `default_options_guard.h` 生成问题
3. 其他 dropbear 编译错误（函数缺失、类型不匹配等）
4. CMake/NDK 链接错误

### 5.4 CI 通过后

- 恢复 workflow 的错误提取代码到合理版本（当前是 `tail -50` 调试版本）
- 考虑是否需要签名配置（当前 release APK 是 UNSIGNED）

## 6. 推送方式

```bash
cd /workspace/PinyinSSH
git push devspace master
# devspace remote: http://127.0.0.1:9800/git/github/wakawakalululu/PhoneSecurityAssistant.git
# 凭据自动注入，无需手动配置
```

## 7. 沙箱限制

- 无 Java/Android SDK/NDK，无法本地验证构建
- 只能通过 CI 远程验证
- `config.h` 等文件被 `.gitignore` 忽略，需 `git add -f`

## 8. 未实现的后续功能

- ADB 桥接 Phase 2/3：持久化 AdbTask/AdbTaskStore/AdbTaskService 和配置完善
- rclone 配置 UI 优化
- SSH 密钥管理增强
