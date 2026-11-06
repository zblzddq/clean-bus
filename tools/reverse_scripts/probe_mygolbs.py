import urllib.request
import ssl
import re
import json

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

headers = {
    'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148'
}

req = urllib.request.Request('https://m.mygolbs.com', headers=headers)
html = urllib.request.urlopen(req, context=ctx, timeout=5).read().decode('utf-8', errors='ignore')

scripts = re.findall(r'src=["\']?([^"\' >]+)', html)
print("Scripts found in m.mygolbs.com:")
for s in scripts:
    print(" -", s)

# Fetch main JS
for s in scripts:
    if 'app' in s or 'main' in s or 'chunk-vendors' in s:
        url = s if s.startswith('http') else 'https://m.mygolbs.com/' + s.lstrip('/')
        try:
            print("Inspecting:", url)
            js = urllib.request.urlopen(urllib.request.Request(url, headers=headers), context=ctx, timeout=5).read().decode('utf-8', errors='ignore')
            # 搜索 API 关键字
            api_matches = re.findall(r'["\'](https?://[^"\']+|(?:/api/|/bus/|/city/)[^"\']+)["\']', js)
            print("API matches found:", len(api_matches))
            for m in set(api_matches):
                if any(k in m for k in ['bus', 'line', 'station', 'city', 'search', 'around']):
                    print("   ->", m[:120])
        except Exception as e:
            print("Failed:", e)

