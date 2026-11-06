# 运维手册（CleanBus）

> 目标：**上游断供时，10 分钟内发现并恢复**，而不是等用户反馈"查不到车"。

## 0. 前提（一次性）

Worker 侧需要三个 Secret / 绑定，缺一不可：

| 名称 | 类型 | 用途 |
|---|---|---|
| `RULES_SIGNING_KEY` | wrangler secret（PKCS#8 Base64） | ECDSA P-256 规则签名，缺失时 `/api/v1/rules` 直接 503 |
| `ADMIN_TOKEN` | wrangler secret | `triggerSniff` 与 `admin/rules` 的 Bearer 鉴权 |
| `RULES_KV` | KV namespace 绑定 | 规则持久化 + 健康上报计数 |

```bash
wrangler secret put RULES_SIGNING_KEY
wrangler secret put ADMIN_TOKEN
wrangler kv namespace create RULES_KV   # 把返回的 id 填进 wrangler.toml
wrangler deploy
```

私钥只存在于 Secret 中；客户端内置的是公钥（`DynamicRuleManager.PUBLIC_RULE_KEY_SPKI_B64`），公开无害。
**换私钥 = 换公钥 = 必须发版**，不要随手轮换。

## 1. 日常巡检（一条命令）

```bash
curl -s https://cleanbus.277011444.workers.dev/api/v1/status
```

关键字段：

```json
{
  "ruleVersion": 2026110602,
  "keyVerified": false,
  "sources": {
    "chelaile": { "version": 101, "host": "https://web.chelaile.net.cn/api",
                  "keyVerified": false,
                  "health": { "count": 12, "lastAt": "2026-11-06T13:19:53Z", "lastKind": "decrypt-fail" } },
    "mygolbs":  { "version": 101, "host": "https://h5.mygolbs.com/ApiData.do",
                  "keyVerified": true, "health": null }
  }
}
```

判读规则：

| 现象 | 含义 | 处置 |
|---|---|---|
| `health.count` 在涨，`lastKind=decrypt-fail` | 车来了密钥变了（最可能） | 走 §2 轮换 |
| `lastKind=http-error` 或 `timeout` | 上游封 IP / 网络问题 | 先等 30 分钟，仍涨再查 Worker 出口 |
| `keyVerified=false` 但 `health=null` | 从未验活成功过（明文上游），属正常待命态 | 不用处理 |
| `health.count` 长时间不动 | 已恢复或没人用 | 对比 `lastAt` 时间 |

客户端侧：同一源 10 分钟内连续失败 3 次才会报一次，且每源每小时最多 1 次，所以 `count` 增长是"真挂"的强信号，抖动不会误报。

## 2. 车来了密钥轮换（人工，约 30 秒生效）

车来了的小程序 AES 密钥**不在任何可匿名嗅探的 Web 资源里**（H5 bundle 里的是另一把 `4225...`，与小程序端不通用；`servicewechat.com` 的包匿名拉不到）。
所以这一源只能人工热更，靠 Cloudflare 下发 + 客户端验签，无需发版。

```bash
curl -X POST https://cleanbus.277011444.workers.dev/api/v1/admin/rules \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "chelaile": {
      "version": 102,
      "baseUrl": "https://web.chelaile.net.cn/api",
      "salt": "<新 salt>",
      "aesKey": "<新 AES key>"
    }
  }'
```

约束（服务端会校验，违反返回 400）：

- `version` 必须**严格大于**当前值，否则拒绝
- `baseUrl` 必须 https 且 host ∈ `web.chelaile.net.cn` / `h5.mygolbs.com` / `cleanbus.277011444.workers.dev`
- 下发后 Worker 会用同一个私钥重新签名，客户端验签通过才落地

**回滚**：把旧值带上 `version: 当前+1` 再发一次即可，服务端不区分"新/旧"，只认版本号递增。

## 3. 掌上公交（自动）

`sniffChelaile`/`sniffMyGoLbs` 每 30 分钟由 cron 触发，规则从 `h5.mygolbs.com` 的 bundle 里正则提取，并用提取到的密钥**真实解密**一次响应才判 `keyVerified=true`。

手动触发：

```bash
curl -X POST https://cleanbus.277011444.workers.dev/api/v1/triggerSniff \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

同一时刻 5 分钟内只允许一次（429 + `Retry-After`），防止把配额和上游风控打穿。

## 4. 客户端失败上报协议

```
POST /api/v1/report
{"source":"chelaile"|"mygolbs", "kind":"decrypt-fail"|"parse-fail"|"empty"|"http-error"|"timeout"}
```

- 无需鉴权，但按 `CF-Connecting-IP` 限流 1 条/分钟
- 只上报计数与类型，不上报线路、坐标、设备 ID
- 服务端聚合为 `health:<source>`，通过 `/api/v1/status` 暴露

## 5. 已知限制（别再去试）

1. **车来了密钥无法自动嗅探**——2026-11-06 实测：H5 bundle 有 salt 与 H5 专用 AES key，小程序 key 全 Web 零命中；微信 CDN 匿名 404。
2. **H5 协议复用不可行**——签名算法已从 bundle 还原（`md5(JSON.stringify(params)去括号,:/=,,/& + salt)`），但按此构造的请求一律 `status:400`，缺请求头或会话参数，需要真实浏览器抓包才能补齐，收益不抵成本。
3. **APK 签名是 debug keystore**——`build_clean_bus.ps1` 在未提供 `CLEANBUS_KS` 时会退化到 debug 签名；正式分发前必须配置 release keystore，否则用户无法覆盖升级。
4. **无 CI**——编译与签名验证目前靠本地脚本 `build_clean_bus.ps1` 与人工复核。

## 6. 断供告警（GitHub Issue → 邮件）

Worker cron 每 30 分钟在嗅探之后检查健康计数，命中阈值自动开 issue。

### 6.1 配置

```bash
# 1) 给 Worker 一个只能建 issue 的 token（建议 fine-grained: issues:write）
wrangler secret put GH_ALERT_TOKEN

# 2) wrangler.toml 增加变量
[vars]
GH_REPO = "zblzddq/clean-bus"
ALERT_THRESHOLD = "5"

wrangler deploy
```

### 6.2 邮件如何到 277011444@qq.com

Cloudflare Worker 不能直接发 SMTP，所以走 **GitHub Issue → GitHub 通知邮件**：

1. GitHub → Settings → Emails 添加 `277011444@qq.com` 并完成验证
2. 设为 Primary 或在 Notifications 里勾选该地址接收通知
3. 确认 Watching 了 `zblzddq/clean-bus`

### 6.3 演练（上线后必做一次）

```bash
curl -X POST https://cleanbus.277011444.workers.dev/api/v1/triggerAlert \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

- 首次调用只建基线，不发 issue（避免历史计数误报）
- 去重：同一源 6 小时内最多一条

### 6.4 告警内容

标题 `[CleanBus] 车来了疑似断供（新增 N 次失败）`，正文含源、最后失败类型与时间、累计次数，
以及可直接复制的轮换 curl。处理后查 `GET /api/v1/status` 看计数是否停止增长。

### 6.5 为什么改由 GitHub Actions 建 issue（重要）

实测结论：**用你自己的 PAT 建 issue 不会收到邮件**——GitHub 不给「自己创建的内容」发通知，
即使仓库 Watch 是 All Activity、Default notifications email 已设为 QQ 邮箱。

因此告警改由 `.github/workflows/alert.yml` 执行（每 30 分钟）：

- 以 `github-actions[bot]` 身份建 issue → actor 不是你 → 邮件正常送达
- 同源已有未关闭的 `ops-alert` issue 时不会重复开
- 恢复后会在现存 issue 下评论，提醒你确认关闭
- 不需要 `GH_ALERT_TOKEN`；Worker 侧的 `checkAndAlert()` 在缺 token 时会自动跳过，可保留作为备用

演练：

```bash
gh workflow run alert.yml            # 手动跑一次
gh run watch                         # 看日志
```

若 `/api/v1/status` 未部署（返回旧格式，无 `sources` 字段），工作流会安全跳过，不误报。
