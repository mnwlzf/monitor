# Monitor · 上游用量监控平台

聚合监控 **New API** / **Sub2API** 等上游站点的账号余额、用量、分组与 API Key 变化，
提供 Web 仪表盘、号池缓存率分析、邮件告警与 QQ 机器人查询。

单体部署：一个镜像同时提供前端页面与后端 API，前端静态资源在构建期打进 Jar 的 `static/` 目录。

---

## 功能

- **多上游账号监控**：余额、额度、已用额度、请求数、模型分布
- **变更追踪**：上游分组与 API Key 的新增/变更/停用，带快照可回溯
- **号池监控**：缓存率、请求量、模型维度统计；支持直连自建 Sub2API 只读库做秒级增量采集
- **邮件告警**：余额不足、API Key 变更、每日余额报表，收件人可在页面配置
- **QQ 机器人**：OneBot v11 协议（NapCat / Lagrange / go-cqhttp），支持 `/` 命令与自然语言问答
- **登录鉴权**：ADMIN 可读写，VIEWER 只读

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 3.5 · MyBatis-Plus · Flyway · PostgreSQL · Redis |
| 前端 | Vue 3 · TypeScript · Vite · Element Plus · ECharts |
| 机器人 | OneBot v11 · Spring AI（可选大模型） |
| 部署 | Docker 多阶段构建 · Docker Compose · GitHub Actions → GHCR |

## 目录结构

```
monitor/
├── backend/                 # Spring Boot 后端
│   ├── src/main/java/com/monitor/platform/
│   │   ├── adapter/         # 上游适配器（newapi / sub2api）
│   │   ├── api/             # 对外 HTTP 接口（controller / dto / service）
│   │   ├── bot/             # QQ 机器人（OneBot）
│   │   ├── collector/       # 采集调度与数据落库
│   │   ├── common/          # 公共契约：异常、响应、工具、定时任务抽象
│   │   ├── config/          # 全局配置（Security / Redis / RestClient 等）
│   │   ├── infrastructure/  # 基础设施（MyBatis TypeHandler 等）
│   │   ├── mail/            # 邮件与告警
│   │   ├── pool/            # 号池监控与增量采集
│   │   └── scheduler/       # 动态定时任务
│   ├── src/main/resources/
│   │   ├── db/migration/    # Flyway 迁移脚本
│   │   └── mapper/          # MyBatis XML
│   └── src/test/            # 单元测试
├── frontend/                # Vue 3 前端
│   └── src/{api,components,views}
├── config/                  # 外挂配置（application.yml 挂载到容器 /app/config）
├── deploy/                  # 部署脚本（update-monitor.sh）
├── docs/                    # 设计文档
├── scripts/                 # 本地开发辅助脚本（PowerShell）
├── compose.yaml             # 一体化部署：app + postgres + redis + 可选 napcat
├── compose.external.yaml    # 仅 app，数据库/Redis 使用外部已有服务
└── Dockerfile               # 多阶段构建：前端 → 后端 → 运行镜像
```

## 快速开始

### 前置要求

- **Docker 部署**：Docker 24+ 与 `docker compose` 插件
- **本地开发**：JDK 21、Maven 3.6+（建议 3.9+）、Node.js 22+（`./scripts/check-environment.ps1` 可自动检查）

### 方式一：Docker 一键启动（推荐）

```bash
cp .env.example .env      # 按需修改数据库/Redis 密码、管理员账号等
docker compose up -d --build
```

启动后访问 <http://localhost:8080>，管理员账号见 `.env` 中的 `MONITOR_ADMIN_USERNAME` / `MONITOR_ADMIN_PASSWORD`。

两份 compose 文件的区别：

| 文件 | 适用场景 | 启动的服务 |
| --- | --- | --- |
| `compose.yaml` | 单机一体化，自带数据库与缓存 | `app` + `postgres` + `redis`（+ 可选 `napcat`） |
| `compose.external.yaml` | 已有 PostgreSQL / Redis，只想跑应用 | 仅 `app` |

只用内置基础设施、不启动 QQ 机器人：

```bash
docker compose up -d app postgres redis
```

### 方式二：本地开发

后端与前端分开运行，前端 Vite 会把 `/api`、`/actuator` 代理到 `http://127.0.0.1:8080`。

```powershell
# 1) 先起依赖（PostgreSQL / Redis）
docker compose up -d postgres redis

# 2) 启动后端（另开一个终端）
./scripts/start-backend.ps1

# 3) 启动前端（再开一个终端）
./scripts/start-frontend.ps1
```

- 后端接口：<http://127.0.0.1:8080>
- 前端开发地址：<http://127.0.0.1:5173>

> **注意**：仓库根目录的 `.env` 是给容器化部署用的，其中 `DB_URL` / `REDIS_HOST` 默认是
> compose 服务名（`postgres` / `redis`），宿主机无法解析。本地直跑时请改成 `localhost`，
> 或用环境变量临时覆盖：`$env:DB_URL = 'jdbc:postgresql://localhost:5432/monitor'`。
> `start-backend.ps1` 会在检测到该情况时给出提示。

## 环境变量

[.env.example](.env.example) 是环境变量的**唯一事实来源**，复制为 `.env` 后按需修改。按用途分组：

| 分组 | 主要变量 | 说明 |
| --- | --- | --- |
| 应用 | `TZ` `APP_PORT` `JAVA_OPTS` | 时区、对外端口、JVM 参数 |
| 数据库 | `POSTGRES_*` `DB_URL` `DB_USERNAME` `DB_PASSWORD` | 内置容器初始化用 `POSTGRES_*`；应用实际连接用 `DB_*` |
| Redis | `REDIS_HOST` `REDIS_PORT` `REDIS_PASSWORD` | 用于缓存上游登录凭证 |
| 凭证加密 | `UPSTREAM_CREDENTIAL_KEY` | Base64 编码的 32 字节密钥，`openssl rand -base64 32` 生成，**务必妥善保存** |
| 登录鉴权 | `MONITOR_ADMIN_USERNAME` `MONITOR_ADMIN_PASSWORD` `MONITOR_SECURITY_ENABLED` `MONITOR_SESSION_*` | 管理员账号与会话配置 |
| 号池增量采集 | `SUB2API_DB_*` | 可选，直连自建 Sub2API 只读库，建议用仅 `SELECT` 权限的账号 |
| QQ 机器人 | `MONITOR_BOT_*` | 可选，默认关闭 |
| 身份识别 | `MONITOR_BOT_IDENTITY_*` | 可选；把 Sub2API 平台用户邮箱同步到 Redis，用于区分平台用户与陌生人 |
| 大模型 | `SPRING_AI_*` | 可选，默认 `none`，不配 Key 也能正常启动 |

## 常用命令

```bash
# 后端
cd backend
mvn test                 # 运行单元测试
mvn spring-boot:run      # 本地启动（需先备好 DB / Redis）

# 前端
cd frontend
npm ci
npm run dev              # 开发服务器
npm run typecheck        # 类型检查
npm run build            # 生产构建
```

## 测试

单元测试不依赖外部服务，可直接执行：

```bash
cd backend && mvn test
cd frontend && npm run typecheck
```

标记为 `contract` 的联调用例需要**真实上游服务与数据库环境**，默认被 surefire 排除（见 `backend/pom.xml`），
需要时用专门的 profile 执行：

```bash
cd backend && mvn test -Pcontract-tests
```

## CI

| 工作流 | 触发 | 内容 |
| --- | --- | --- |
| [ci.yml](.github/workflows/ci.yml) | PR / push 到 `main` | 后端 `mvn test`、前端 `npm run typecheck` + `npm run build` |
| [docker-publish.yml](.github/workflows/docker-publish.yml) | push 到 `main` / `v*` tag | 构建并推送镜像到 GHCR |

## 部署与更新

服务端部署目录通常包含 `.env`、`compose.external.yaml` 与更新脚本，脚本源码在
[deploy/update-monitor.sh](deploy/update-monitor.sh)，需要与 `.env` 放在同一目录（脚本会切到自身所在目录执行）：

```bash
./update-monitor.sh                   # 拉取最新镜像并重建容器
./update-monitor.sh --tag v1.0.0      # 使用指定标签
./update-monitor.sh --build           # 本地重新构建镜像
./update-monitor.sh --rollback        # 回滚到上一次更新前的镜像
```

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/数据库设计.md](docs/数据库设计.md) | 表结构与字段说明 |
| [docs/QQ机器人.md](docs/QQ机器人.md) | 机器人部署与命令说明 |
| [docs/项目结构设计建议.md](docs/项目结构设计建议.md) | 单体项目的模块划分思路 |

## 常见问题

**Q：容器起来了但页面 502 / 打不开？**
查看应用健康检查与日志：`docker compose logs -f app`。常见原因是 `.env` 里的 `DB_*` / `REDIS_*` 与实际服务地址不一致。

**Q：日志文件在哪？**
容器内 `/app/logs/monitor.log`，通过 `app-logs` 卷持久化。`deploy/update-monitor.sh` 会把宿主机 `logs/` 属主改为容器内 UID `10001`。

**Q：忘了 `UPSTREAM_CREDENTIAL_KEY`？**
该密钥用于解密已保存的上游账号密码，**丢失后已保存的凭证无法恢复**，需要重新录入账号。

**Q：不想用 QQ 机器人？**
保持 `MONITOR_BOT_ENABLED=false` 即可；`compose.yaml` 中的 `napcat` 服务可以删掉，或启动时只指定 `app postgres redis`。