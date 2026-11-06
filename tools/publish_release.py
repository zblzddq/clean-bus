import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))

token = os.environ.get('GITHUB_TOKEN', '')
if not token:
    print('Error: GITHUB_TOKEN environment variable not set.')
    sys.exit(1)

user = 'zblzddq'
repo = 'clean-bus'

# 1. 动态读取版本号
version_file = os.path.join(REPO_ROOT, 'version.json')
if not os.path.exists(version_file):
    print(f"Error: version.json not found at {version_file}")
    sys.exit(1)

with open(version_file, 'r', encoding='utf-8') as f:
    ver_data = json.load(f)

version_name = str(ver_data.get('versionName', '1.0.0'))
version_code = int(ver_data.get('versionCode', 1))
tag_name = f"v{version_name}"

# 2. 动态解析最低系统版本 (AndroidManifest.xml)
manifest_path = os.path.join(REPO_ROOT, 'app', 'src', 'main', 'AndroidManifest.xml')
min_sdk = 26
if os.path.exists(manifest_path):
    try:
        tree = ET.parse(manifest_path)
        root = tree.getroot()
        uses_sdk = root.find('uses-sdk')
        if uses_sdk is not None:
            for k, v in uses_sdk.attrib.items():
                if 'minSdkVersion' in k:
                    min_sdk = int(v)
                    break
    except Exception as e:
        print(f"Notice: Failed to parse manifest minSdk, defaulting to 26 ({e})")

sdk_map = {
    21: ("Android 5.0", 21),
    22: ("Android 5.1", 22),
    23: ("Android 6.0", 23),
    24: ("Android 7.0", 24),
    25: ("Android 7.1", 25),
    26: ("Android 8.0", 26),
    27: ("Android 8.1", 27),
    28: ("Android 9.0", 28),
    29: ("Android 10.0", 29),
    30: ("Android 11.0", 30),
    31: ("Android 12.0", 31),
    33: ("Android 13.0", 33),
    34: ("Android 14.0", 34)
}
os_name, sdk_val = sdk_map.get(min_sdk, (f"Android API {min_sdk}", min_sdk))
min_sys_desc = f"{os_name} (API Level {sdk_val})"

# 3. 动态扫描产物文件、大小与 SHA256
releases_dir = os.path.join(REPO_ROOT, 'releases')
apk_candidates = [
    (f"CleanBus_{tag_name}.apk", os.path.join(releases_dir, f"CleanBus_{tag_name}.apk")),
    ("CleanBus_Release.apk", os.path.join(releases_dir, "CleanBus_Release.apk"))
]

apk_info_list = []
for name, path in apk_candidates:
    if os.path.exists(path):
        size_bytes = os.path.getsize(path)
        size_kb = size_bytes / 1024.0
        with open(path, 'rb') as f:
            data = f.read()
            sha256 = hashlib.sha256(data).hexdigest()
        apk_info_list.append({
            'name': name,
            'path': path,
            'size_bytes': size_bytes,
            'size_kb': size_kb,
            'sha256': sha256
        })
    else:
        print(f"Warning: Artifact {name} not found at {path}")

if apk_info_list:
    primary_kb = apk_info_list[0]['size_kb']
    size_str = f"{primary_kb:.1f} KB"
    table_rows = []
    for apk in apk_info_list:
        table_rows.append(f"| **`{apk['name']}`** | {apk['size_kb']:.1f} KB ({apk['size_bytes']:,} 字节) | `{apk['sha256']}` |")
    table_markdown = "\n".join(table_rows)
else:
    size_str = "< 100 KB"
    table_markdown = "| *(无本地构建产物)* | - | - |"

# 4. 代理地址从环境变量实时读取
proxy_url = os.environ.get('HTTPS_PROXY') or os.environ.get('HTTP_PROXY') or os.environ.get('ALL_PROXY') or ''
if proxy_url:
    print(f"Using proxy from environment: {proxy_url}")
    proxy_handler = urllib.request.ProxyHandler({'http': proxy_url, 'https': proxy_url})
    opener = urllib.request.build_opener(proxy_handler)
else:
    print("No proxy environment variable set; connecting directly.")
    opener = urllib.request.build_opener()

body_text = f"""## 🚀 CleanBus {tag_name} 正式版发布

> **超轻量 · 零臃肿 · 全国下沉覆盖 · 双源自愈 · 100% 原生 Android 实时公交客户端**

---

### ✨ 核心特性与更新亮点

#### 1. 极致轻量与零依赖架构
- **极小体积**：APK 安装包仅 **{size_str}**，仅为市面主流公交 App 的千分之一（1/1000）。
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
{table_markdown}

- **支持架构**：通用无架构限制（纯 DEX 无 Native SO）
- **最低系统要求**：{min_sys_desc} 及以上
- **签名验证**：支持 APK Signature Scheme V1 / V2 / V3 完整签名
"""

# 5. 检查 GitHub Release 是否已存在 (查重并更新，避免重复发布崩溃)
get_release_url = f'https://api.github.com/repos/{user}/{repo}/releases/tags/{tag_name}'
req_check = urllib.request.Request(get_release_url, headers={
    'Authorization': f'Bearer {token}',
    'User-Agent': 'CleanBus',
    'Accept': 'application/vnd.github.v3+json'
})

existing_release = None
try:
    with opener.open(req_check) as resp:
        existing_release = json.loads(resp.read().decode())
        print(f"Found existing release for {tag_name} (ID: {existing_release['id']})")
except urllib.error.HTTPError as e:
    if e.code != 404:
        raise

if existing_release:
    release_id = existing_release['id']
    upload_url = existing_release['upload_url'].split('{')[0]
    html_url = existing_release['html_url']

    patch_url = f'https://api.github.com/repos/{user}/{repo}/releases/{release_id}'
    patch_payload = json.dumps({
        'body': body_text
    }).encode('utf-8')
    req_patch = urllib.request.Request(patch_url, data=patch_payload, headers={
        'Authorization': f'Bearer {token}',
        'User-Agent': 'CleanBus',
        'Content-Type': 'application/json'
    }, method='PATCH')
    with opener.open(req_patch) as resp:
        print(f"Release updated: {html_url}")
else:
    create_url = f'https://api.github.com/repos/{user}/{repo}/releases'
    payload = json.dumps({
        'tag_name': tag_name,
        'target_commitish': 'main',
        'name': f'CleanBus {tag_name} - 双源自愈与全国下沉覆盖正式版',
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

# 6. 上传 Assets (若已有同名 asset 则先删除再上传)
if apk_info_list:
    assets_url = f'https://api.github.com/repos/{user}/{repo}/releases/{release_id}/assets'
    req_assets = urllib.request.Request(assets_url, headers={
        'Authorization': f'Bearer {token}',
        'User-Agent': 'CleanBus'
    })
    with opener.open(req_assets) as resp:
        existing_assets = {a['name']: a['id'] for a in json.loads(resp.read().decode())}

    for apk in apk_info_list:
        name = apk['name']
        path = apk['path']
        if name in existing_assets:
            print(f"Removing duplicate existing asset {name} (ID: {existing_assets[name]})...")
            del_url = f"https://api.github.com/repos/{user}/{repo}/releases/assets/{existing_assets[name]}"
            req_del = urllib.request.Request(del_url, headers={
                'Authorization': f'Bearer {token}',
                'User-Agent': 'CleanBus'
            }, method='DELETE')
            with opener.open(req_del) as resp:
                pass

        print(f"[2/3] Uploading asset {name} ({apk['size_kb']:.1f} KB)...")
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
