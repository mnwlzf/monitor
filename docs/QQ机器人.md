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
# 1) 起 NapCat（已包含在主 compose.yaml 里）
docker compose up -d

# 2) 从日志里拿 WebUI 的登录 token
docker compose logs napcat

# 3) 本机执行 SSH 隧道（WebUI 只绑在服务器的 127.0.0.1）
ssh -L 6099:127.0.0.1:6099 root@<服务器IP>

# 4) 浏览器打开 WebUI，扫码登录 QQ（建议用**小号**：自建机器人有风控/封号风险）
#    http://127.0.0.1:6099/webui?token=<上一步日志里的 token>
```

> 登录态与配置直接挂在宿主机 `/app/napcat/` 下（`qq` / `config` / `plugins` 三个子目录），
> 重启容器不用重新扫码。**NapCat 侧不需要 `.env`**：端口与目录都写在 `compose.yaml` 的 napcat 服务里，部署目录不同就改那三行 volume。

登录成功后，在 WebUI 的「网络配置」里开两样：

| 类型 | 作用 | 配置 |
|---|---|---|
| **HTTP 服务端** | monitor 通过它把回复发出去 | 容器内监听 `0.0.0.0:3000`（NapCat 侧固定），compose 映射到宿主机 `127.0.0.1:5000` |
| **HTTP 客户端（反向 HTTP 上报）** | NapCat 把收到的消息推给 monitor | URL `http://monitor:8080/api/v1/bot/onebot`，Header `Authorization: Bearer <MONITOR_BOT_WEBHOOK_TOKEN>` |

### 两个容器怎么互访

`napcat` 与 `app` 在**同一个 compose 文件**里，天然处于同一个网络（项目名 `monitor` → 网络 `monitor_default`），
所以直接用**容器名 + 容器内端口**互访，不经过宿主机端口：

| 方向 | 地址 |
|---|---|
| app → napcat（发消息） | `http://napcat:3000`（`MONITOR_BOT_API_BASE_URL`） |
| napcat → app（反向 HTTP 上报） | `http://monitor:8080/api/v1/bot/onebot` |

> ⚠️ 不要用 `host.docker.internal` 走宿主机端口：NapCat 的端口只绑在 `127.0.0.1`，
> 容器从网卡 IP 访问回环端口是连不通的。
>
> 宿主机端口被占用（`Bind for 127.0.0.1:xxxx failed: port is already allocated`）时：
> `docker ps` / `ss -lntp | grep <端口>` 找出占用者；或直接换端口 ——
> 改 `compose.yaml` 里 napcat 的 `ports` 映射（宿主机侧），容器内始终是 3000。

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
| `/我的信息` | 查看当前绑定的邮箱 |
| `/绑定 <邮箱>` | 绑定邮箱（也可由 `<QQ号>@qq.com` 自动匹配，无需绑定） |
| `/解绑` | 解除绑定 |

命令前缀可用 `MONITOR_BOT_COMMAND_PREFIX` 改。**这些命令不依赖大模型。**

### 长回复会自动转成图片

渠道状态、账户余额、号池明细这类内容动辄上百行，QQ 文本消息放不下，会被
`回复最大长度`（默认 900 字）截断。超过这个长度的回复会**渲染成 PNG 图片发送**，
内容完整可见：

- 标题加粗，`- ` 列表项渲染成圆点列表，左侧有强调色条，右下角带生成时间
- 图片宽度可用 `MONITOR_BOT_IMAGE_WIDTH` 调整（默认 760px），高度按内容自适应
- 内容过长（超过 120 行）时图片本身会截断，并标注「内容过长，图片已截断」

渲染依赖**中文字体**：JRE 自带字体不含中文，官方镜像已装 `fonts-wqy-microhei`。
如果用自建镜像，请自行安装中文字体，或用 `MONITOR_BOT_IMAGE_FONT` 指定字体名
（例如 `Noto Sans CJK SC`）。

> 字体探测不到时不会报错，而是**自动退回截断文本**，并在启动日志里给出提示：
> `未找到可显示中文的字体，长回复将退回截断文本。`
> 也就是说「发不出图片」不会导致机器人不说话，只是内容仍会被截断。

不需要这个功能时把 `MONITOR_BOT_IMAGE_ENABLED` 设为 `false` 即可。

## 4. 身份识别与管理员（谁能查平台数据）

机器人**按邮箱**判断发送者是谁，进而决定用哪套人设 —— 这直接关系到平台数据会不会泄露。

```
QQ 消息 ──► QQ 号 ──┬─► 显式绑定（/绑定 <邮箱>）      ──► 邮箱
                    └─► 自动匹配（<QQ号>@qq.com）    ──► 邮箱
                                     │
                                     ▼
                 邮箱是否在「平台用户」名单里？（Redis 缓存）
                          │                        │
                         是                        否
                          ▼                        ▼
              监控助手（平台用户）            普通聊天机器人
              ├─ 管理员：全部只读查询工具      └─ 无工具、提示词禁止提及平台，
              └─ 普通用户：无平台工具，             平台级命令一律回「未知命令」
                 问到平台数据只回「仅管理员可查」
```

### 数据来源

平台用户邮箱来自自建 Sub2API 的只读库 `public.users`（只取 `email` / `role` / `status`，
过滤掉软删除），由定时任务 `sub2api-user-sync` 默认每 10 分钟同步到 Redis。

只读账号需要额外授权：

```sql
GRANT SELECT ON public.users TO monitor_ro;
```

> 未配置只读库或未授权时，任务每轮直接跳过，机器人退化为「所有人都是普通聊天」——
> 不会报错，也不会误判成平台用户。

### 管理员：两个来源取并集

Sub2API 只允许**一个**管理员账号，实际运维往往需要多人分担，因此管理员有两个来源：

| 来源 | 说明 |
|---|---|
| Sub2API 自带 | `public.users.role = 'admin'` 的用户 |
| 自定义名单 | 在**「系统设置」页 → 身份识别与管理员**卡片里登记的邮箱，保存在监控项目自己的库里 |

**任一命中即为管理员**。自定义管理员即使不是 Sub2API 用户，也能以监控助手（管理员）身份使用机器人。
页面同时展示 Sub2API 侧的缓存人数、管理员数与最近同步时间，并提供「立即同步用户」按钮。

### 三种人设的差异

| 身份 | 人设 | 平台查询工具 | 平台级命令 |
|---|---|---|---|
| 管理员 | 监控助手 | ✅ 全部 | ✅ 可用 |
| 普通用户 | 监控助手 | ❌ 不挂载 | ❌ 提示「仅管理员可查」 |
| 陌生人 | 普通聊天 | ❌ 不挂载 | ❌ 回「未知命令」，不暴露功能存在 |

> 对**非平台用户**，机器人绝不会出现任何平台相关信息：没有工具、系统提示词明令禁止提及平台，
> 连 `/help` 都只列出绑定与解绑。这是刻意的硬约束。

### 一个刻意的取舍

`/绑定` 的回复与邮箱是否命中平台用户**完全无关**（统一回「已记录你的邮箱。」），
否则任何人都能靠反复绑定邮箱、观察回复差异来枚举出哪些邮箱是平台用户。

代价是：绑定了一个并非平台用户的邮箱时，用户不会收到任何提示，只会继续以普通聊天身份对话。
## 5. 白名单与行为参数（页面上配置）

`允许的群号`、`允许的私聊 QQ`、`命令前缀`、`回复最大长度`、`会话记忆条数`、`群里是否需 @机器人`
都在 **「系统设置」页 → QQ 机器人** 卡片里维护，存在数据库 `bot_settings` 单行表里，
**保存后立即生效，不需要重建容器**。

> `MONITOR_BOT_ALLOWED_GROUPS` / `MONITOR_BOT_ALLOWED_USERS` 等环境变量只在**首次启动**时用来
> 初始化这一行（老部署平滑过渡）；之后一律以页面上的为准。

仍然留在环境变量里的只有「部署级接线参数」，因为它们要和 NapCat 侧保持一致：

| 环境变量 | 作用 |
|---|---|
| `MONITOR_BOT_ENABLED` | 总开关：false 时回调接口直接 404 |
| `MONITOR_BOT_WEBHOOK_TOKEN` | 与 NapCat 上报共用的密钥（用于校验 X-Signature） |
| `MONITOR_BOT_API_BASE_URL` | OneBot HTTP API 地址，默认 `http://napcat:3000` |
| `MONITOR_BOT_API_TOKEN` | OneBot HTTP API 的 access token，一般留空 |

## 6. 开启自然语言问答（可选）

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

## 7. 安全

- 回调接口 `/api/v1/bot/onebot` 没有登录会话，**完全依赖共享密钥**：
  未配置 `MONITOR_BOT_WEBHOOK_TOKEN` 时直接返回 404，密钥不匹配返回 403。
- 建议同时配置 `MONITOR_BOT_ALLOWED_GROUPS` / `MONITOR_BOT_ALLOWED_USERS`，避免被拉进陌生群乱问。
- 机器人只读：暴露给模型的工具全部是查询接口，没有写操作。

## 8. 排查

| 现象 | 排查方向 |
|---|---|
| 机器人没反应 | 看本服务日志有没有收到 `POST /api/v1/bot/onebot`；没有就是 OneBot 上报地址/网络不通 |
| 日志 403 | 两边 `Authorization` 与 `MONITOR_BOT_WEBHOOK_TOKEN` 不一致 |
| 日志 404 | `MONITOR_BOT_ENABLED` 不是 true，或密钥没配 |
| 群里不回、私聊回 | `MONITOR_BOT_REQUIRE_MENTION=true` 时需要先 @机器人 |
| 回复「处理这条消息时出错了」 | 大概率是 OneBot 的 HTTP API 地址或 token 配错，看同一时间的 WARN 日志 |