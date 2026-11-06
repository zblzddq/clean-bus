import os
import urllib.request
import urllib.parse
import json

ACCOUNT_ID = os.environ.get("CF_ACCOUNT_ID", "")
API_TOKEN = os.environ.get("CF_API_TOKEN", "")
SCRIPT_NAME = "cleanbus"

if not ACCOUNT_ID or not API_TOKEN:
    print("Error: Please set environment variables CF_ACCOUNT_ID and CF_API_TOKEN before running deployment.")
    exit(1)

worker_path = os.path.join(os.path.dirname(__file__), "clean_bus_worker.js")
with open(worker_path, "r", encoding="utf-8") as f:
    worker_code = f.read()

# 1. 上传 Worker 脚本 (ESM 模块格式)
print(f"[1/3] Uploading worker script '{SCRIPT_NAME}' to Cloudflare...")
boundary = "----CloudflareWorkerBoundaryXYZ123"
body_parts = []

# Part 1: metadata
metadata = {
    "main_module": "clean_bus_worker.js",
    "compatibility_date": "2024-03-01",
    "compatibility_flags": ["nodejs_compat"]
}
body_parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n{json.dumps(metadata)}\r\n")

# Part 2: script
body_parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"clean_bus_worker.js\"; filename=\"clean_bus_worker.js\"\r\nContent-Type: application/javascript+module\r\n\r\n{worker_code}\r\n")
body_parts.append(f"--{boundary}--\r\n")

full_body = "".join(body_parts).encode('utf-8')

upload_url = f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/workers/scripts/{SCRIPT_NAME}"
req = urllib.request.Request(upload_url, data=full_body, method="PUT", headers={
    "Authorization": f"Bearer {API_TOKEN}",
    "Content-Type": f"multipart/form-data; boundary={boundary}"
})

try:
    res = urllib.request.urlopen(req)
    res_data = json.loads(res.read().decode('utf-8'))
    print("Script upload success:", res_data.get("success"))
except Exception as e:
    print("Script upload failed:", e)
    if hasattr(e, 'read'):
        print(e.read().decode('utf-8'))
    exit(1)

# 2. 启用 workers.dev 路由
print(f"[2/3] Enabling workers.dev subdomain route...")
subdomain_url = f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/workers/scripts/{SCRIPT_NAME}/subdomain"
subdomain_body = json.dumps({"enabled": True}).encode('utf-8')
req_sub = urllib.request.Request(subdomain_url, data=subdomain_body, method="POST", headers={
    "Authorization": f"Bearer {API_TOKEN}",
    "Content-Type": "application/json"
})
try:
    res_sub = urllib.request.urlopen(req_sub)
    print("Subdomain enabled:", json.loads(res_sub.read().decode('utf-8')).get("success"))
except Exception as e:
    print("Subdomain config error:", e)
    if hasattr(e, 'read'):
        print(e.read().decode('utf-8'))

# 3. 绑定 30 分钟 Cron 触发器
print(f"[3/3] Setting 30-minute Cron schedule (*/30 * * * *)...")
cron_url = f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/workers/scripts/{SCRIPT_NAME}/schedules"
cron_body = json.dumps([{"cron": "*/30 * * * *"}]).encode('utf-8')
req_cron = urllib.request.Request(cron_url, data=cron_body, method="PUT", headers={
    "Authorization": f"Bearer {API_TOKEN}",
    "Content-Type": "application/json"
})
try:
    res_cron = urllib.request.urlopen(req_cron)
    print("Cron schedule success:", json.loads(res_cron.read().decode('utf-8')).get("success"))
except Exception as e:
    print("Cron config error:", e)
    if hasattr(e, 'read'):
        print(e.read().decode('utf-8'))

print("\n>>> DEPLOYMENT FINISHED! Target domain: https://cleanbus.277011444.workers.dev")

