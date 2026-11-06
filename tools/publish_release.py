import urllib.request
import json
import os

token = os.environ.get('GITHUB_TOKEN', '')
if not token:
    print('Error: GITHUB_TOKEN environment variable not set.')
    exit(1)
user = 'zblzddq'
repo = 'clean-bus'
tag_name = 'v1.0.13'

proxy_handler = urllib.request.ProxyHandler({'http': 'http://127.0.0.1:7897', 'https': 'http://127.0.0.1:7897'})
opener = urllib.request.build_opener(proxy_handler)

body_text = """## 🚀 CleanBus v1.0.13 正式版发布

> **超轻量 · 零臃肿 · 全国下沉覆盖 · 双源自愈 · 100% 原生 Android 实时公交客户端**

---

### ✨ 核心特性与更新亮点

#### 1. 极致轻量与零依赖架构
- **极小体积**：APK 安装包仅 **48.4 KB**，仅为市面主流公交 App 的千分之一（1/1000）。
- **零外部 SDK**：彻底摒弃臃肿的三方广告 SDK、分析统计库及重量级框架，100% 纯原生 Java 与 Android View 手写构建。
- **纯粹隐私保护**：无开屏广告、无弹窗推广、无后台常驻、不搜集任何设备唯一识别码。

#### 2. 双源互补与全国下沉深度覆盖
- **双引擎深度整合**：原生复刻并接入「车来了」与「掌上公交（MyGoLBS）」底层通讯协议，实现链式调度互补。
- **下沉市场广域覆盖**：全面补齐西藏拉萨、日喀则、贵州兴义/遵义/六盘水、四川宜宾等大量地州县级市的实时公交数据支持。
- **智能离线定位**：内置轻量全国城市坐标离线盒，配合原生 Geocoder 与 OSM 逆地理纠偏，离线秒级自动识别所在城市。

#### 3. 极速国内直连 + 云端网关免发版自愈
- **日常 80ms 极速查询**：国内用户查车默认直连国内优质 CDN 节点，杜绝海外跨境代理延迟。
- **Cloudflare 规则哨兵**：云端部署无人值守 Worker（每 30 分钟定时嗅探上游 H5 宿主），动态正则模式匹配提取上游最新接口路由与加密密钥，并通过动静分离冒烟测试验活。
- **客户端 A/B 槽容错回滚**：客户端启动静默比对规则版本，通过候选槽平滑装载；遇网络抖动或连续异常自动熔断回滚至内置稳定槽，保障 100% 高可用。

#### 4. 人性化乘车交互与调度预报
- **站点居中吸附**：点击线路上任意站点即时切换聚焦，自动居中展开到站预报卡片。
- **双向智能模糊换向**：智能模糊匹配对向对应站点，换向后精准锁定并平滑定位视口。
- **车辆偏离预警与接续调度**：自动识别车辆偏离轨迹并醒目告警，同时无缝接续下一辆正常营运车辆预报。

---

### 📦 安装包文件信息

| 文件名 | 大小 | SHA256 校验和 |
| :--- | :--- | :--- |
| **`CleanBus_v1.0.13.apk`** | 48.4 KB (49,560 字节) | `d94801136312986414970cb0b35f9958826870235932cb9a1a84358d5c6bdea4` |
| **`CleanBus_Release.apk`** | 48.4 KB (49,560 字节) | `d94801136312986414970cb0b35f9958826870235932cb9a1a84358d5c6bdea4` |

- **支持架构**：通用无架构限制（纯 DEX 无 Native SO）
- **最低系统要求**：Android 5.0 (API Level 21) 及以上
- **签名验证**：支持 APK Signature Scheme V1 / V2 / V3 完整签名
"""

# 1. 创建 Release
create_url = f'https://api.github.com/repos/{user}/{repo}/releases'
payload = json.dumps({
    'tag_name': tag_name,
    'target_commitish': 'main',
    'name': 'CleanBus v1.0.13 - 双源自愈与全国下沉覆盖首发版',
    'body': body_text,
    'draft': False,
    'prerelease': False
}).encode('utf-8')

req = urllib.request.Request(create_url, data=payload, headers={
    'Authorization': f'Bearer {token}',
    'User-Agent': 'CleanBus',
    'Content-Type': 'application/json'
})

print(f"[1/3] Creating Release {tag_name}...")
with opener.open(req) as resp:
    release_info = json.loads(resp.read().decode())
    release_id = release_info['id']
    upload_url = release_info['upload_url'].split('{')[0]
    html_url = release_info['html_url']
    print(f"Release created: {html_url} (ID: {release_id})")

# 2. 上传 Assets
apk_files = [
    ('CleanBus_v1.0.13.apk', r'c:\Users\a3991\Documents\my_pro\clean_bus_app\releases\CleanBus_v1.0.13.apk'),
    ('CleanBus_Release.apk', r'c:\Users\a3991\Documents\my_pro\clean_bus_app\releases\CleanBus_Release.apk')
]

for name, path in apk_files:
    print(f"[2/3] Uploading asset {name}...")
    with open(path, 'rb') as f:
        apk_data = f.read()

    asset_url = f'{upload_url}?name={name}'
    asset_req = urllib.request.Request(asset_url, data=apk_data, headers={
        'Authorization': f'Bearer {token}',
        'User-Agent': 'CleanBus',
        'Content-Type': 'application/vnd.android.package-archive'
    })
    with opener.open(asset_req) as asset_resp:
        asset_info = json.loads(asset_resp.read().decode())
        print(f"Asset uploaded: {asset_info.get('browser_download_url')}")

print("\nAll assets published successfully!")
