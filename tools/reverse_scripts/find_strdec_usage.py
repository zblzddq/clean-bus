import urllib.request
import ssl
import re

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

req = urllib.request.Request('https://h5.mygolbs.com', headers={'User-Agent': 'Mozilla/5.0'})
html = urllib.request.urlopen(req, context=ctx, timeout=5).read().decode('utf-8', errors='ignore')

# 找出所有的外链 script 并搜索 strDec
scripts = re.findall(r'src=["\']?([^"\' >]+\.js[^"\' >]*)', html)
for s in scripts:
    url = s if s.startswith('http') else 'https://h5.mygolbs.com/' + s.lstrip('/')
    try:
        js = urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'}), context=ctx, timeout=5).read().decode('utf-8', errors='ignore')
        if 'strDec' in js:
            print("Found 'strDec' in:", url)
            for m in re.finditer(r'strDec\s*\([^)]+\)', js):
                print("   call:", m.group(0))
        if 'ajaxSetup' in js:
            print("Found 'ajaxSetup' in:", url)
    except Exception as e:
        print("Failed to check", url, e)

