with open(r"c:\Users\a3991\Documents\my_pro\probe_h5.py", "r") as f:
    pass

import urllib.request
import ssl
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

req = urllib.request.Request('https://h5.mygolbs.com', headers={'User-Agent': 'Mozilla/5.0'})
html = urllib.request.urlopen(req, context=ctx, timeout=5).read().decode('utf-8', errors='ignore')

# 搜索所有包含 script 的行
for idx, line in enumerate(html.split('\n')):
    if any(k in line for k in ['decrypt', 'strDec', 'strEnc', 'ajaxSetup', 'ajaxPrefilter']):
        print(f"Line {idx}: {line.strip()[:150]}")

