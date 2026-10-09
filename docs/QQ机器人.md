# QQ 机器人（OneBot + Spring AI）

不打开站点，直接在 QQ 里问「近 24 小时哪个号池账号缓存率最低」「平台余额还剩多少」，机器人查真实数据后回答。

## 架构

```
QQ 客户端 ──► NapCat / Lagrange / go-cqhttp（OneBot v11 实现）
                      │  反向 HTTP 上报（带 Authorization: Bearer <webhook-token>）
                      ▼
              POST /api/v1/bot/onebot
                      │
                      ├─ / 开头的命令  ──► 直接查库（不需要大模型）
                      └─ 自然语言      ──► Spring AI ChatClient ──► function calling ──► 同一批只读查询
                      │
                      ▼
              POST {api-base-url}/send_group_msg | /send_private_msg
```

所有查询都是**只读**的，机器人不会触发采集、删除等写操作。

## 1. 先跑一个 OneBot 实现（NapCat）

OneBot 只是**协议**，本身不是服务 —— 需要有个程序真的登录你的 QQ 账号。
个人开发者推荐 [NapCat](https://github.com/NapNeko/NapCatQQ)（官方 Docker 镜像 `mlikiowa/napcat-docker`）：

```bash
# 1) 起 NapCat（本仓库自带 compose.qqbot.yaml）
docker compose -f compose.qqbot.yaml up -d

# 2) 从日志里拿 WebUI 的登录 token
docker compose -f compose.qqbot.yaml logs napcat

# 3) 浏览器打开 WebUI，扫码登录 QQ
#    http://127.0.0.1:6099/webui
#    建议用**小号**：自建机器人有风控/封号风险
```

> 登录态与配置默认落在宿主机 `/app/napcat/`（`qq` / `config` / `plugins` 三个子目录），
> 重启容器不用重新扫码。仓库不在 `/app` 下时，在 `.env` 里设 `NAPCAT_DATA_DIR=/你的路径/napcat`。

登录成功后，在 WebUI 的「网络配置」里开两样：

| 类型 | 作用 | 配置 |
|---|---|---|
| **HTTP 服务端** | monitor 通过它把回复发出去 | 容器内监听 `0.0.0.0:3000`（NapCat 侧固定），compose 映射到宿主机 `127.0.0.1:5000` |
| **HTTP 客户端（反向 HTTP 上报）** | NapCat 把收到的消息推给 monitor | URL `http://monitor:8080/api/v1/bot/onebot`，Header `Authorization: Bearer <MONITOR_BOT_WEBHOOK_TOKEN>` |

### 两个容器怎么互访

monitor 和 napcat 是两套独立的 compose（比如 `/app/monitor` 与 `/app/napcat`），
`compose.qqbot.yaml` 里已经把 napcat 挂到 **monitor 项目的默认网络** `monitor_default` 上，
所以两边直接用**容器名 + 容器内端口**互访，不经过宿主机端口：

| 方向 | 地址 |
|---|---|
| monitor → napcat（发消息） | `http://napcat:3000`（`MONITOR_BOT_API_BASE_URL`） |
| napcat → monitor（反向 HTTP 上报） | `http://monitor:8080/api/v1/bot/onebot` |

> ⚠️ 顺序：**先起 monitor**（`docker compose -f compose.yaml up -d`，它会创建 `monitor_default` 网络），
> 再起 napcat。反过来会报 `network monitor_default declared as external, but could not be found`。
>
> ⚠️ 不要用 `host.docker.internal` 走宿主机端口：NapCat 的端口只绑在 `127.0.0.1`，
> 容器从网卡 IP 访问回环端口是连不通的。
>
> 宿主机端口被占用（`Bind for 127.0.0.1:xxxx failed: port is already allocated`）时：
> `docker ps` / `ss -lntp | grep <端口>` 找出占用者；或直接换端口 ——
> 在 `.env` 里设 `NAPCAT_HTTP_PORT=5000`（宿主机侧），并同步改 `MONITOR_BOT_API_BASE_URL`。
> 容器内始终是 3000，不用动 NapCat 里的监听端口。

其它可选实现：Lagrange.Core、LLOneBot。`go-cqhttp` 已停更，不建议再用。

> 不想自建 QQ 客户端，可以走 **QQ 官方机器人开放平台**（不用挂 QQ、不怕封号），
> 但要主体资质与审核，协议也不同（Webhook + AppID/Secret），需要另外加一层适配。

## 2. 配置本服务

在 `.env` 里填：

```dotenv
MONITOR_BOT_ENABLED=true
MONITOR_BOT_WEBHOOK_TOKEN=<与 OneBot 侧一致>
MONITOR_BOT_API_BASE_URL=http://napcat:3000
MONITOR_BOT_API_TOKEN=<OneBot 的 access token，没设置就留空>

# 白名单：逗号分隔的群号 / QQ，留空表示不限制（建议显式配置）
MONITOR_BOT_ALLOWED_GROUPS=123456789
MONITOR_BOT_ALLOWED_USERS=987654321

# 群里是否需要 @机器人 才响应
MONITOR_BOT_REQUIRE_MENTION=true
```

重启后日志会出现 `QQ 机器人已接入大模型，支持自然语言问答` 或 `未配置大模型，QQ 机器人只支持命令`。

## 3. 使用

私聊机器人，或在群里 `@机器人` 后发送：

| 命令 | 作用 |
|---|---|
| `/help` | 用法 |
| `/平台` | 上游平台概览（账号数、异常数、余额合计） |
| `/号池 [时间窗]` | 号池账号的请求数、缓存率、首 Token，时间窗 `90m/1h/6h/12h/24h/7d/30d/90d`，默认 `24h` |
| `/采集` | 号池直连库增量采集状态（游标位置、数据滞后） |
| `/账号 <关键字>` | 按名称模糊查账号余额与状态 |
| `/变更 [条数]` | 最近的账号 / 密钥变更记录 |

命令前缀可用 `MONITOR_BOT_COMMAND_PREFIX` 改。**这些命令不依赖大模型。**

## 4. 开启自然语言问答（可选）

默认 `SPRING_AI_MODEL_CHAT=none`，不配 Key 也能正常启动。要开启对话：

```dotenv
SPRING_AI_MODEL_CHAT=openai
SPRING_AI_OPENAI_API_KEY=sk-...
SPRING_AI_OPENAI_BASE_URL=https://api.openai.com   # 兼容 OpenAI 协议的服务改这里
SPRING_AI_OPENAI_MODEL=gpt-4o-mini
```

之后就可以直接说人话，模型会通过 function calling 调用同一批只读查询：

- 近 7 天哪个号池账号缓存率最低？
- 帮我看看有没有账号余额快用完了
- 号池的数据是不是延迟了？

每个会话保留最近 10 条消息（`MONITOR_BOT_MEMORY_WINDOW`），支持追问「那 30 天呢？」。

## 5. 安全

- 回调接口 `/api/v1/bot/onebot` 没有登录会话，**完全依赖共享密钥**：
  未配置 `MONITOR_BOT_WEBHOOK_TOKEN` 时直接返回 404，密钥不匹配返回 403。
- 建议同时配置 `MONITOR_BOT_ALLOWED_GROUPS` / `MONITOR_BOT_ALLOWED_USERS`，避免被拉进陌生群乱问。
- 机器人只读：暴露给模型的工具全部是查询接口，没有写操作。

## 6. 排查

| 现象 | 排查方向 |
|---|---|
| 机器人没反应 | 看本服务日志有没有收到 `POST /api/v1/bot/onebot`；没有就是 OneBot 上报地址/网络不通 |
| 日志 403 | 两边 `Authorization` 与 `MONITOR_BOT_WEBHOOK_TOKEN` 不一致 |
| 日志 404 | `MONITOR_BOT_ENABLED` 不是 true，或密钥没配 |
| 群里不回、私聊回 | `MONITOR_BOT_REQUIRE_MENTION=true` 时需要先 @机器人 |
| 回复「处理这条消息时出错了」 | 大概率是 OneBot 的 HTTP API 地址或 token 配错，看同一时间的 WARN 日志 |