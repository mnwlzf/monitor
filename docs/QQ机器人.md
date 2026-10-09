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

## 1. 配置 OneBot 实现

以 NapCat / Lagrange 为例，在它的配置里开启「反向 HTTP 上报」（reverse HTTP / HTTP 上报）：

| 项 | 值 |
|---|---|
| 上报地址 | `http://<本服务地址>:8080/api/v1/bot/onebot` |
| Authorization | `Bearer <MONITOR_BOT_WEBHOOK_TOKEN>` |
| 消息格式 | `array`（默认即可；`string` 也兼容） |

同时记下它的 **HTTP API 地址**（例如 `http://127.0.0.1:3000`）和 access token，稍后填到本服务。

> 容器部署时注意网络：`MONITOR_BOT_API_BASE_URL` 是**本服务访问 OneBot** 的地址，
> 用 `host.docker.internal` 指向宿主机，或填 OneBot 容器的服务名。

## 2. 配置本服务

在 `.env` 里填：

```dotenv
MONITOR_BOT_ENABLED=true
MONITOR_BOT_WEBHOOK_TOKEN=<与 OneBot 侧一致>
MONITOR_BOT_API_BASE_URL=http://host.docker.internal:3000
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