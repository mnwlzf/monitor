#!/usr/bin/env bash
# ============================================================
# Monitor 镜像更新脚本
#
# 特点：
#   - 自动沿用当前目录下已有的 .env / config / logs
#   - 默认从 GHCR 拉取最新镜像后重建容器
#   - 也支持本地重新构建镜像
#   - 支持指定标签与回滚
#
# 用法：
#   ./update-monitor.sh                 拉取最新镜像并重建容器
#   ./update-monitor.sh --build         本地重新构建镜像后重建容器
#   ./update-monitor.sh --build --no-cache
#   ./update-monitor.sh --tag v1.0.0    使用指定标签
#   ./update-monitor.sh --rollback      回滚到上一次更新前的镜像
#   ./update-monitor.sh --help
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

COMPOSE_FILE="${COMPOSE_FILE:-compose.external.yaml}"
SERVICE="${SERVICE:-app}"
CONTAINER="${CONTAINER:-monitor}"
ENV_FILE="${ENV_FILE:-.env}"
LOG_DIR="${LOG_DIR:-logs}"
IMAGE_REPO="${IMAGE_REPO:-ghcr.io/mnwlzf/monitor}"

MODE="pull"
TAG=""
BUILD_ARGS=()

log()  { printf '\033[32m[%s]\033[0m %s\n' "$(date '+%F %T')" "$*"; }
warn() { printf '\033[33m[%s]\033[0m %s\n' "$(date '+%F %T')" "$*"; }
die()  { printf '\033[31m[%s]\033[0m %s\n' "$(date '+%F %T')" "$*" >&2; exit 1; }

usage() {
  sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --build)     MODE="build"; shift ;;
    --no-cache)  BUILD_ARGS+=("--no-cache"); shift ;;
    --tag)       TAG="${2:-}"; [[ -n "$TAG" ]] || die "--tag 需要一个参数"; shift 2 ;;
    --rollback)  MODE="rollback"; shift ;;
    -h|--help)   usage; exit 0 ;;
    *)           die "未知参数: $1（用 --help 查看用法）" ;;
  esac
done

command -v docker >/dev/null 2>&1 || die "未安装 docker"
docker compose version >/dev/null 2>&1 || die "未安装 docker compose 插件"
[[ -f "$COMPOSE_FILE" ]] || die "找不到 $COMPOSE_FILE，请在部署目录执行本脚本"
[[ -f "$ENV_FILE" ]]    || die "找不到 $ENV_FILE，请先准备数据库/Redis 配置"

export COMPOSE_FILE
export MONITOR_IMAGE="${IMAGE_REPO}:${TAG:-latest}"

# ------------------------------------------------------------
# 1. 备份配置，保证任何情况下都能恢复
# ------------------------------------------------------------
STAMP="$(date +%Y%m%d%H%M%S)"
cp "$ENV_FILE" "${ENV_FILE}.bak.${STAMP}"
log "配置已备份: ${ENV_FILE}.bak.${STAMP}"

# ------------------------------------------------------------
# 2. 日志目录权限：容器内以 UID 10001 运行
# ------------------------------------------------------------
mkdir -p "$LOG_DIR"
if ! chown -R 10001:10001 "$LOG_DIR" 2>/dev/null; then
  warn "无法修改 $LOG_DIR 属主，如启动报 Permission denied 请手动执行：chown -R 10001:10001 $LOG_DIR"
fi

# ------------------------------------------------------------
# 3. 记录当前镜像，供回滚使用
# ------------------------------------------------------------
if docker inspect "$CONTAINER" >/dev/null 2>&1; then
  docker inspect -f '{{.Image}}' "$CONTAINER" > .last_image_id
  log "已记录当前镜像 ID: $(cat .last_image_id)"
fi

# ------------------------------------------------------------
# 4. 按模式执行
# ------------------------------------------------------------
case "$MODE" in
  rollback)
    [[ -f .last_image_id ]] || die "没有找到 .last_image_id，无法回滚"
    ROLLBACK_ID="$(cat .last_image_id)"
    docker image inspect "$ROLLBACK_ID" >/dev/null 2>&1 || die "回滚镜像已不存在: $ROLLBACK_ID"
    docker tag "$ROLLBACK_ID" "${IMAGE_REPO}:rollback" >/dev/null
    export MONITOR_IMAGE="${IMAGE_REPO}:rollback"
    log "回滚到镜像: $ROLLBACK_ID"
    ;;

  build)
    [[ -f Dockerfile ]] || die "--build 需要仓库源码（Dockerfile/frontend/backend），当前目录没有"
    log "本地重新构建镜像: ${MONITOR_IMAGE}"
    docker compose build "${BUILD_ARGS[@]}" "$SERVICE"
    ;;

  pull)
    log "拉取最新镜像: ${MONITOR_IMAGE}"
    if ! docker compose pull "$SERVICE"; then
      warn "镜像拉取失败，尝试使用本地已有镜像继续"
    fi
    docker image inspect "$MONITOR_IMAGE" >/dev/null 2>&1 || die "本地没有可用镜像: $MONITOR_IMAGE"
    ;;
esac

# ------------------------------------------------------------
# 5. 重建容器（--force-recreate 保证新的环境变量生效）
# ------------------------------------------------------------
log "重建并启动容器..."
docker compose up -d --force-recreate --no-build "$SERVICE"

# ------------------------------------------------------------
# 6. 等待健康检查
# ------------------------------------------------------------
log "等待容器健康检查..."
HEALTH="unknown"
for _ in $(seq 1 60); do
  HEALTH="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$CONTAINER" 2>/dev/null || echo missing)"
  case "$HEALTH" in
    healthy|none) break ;;
    unhealthy)    warn "容器状态 unhealthy"; break ;;
  esac
  sleep 3
done

docker ps --filter "name=^${CONTAINER}$" --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'

if [[ "$HEALTH" == "healthy" ]]; then
  log "更新完成，容器运行正常"
else
  warn "容器状态: $HEALTH，最近日志："
  docker logs --tail 40 "$CONTAINER" 2>&1 || true
  exit 1
fi

log "最近采集日志："
docker logs --since 5m "$CONTAINER" 2>&1 | grep -E '全部账号并发采集结束|账号采集失败|凭证解密失败' | tail -5 || true

log "完成。如需回滚：$0 --rollback"