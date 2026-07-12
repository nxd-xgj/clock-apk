# clock-online 部署说明（Cloudflare Workers + Durable Objects）

免费、无需信用卡。统计「最近 90 秒内有心跳的设备数」= 实时在线人数。

## 方式一：用 Wrangler（推荐，一条命令）

1. 本机装 Node.js（18+）。
2. 安装 wrangler：
   ```
   npm install -g wrangler
   ```
3. 登录 Cloudflare（浏览器授权，不需要给我 token）：
   ```
   wrangler login
   ```
4. 进入本目录，部署：
   ```
   cd clock-online
   wrangler deploy
   ```
5. 终端会输出类似：
   ```
   https://clock-online.<你的子域>.workers.dev
   ```
   把这个 URL 发给纳西妲，她会填进 APK。

## 方式二：Cloudflare Dashboard（不用装 npm）

1. 登录 dash.cloudflare.com → Workers & Pages → Create → Worker，名称 `clock-online`，先 Deploy 一次（默认代码即可）。
2. 进入该 Worker → Settings → Variables → Durable Object bindings → Add binding：
   - Variable name: `COUNTER`
   - Class name: `OnlineCounter`（选 Create new，namespace 名随意）
   - Save。
3. 回到 Worker 的 Code 编辑器，把 `worker.js` 的全部内容粘贴进去，Save & Deploy。
4. 把生成的 URL 发给纳西妲。

## 接口约定（给 APK 用）

- 心跳 + 取数（推荐，一次搞定）：
  `GET {URL}/heartbeat?id=<设备UUID>`
  返回：`{"online": 3, "ts": 1700000000000}`

- 仅取数（不写）：
  `GET {URL}/count`
  返回同上。

在线人数 = 最近 90 秒内有心跳心跳的不同设备数。
