#!/usr/bin/env bash
#
# tools/auto-commit.sh — 自动增量 Git 提交守护脚本
#
# 用法:
#   tools/auto-commit.sh                 # 前台守护进程: 每 20 秒检查并提交一次
#   tools/auto-commit.sh --interval 30   # 自定义轮询间隔(秒)
#   tools/auto-commit.sh --once          # 只执行一次检查+提交, 然后退出(CI/手动)
#   tools/auto-commit.sh --help          # 显示帮助
#
# 停止方式:
#   touch tools/.auto-commit.stop          # 脚本删除该文件、记录日志并干净退出
#   kill "$(cat tools/.auto-commit.pid)"   # 或直接结束 PID 文件中记录的进程
#
# 后台运行示例:
#   cd /workspaces/Sshd4a && \
#     nohup tools/auto-commit.sh --interval 20 >> tools/.auto-commit.log 2>&1 & \
#     echo $! > tools/.auto-commit.pid
#
# 行为说明:
#   - 每轮执行 git add -A (遵循 .gitignore), 仅当确实有暂存变更时才提交
#   - 提交信息: "自动提交 <时间>（<N> 个文件变更）" + diff --stat 前 25 行正文
#     + "[auto-commit]" 尾注
#   - 合并/变基/拣选进行中会跳过当前轮; 提交失败(如 index.lock)会记录并在下一轮重试
#   - 绝不执行 push / commit --amend / 分支或标签操作 / 任何交互式 git 命令
#   - 自身的运行时文件(日志/PID/停止文件)由 tools/.gitignore 忽略
#
set -uo pipefail

# ---------------------------------------------------------------------------
# 基本路径与默认配置
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." >/dev/null 2>&1 && pwd)"

LOG_FILE="${ROOT_DIR}/tools/.auto-commit.log"
PID_FILE="${ROOT_DIR}/tools/.auto-commit.pid"
STOP_FILE="${ROOT_DIR}/tools/.auto-commit.stop"

MODE="watch"   # watch(默认前台守护) | once(单次)
INTERVAL=20    # 轮询间隔(秒)
STOPPED=0      # 置 1 后主循环退出
EXIT_CODE=0

# ---------------------------------------------------------------------------
# 日志与帮助
# ---------------------------------------------------------------------------
log() {
  local line
  line="[$(date '+%Y-%m-%d %H:%M:%S')] $*"
  printf '%s\n' "${line}" >> "${LOG_FILE}"
  if [ -t 1 ]; then
    printf '%s\n' "${line}"
  fi
}

usage() {
  cat <<'EOF'
用法: tools/auto-commit.sh [选项]

默认在前台运行守护进程, 每隔一段时间检查一次工作区, 有变更就自动 git commit。

选项:
  --interval N   轮询间隔秒数(默认 20, 必须为正整数)
  --once         只检查并提交一次, 然后退出(适合 CI 或手动运行)
  --help, -h     显示本帮助

停止:
  touch tools/.auto-commit.stop          # 脚本删除该文件、记录日志并干净退出
  kill "$(cat tools/.auto-commit.pid)"   # 或向 PID 文件中记录的进程发送信号

日志文件:  tools/.auto-commit.log
PID 文件:  tools/.auto-commit.pid

后台运行示例:
  cd /workspaces/Sshd4a && \
    nohup tools/auto-commit.sh --interval 20 >> tools/.auto-commit.log 2>&1 & \
    echo $! > tools/.auto-commit.pid
EOF
}

# ---------------------------------------------------------------------------
# 单轮: 检查并提交
# ---------------------------------------------------------------------------
run_cycle() {
  # 1) 停止文件
  if [ -f "${STOP_FILE}" ]; then
    rm -f "${STOP_FILE}"
    log "检测到停止文件 tools/.auto-commit.stop, 已删除, 准备退出。"
    STOPPED=1
    return 0
  fi

  # 2) 仓库检查
  if [ ! -d "${ROOT_DIR}/.git" ]; then
    log "错误: 找不到 ${ROOT_DIR}/.git, 无法继续。"
    STOPPED=1
    EXIT_CODE=1
    return 1
  fi

  # 3) 合并 / 变基 / 拣选进行中 → 跳过本轮
  if [ -d "${ROOT_DIR}/.git/rebase-merge" ] \
    || [ -d "${ROOT_DIR}/.git/rebase-apply" ] \
    || [ -f "${ROOT_DIR}/.git/MERGE_HEAD" ] \
    || [ -f "${ROOT_DIR}/.git/CHERRY_PICK_HEAD" ]; then
    log "检测到合并/变基/拣选进行中, 跳过本轮。"
    return 0
  fi

  # 4) 暂存所有变更(遵循 .gitignore)
  if ! git -C "${ROOT_DIR}" add -A; then
    log "错误: git add -A 失败, 本轮放弃, 下一轮重试。"
    return 1
  fi

  # 5) 没有变更 → 等待下一轮
  if git -C "${ROOT_DIR}" diff --cached --quiet; then
    log "本轮无变更, 等待下一轮。"
    return 0
  fi

  # 6) 构造提交信息并提交
  local ts count summary body oneline
  ts="$(date '+%Y-%m-%d %H:%M:%S')"
  count="$(git -C "${ROOT_DIR}" diff --cached --name-only | wc -l | tr -d '[:space:]')"
  summary="自动提交 ${ts}（${count} 个文件变更）"
  body="$(git -C "${ROOT_DIR}" diff --cached --stat | head -n 25)"

  if git -C "${ROOT_DIR}" commit -m "${summary}" -m "${body}" -m "[auto-commit]"; then
    oneline="$(git -C "${ROOT_DIR}" log -1 --oneline)"
    log "提交成功: ${oneline}"
    return 0
  fi

  log "错误: git commit 失败(如 index.lock 被占用), 暂存状态保留, 下一轮重试。"
  return 1
}

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    --once)
      MODE="once"
      shift
      ;;
    --interval)
      if [ $# -lt 2 ]; then
        echo "错误: --interval 需要一个秒数参数。" >&2
        usage >&2
        exit 2
      fi
      INTERVAL="$2"
      shift 2
      ;;
    --interval=*)
      INTERVAL="${1#*=}"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "错误: 未知参数 '$1'。" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "${INTERVAL}" in
  ''|*[!0-9]*)
    echo "错误: --interval 必须是非负整数秒, 当前为 '${INTERVAL}'。" >&2
    exit 2
    ;;
esac
if [ "${INTERVAL}" -le 0 ]; then
  echo "错误: --interval 必须大于 0。" >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# 单次模式
# ---------------------------------------------------------------------------
if [ "${MODE}" = "once" ]; then
  run_cycle
  exit $?
fi

# ---------------------------------------------------------------------------
# 守护进程模式
# ---------------------------------------------------------------------------
# 防止重复启动: 若 PID 文件中的进程仍在运行则不再启动第二个实例
if [ -f "${PID_FILE}" ]; then
  old_pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [ -n "${old_pid}" ] && [ "${old_pid}" != "$$" ] && kill -0 "${old_pid}" 2>/dev/null; then
    log "已有守护进程在运行(PID ${old_pid}), 本次不重复启动。"
    exit 0
  fi
fi

printf '%s\n' "$$" > "${PID_FILE}"
log "自动提交守护进程启动: PID $$, 间隔 ${INTERVAL}s, 仓库 ${ROOT_DIR}"

cleanup_and_exit() {
  if [ -f "${PID_FILE}" ] && [ "$(cat "${PID_FILE}" 2>/dev/null || true)" = "$$" ]; then
    rm -f "${PID_FILE}"
  fi
  log "收到终止信号, 自动提交守护进程退出(PID $$)。"
  exit "${EXIT_CODE}"
}
trap cleanup_and_exit INT TERM

while [ "${STOPPED}" -eq 0 ]; do
  run_cycle || true
  if [ "${STOPPED}" -eq 1 ]; then
    break
  fi
  sleep "${INTERVAL}"
done

if [ -f "${PID_FILE}" ] && [ "$(cat "${PID_FILE}" 2>/dev/null || true)" = "$$" ]; then
  rm -f "${PID_FILE}"
fi
log "自动提交守护进程退出(PID $$)。"
exit "${EXIT_CODE}"
