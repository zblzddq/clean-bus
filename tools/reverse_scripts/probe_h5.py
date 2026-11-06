import urllib.request
import ssl
import re

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

headers = {'User-Agent': 'Mozilla/5.0'}
req = urllib.request.Request('https://h5.mygolbs.com', headers=headers)
html = urllib.request.urlopen(req, context=ctx, timeout=5).read().decode('utf-8', errors='ignore')

scripts = re.findall(r'src=["\']?([^"\' >]+)', html)
print("Scripts found in h5.mygolbs.com:")
for s in scripts:
    print(" -", s)

