#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ENV_FILE="$ROOT_DIR/.env"
EXAMPLE_FILE="$ROOT_DIR/.env.example"
DEPLOY_DIR="$ROOT_DIR/.deploy"
MODE_FILE="$DEPLOY_DIR/mode"
NAPCAT_FLAG_FILE="$DEPLOY_DIR/with-napcat"
IMAGE_REPO="${MONITOR_IMAGE_REPO:-ghcr.io/mnwlzf/monitor}"
CONTAINER_NAME="monitor"
LEGACY_KEYS="MONITOR_COLLECTION_ENABLED MONITOR_WEB_ALLOWED_ORIGINS MONITOR_WEB_SEARCH_ALLOW_NON_ADMIN"

log()  { printf '\033[32m[%s]\033[0m %s\n' "$(date '+%F %T')" "$*"; }
warn() { printf '\033[33m[%s]\033[0m %s\n' "$(date '+%F %T')" "$*" >&2; }
die()  { printf '\033[31m[%s]\033[0m %s\n' "$(date '+%F %T')" "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Monitor 统一部署脚本

用法：
  ./scripts/deploy.sh install [--mode full|external] [--with-napcat]
  ./scripts/deploy.sh init    [--mode full|external] [--with-napcat]
  ./scripts/deploy.sh update
  ./scripts/deploy.sh rollback
  ./scripts/deploy.sh status
  ./scripts/deploy.sh logs
  ./scripts/deploy.sh env-check
  ./scripts/deploy.sh env-fix
  ./scripts/deploy.sh doctor

模式：
  full      使用 compose.yaml，内置 PostgreSQL + Redis（新服务器推荐）
  external  使用 compose.external.yaml，连接已有 PostgreSQL + Redis

示例：
  ./scripts/deploy.sh install                         # 最小部署：app + PostgreSQL + Redis
  ./scripts/deploy.sh install --with-napcat           # 再加 NapCat，扫码登录 QQ
  ./scripts/deploy.sh install --mode external         # 使用已有 DB / Redis
  ./update-monitor.sh                                 # 正常更新 app 到 latest
  ./scripts/deploy.sh env-check                       # 检查 .env 与模板的 key 差异
EOF
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"
}

require_docker() {
  require_cmd docker
  docker compose version >/dev/null 2>&1 || die "缺少 docker compose 插件"
}

require_env_file() {
  [[ -f "$ENV_FILE" ]] || die "找不到 .env。首次部署请运行：./scripts/deploy.sh install"
}

env_get() {
  local key="$1"
  [[ -f "$ENV_FILE" ]] || return 0
  awk -v key="$key" '
    $0 ~ "^" key "=" { sub(/^[^=]*=/, ""); value=$0 }
    END { print value }
  ' "$ENV_FILE" | tr -d '\r'
}

env_has() {
  local key="$1"
  [[ -f "$ENV_FILE" ]] && grep -q "^${key}=" "$ENV_FILE"
}

set_env_value() {
  local key="$1" value="$2" tmp
  tmp="$(mktemp)"
  awk -v key="$key" -v value="$value" '
    BEGIN { found=0 }
    index($0, key "=") == 1 { print key "=" value; found=1; next }
    { print }
    END { if (!found) print key "=" value }
  ' "$ENV_FILE" > "$tmp"
  mv "$tmp" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
}

file_keys() {
  awk -F= '/^[A-Za-z_][A-Za-z0-9_]*=/{print $1}' "$1" | sort -u
}

read_mode() {
  if [[ -f "$MODE_FILE" ]]; then
    local mode
    mode="$(tr -d '\r\n' < "$MODE_FILE")"
    case "$mode" in
      full|external) printf '%s\n' "$mode"; return ;;
      *) die ".deploy/mode 内容无效：$mode" ;;
    esac
  fi
  printf 'full\n'
}

write_mode() {
  local mode="$1"
  case "$mode" in full|external) ;; *) die "mode 只能是 full 或 external" ;; esac
  mkdir -p "$DEPLOY_DIR"
  chmod 700 "$DEPLOY_DIR" 2>/dev/null || true
  printf '%s\n' "$mode" > "$MODE_FILE"
  chmod 600 "$MODE_FILE" 2>/dev/null || true
}

napcat_enabled() {
  [[ -f "$NAPCAT_FLAG_FILE" ]]
}

napcat_int() {
  if napcat_enabled; then printf '1\n'; else printf '0\n'; fi
}

set_napcat_flag() {
  local enabled="$1"
  mkdir -p "$DEPLOY_DIR"
  chmod 700 "$DEPLOY_DIR" 2>/dev/null || true
  if [[ "$enabled" == "1" ]]; then
    : > "$NAPCAT_FLAG_FILE"
  else
    rm -f "$NAPCAT_FLAG_FILE"
  fi
  chmod 600 "$NAPCAT_FLAG_FILE" 2>/dev/null || true
}

compose_file() {
  local mode="$1"
  if [[ "$mode" == "full" ]]; then
    printf '%s\n' "$ROOT_DIR/compose.yaml"
  else
    printf '%s\n' "$ROOT_DIR/compose.external.yaml"
  fi
}

compose() {
  local mode="$1"; shift
  local file
  file="$(compose_file "$mode")"
  docker compose -f "$file" --env-file "$ENV_FILE" "$@"
}

ensure_dirs() {
  local mode="$1"
  mkdir -p "$ROOT_DIR/config" "$ROOT_DIR/logs" "$DEPLOY_DIR"
  chmod 700 "$DEPLOY_DIR" 2>/dev/null || true
  if [[ "$(id -u)" == "0" ]]; then
    chown -R 10001:10001 "$ROOT_DIR/logs" 2>/dev/null || warn "无法修改 logs 属主，必要时手动 chown -R 10001:10001 logs"
  fi
  if [[ "$mode" == "full" ]]; then
    mkdir -p "$ROOT_DIR/logs"
  fi
  if napcat_enabled; then
    local data_dir
    data_dir="$(env_get NAPCAT_DATA_DIR)"
    [[ -n "$data_dir" ]] || data_dir="./napcat"
    case "$data_dir" in
      /*) ;;
      *) data_dir="$ROOT_DIR/$data_dir" ;;
    esac
    mkdir -p "$data_dir/qq" "$data_dir/config" "$data_dir/plugins"
    chmod 755 "$data_dir" "$data_dir/qq" "$data_dir/config" "$data_dir/plugins" 2>/dev/null || true
  fi
}

check_required_env() {
  local mode="$1"
  local keys=(DB_URL DB_USERNAME DB_PASSWORD REDIS_HOST REDIS_PORT REDIS_PASSWORD
              UPSTREAM_CREDENTIAL_KEY MONITOR_ADMIN_USERNAME MONITOR_ADMIN_PASSWORD)
  if [[ "$mode" == "full" ]]; then
    keys+=(POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD POSTGRES_PORT)
  fi
  if napcat_enabled; then
    keys+=(MONITOR_BOT_WEBHOOK_TOKEN)
  fi
  local key value
  for key in "${keys[@]}"; do
    value="$(env_get "$key")"
    [[ -n "$value" ]] || die ".env 缺少必填项：$key"
  done
  if [[ "$mode" == "external" ]]; then
    case "$(env_get DB_URL)" in
      *YOUR_DB_HOST*|*CHANGE_ME*) die "external 模式请先在 .env 填写真实 DB_URL/DB_PASSWORD" ;;
    esac
    case "$(env_get REDIS_HOST)" in
      *YOUR_REDIS_HOST*|*CHANGE_ME*) die "external 模式请先在 .env 填写真实 REDIS_HOST/REDIS_PASSWORD" ;;
    esac
  fi
}

wait_health() {
  local status="unknown" i
  for i in $(seq 1 60); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$CONTAINER_NAME" 2>/dev/null || echo missing)"
    case "$status" in
      healthy|none) log "容器状态：$status"; return 0 ;;
      unhealthy)
        docker logs --tail 80 "$CONTAINER_NAME" 2>&1 || true
        return 1
        ;;
    esac
    sleep 3
  done
  docker logs --tail 80 "$CONTAINER_NAME" 2>&1 || true
  die "等待健康检查超时：$status"
}

rand_hex() {
  local bytes="$1"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$bytes"
  else
    od -An -N "$bytes" -tx1 /dev/urandom | tr -d ' \n'
  fi
}

rand_base64() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 32 | tr -d '\n'
  else
    head -c 32 /dev/urandom | base64 | tr -d '\n'
  fi
}

init_env() {
  local mode="$1" with_napcat="$2"
  write_mode "$mode"
  set_napcat_flag "$with_napcat"
  if [[ -f "$ENV_FILE" ]]; then
    log ".env 已存在，跳过初始化；只更新部署模式"
    return 0
  fi
  [[ -f "$EXAMPLE_FILE" ]] || die "缺少 .env.example"
  umask 077
  cp "$EXAMPLE_FILE" "$ENV_FILE"
  chmod 600 "$ENV_FILE"

  local postgres_password redis_password admin_password upstream_key webhook_token
  postgres_password="$(rand_hex 24)"
  redis_password="$(rand_hex 24)"
  admin_password="$(rand_hex 12)"
  upstream_key="$(rand_base64)"
  webhook_token="$(rand_hex 24)"

  set_env_value POSTGRES_PASSWORD "$postgres_password"
  set_env_value REDIS_PASSWORD "$redis_password"
  set_env_value UPSTREAM_CREDENTIAL_KEY "$upstream_key"
  set_env_value MONITOR_ADMIN_USERNAME "admin"
  set_env_value MONITOR_ADMIN_PASSWORD "$admin_password"
  set_env_value MONITOR_BOT_WEBHOOK_TOKEN "$webhook_token"
  set_env_value MONITOR_IMAGE_GEN_ENABLED "false"
  set_env_value MONITOR_IMAGE_GEN_BASE_URL ""

  if [[ "$mode" == "full" ]]; then
    set_env_value POSTGRES_DB "monitor"
    set_env_value POSTGRES_USER "monitor"
    set_env_value POSTGRES_PORT "5432"
    set_env_value DB_URL "jdbc:postgresql://postgres:5432/monitor"
    set_env_value DB_USERNAME "monitor"
    set_env_value DB_PASSWORD "$postgres_password"
    set_env_value REDIS_HOST "redis"
    set_env_value REDIS_PORT "6379"
  else
    set_env_value DB_URL "jdbc:postgresql://YOUR_DB_HOST:5432/monitor"
    set_env_value DB_USERNAME "monitor"
    set_env_value DB_PASSWORD "CHANGE_ME"
    set_env_value REDIS_HOST "YOUR_REDIS_HOST"
    set_env_value REDIS_PORT "6379"
    set_env_value REDIS_PASSWORD "CHANGE_ME"
  fi

  if [[ "$with_napcat" == "1" ]]; then
    set_env_value MONITOR_BOT_ENABLED "true"
    set_env_value MONITOR_NAPCAT_ENABLED "true"
    set_env_value NAPCAT_DATA_DIR "./napcat"
    set_env_value NAPCAT_ACCOUNT ""
  else
    set_env_value MONITOR_BOT_ENABLED "false"
    set_env_value MONITOR_NAPCAT_ENABLED "false"
  fi

  log "已生成 .env"
  log "管理员账号：$(env_get MONITOR_ADMIN_USERNAME)"
  log "管理员初始密码：$admin_password"
  if [[ "$mode" == "external" ]]; then
    warn "external 模式请先编辑 .env 中的 DB_URL / DB_USERNAME / DB_PASSWORD / REDIS_HOST / REDIS_PASSWORD，再运行 install"
  fi
}

cmd_init() {
  local mode="$1" with_napcat="$2"
  init_env "$mode" "$with_napcat"
  log "模式：$mode；NapCat：$([[ "$with_napcat" == "1" ]] && echo 开启 || echo 关闭)"
}

cmd_install() {
  local mode="$1" with_napcat="$2"
  if [[ ! -f "$ENV_FILE" && "$mode" == "external" ]]; then
    die "external 模式需要已有 .env。请先复制 .env.example 并填好外部 DB/Redis，或改用默认 full 模式"
  fi
  init_env "$mode" "$with_napcat"
  ensure_dirs "$mode"
  check_required_env "$mode"
  log "拉取 app 镜像：$IMAGE_REPO:latest"
  compose "$mode" pull app
  if napcat_enabled; then
    if docker inspect napcat >/dev/null 2>&1; then
      log "NapCat 容器已存在，跳过镜像拉取（避免意外更新登录态）"
    else
      log "拉取 NapCat 镜像"
      compose "$mode" pull napcat
    fi
  fi
  local services=(app)
  if [[ "$mode" == "full" ]]; then services+=(postgres redis); fi
  if napcat_enabled; then services+=(napcat); fi
  local joined
  joined="$(printf '%s ' "${services[@]}")"
  log "启动服务：${joined% }"
  compose "$mode" up -d --remove-orphans "${services[@]}"
  wait_health
  local port
  port="$(env_get APP_PORT)"
  [[ -n "$port" ]] || port="8080"
  log "部署完成：http://<服务器IP>:$port"
  log "管理员账号：$(env_get MONITOR_ADMIN_USERNAME)"
}

cmd_update() {
  require_env_file
  local mode
  mode="$(read_mode)"
  ensure_dirs "$mode"
  check_required_env "$mode"
  mkdir -p "$DEPLOY_DIR"
  if docker inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
    docker inspect -f '{{.Image}}' "$CONTAINER_NAME" > "$DEPLOY_DIR/last-image-id"
    chmod 600 "$DEPLOY_DIR/last-image-id" 2>/dev/null || true
    log "已记录当前镜像 ID：$(cat "$DEPLOY_DIR/last-image-id")"
  fi
  log "拉取 app 镜像：$IMAGE_REPO:latest"
  compose "$mode" pull app
  log "重建 app 容器"
  compose "$mode" up -d --force-recreate --no-build app
  wait_health
  log "更新完成"
}

cmd_rollback() {
  require_env_file
  local mode id
  mode="$(read_mode)"
  [[ -f "$DEPLOY_DIR/last-image-id" ]] || die "没有 .deploy/last-image-id，无法回滚"
  id="$(tr -d '\r\n' < "$DEPLOY_DIR/last-image-id")"
  docker image inspect "$id" >/dev/null 2>&1 || die "回滚镜像不存在：$id"
  docker tag "$id" "${IMAGE_REPO}:rollback"
  log "回滚到镜像：$id"
  MONITOR_IMAGE="${IMAGE_REPO}:rollback" compose "$mode" up -d --force-recreate --no-build app
  wait_health
}

cmd_status() {
  require_env_file
  local mode
  mode="$(read_mode)"
  compose "$mode" ps
  if docker inspect "$CONTAINER_NAME" >/dev/null 2>&1; then
    local status
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$CONTAINER_NAME")"
    log "health=$status"
  else
    warn "容器 $CONTAINER_NAME 不存在"
  fi
}

cmd_logs() {
  require_env_file
  local mode
  mode="$(read_mode)"
  compose "$mode" logs -f --tail=200 app
}

cmd_env_check() {
  require_env_file
  [[ -f "$EXAMPLE_FILE" ]] || die "缺少 .env.example"
  local mode tmp_example tmp_env missing extra
  mode="$(read_mode)"
  tmp_example="$(mktemp)"
  tmp_env="$(mktemp)"
  file_keys "$EXAMPLE_FILE" > "$tmp_example"
  file_keys "$ENV_FILE" > "$tmp_env"
  if [[ "$mode" == "external" ]]; then
    grep -v '^POSTGRES_' "$tmp_example" > "$tmp_example.filtered" || true
    mv "$tmp_example.filtered" "$tmp_example"
  fi
  missing="$(comm -23 "$tmp_example" "$tmp_env" || true)"
  extra="$(comm -13 "$tmp_example" "$tmp_env" || true)"
  local legacy
  for legacy in $LEGACY_KEYS; do
    extra="$(printf '%s\n' "$extra" | grep -vx "$legacy" || true)"
  done
  rm -f "$tmp_example" "$tmp_env"
  log "模式：$mode；.env key 数：$(file_keys "$ENV_FILE" | wc -l | tr -d ' ')"
  if [[ -n "$missing" ]]; then
    warn "缺少 key："
    printf '%s\n' "$missing" | sed 's/^/  - /' >&2
  else
    log "没有缺少的 key"
  fi
  if [[ -n "$extra" ]]; then
    warn "模板中没有的 key（可能是历史项）："
    printf '%s\n' "$extra" | sed 's/^/  - /' >&2
  fi
  if env_has MONITOR_IMAGE; then
    warn "检测到 MONITOR_IMAGE。正常部署建议删除该项，使用默认 latest"
  fi
  [[ -z "$missing" ]]
}

cmd_env_fix() {
  require_env_file
  [[ -f "$EXAMPLE_FILE" ]] || die "缺少 .env.example"
  local mode backup added key line tmp_example tmp_env missing
  mode="$(read_mode)"
  tmp_example="$(mktemp)"
  tmp_env="$(mktemp)"
  file_keys "$EXAMPLE_FILE" > "$tmp_example"
  file_keys "$ENV_FILE" > "$tmp_env"
  if [[ "$mode" == "external" ]]; then
    grep -v '^POSTGRES_' "$tmp_example" > "$tmp_example.filtered" || true
    mv "$tmp_example.filtered" "$tmp_example"
  fi
  missing="$(comm -23 "$tmp_example" "$tmp_env" || true)"
  rm -f "$tmp_example" "$tmp_env"
  if [[ -z "$missing" ]]; then
    log "没有需要补齐的 key"
    return 0
  fi
  backup="${ENV_FILE}.bak.$(date +%Y%m%d%H%M%S)"
  cp "$ENV_FILE" "$backup"
  chmod 600 "$backup" 2>/dev/null || true
  added=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    key="${line%%=*}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    if [[ "$mode" == "external" && "$key" == POSTGRES_* ]]; then continue; fi
    if ! env_has "$key"; then
      printf '%s\n' "$line" >> "$ENV_FILE"
      log "补齐 $key"
      added=$((added + 1))
      case "$line" in
        *change-me*|*YOUR_*|*your-*|*replace-with*) warn "  $key 使用了模板占位值，请检查" ;;
      esac
    fi
  done < "$EXAMPLE_FILE"
  chmod 600 "$ENV_FILE"
  log "已补齐 $added 个 key；备份：$backup"
}

cmd_doctor() {
  require_docker
  [[ -f "$ENV_FILE" ]] || die "缺少 .env"
  local mode
  mode="$(read_mode)"
  log "模式：$mode"
  log "Docker：$(docker --version)"
  log "Compose：$(docker compose version --short 2>/dev/null || echo unknown)"
  if ! cmd_env_check; then
    warn "环境变量检查未通过；可运行 ./scripts/deploy.sh env-fix 补齐缺失 key"
  fi
  local file
  file="$(compose_file "$mode")"
  [[ -f "$file" ]] || die "缺少 compose 文件：$file"
  compose "$mode" config >/dev/null && log "compose 配置校验通过"
  if napcat_enabled; then
    local data_dir
    data_dir="$(env_get NAPCAT_DATA_DIR)"
    [[ -n "$data_dir" ]] || data_dir="./napcat"
    case "$data_dir" in /*) ;; *) data_dir="$ROOT_DIR/$data_dir" ;; esac
    [[ -d "$data_dir/config" ]] && log "NapCat 配置目录：$data_dir/config" || warn "NapCat 配置目录不存在：$data_dir/config"
  fi
  df -h "$ROOT_DIR" | tail -1
  if command -v free >/dev/null 2>&1; then free -m | sed -n '1,2p'; fi
}

parse_opts() {
  OPT_MODE=""
  OPT_WITH_NAPCAT=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --mode) [[ $# -ge 2 ]] || die "--mode 需要 full 或 external"; OPT_MODE="$2"; shift 2 ;;
      --mode=*) OPT_MODE="${1#*=}"; shift ;;
      --with-napcat) OPT_WITH_NAPCAT="1"; shift ;;
      --without-napcat) OPT_WITH_NAPCAT="0"; shift ;;
      -h|--help) usage; exit 0 ;;
      *) die "未知参数：$1" ;;
    esac
  done
  if [[ -n "$OPT_MODE" ]]; then
    case "$OPT_MODE" in full|external) ;; *) die "--mode 只能是 full 或 external" ;; esac
  fi
}

main() {
  local cmd="${1:-help}"
  [[ $# -gt 0 ]] && shift
  case "$cmd" in
    help|-h|--help) usage ;;
    init)
      parse_opts "$@"
      local mode="${OPT_MODE:-full}"
      local with="${OPT_WITH_NAPCAT:-0}"
      cmd_init "$mode" "$with"
      ;;
    install)
      parse_opts "$@"
      local mode="${OPT_MODE:-$(read_mode)}"
      local with="${OPT_WITH_NAPCAT:-$(napcat_int)}"
      cmd_install "$mode" "$with"
      ;;
    update) parse_opts "$@"; cmd_update ;;
    rollback) parse_opts "$@"; cmd_rollback ;;
    status) parse_opts "$@"; cmd_status ;;
    logs) parse_opts "$@"; cmd_logs ;;
    env-check) parse_opts "$@"; cmd_env_check ;;
    env-fix) parse_opts "$@"; cmd_env_fix ;;
    doctor) parse_opts "$@"; cmd_doctor ;;
    *) usage; die "未知命令：$cmd" ;;
  esac
}

main "$@"
