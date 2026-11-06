import urllib.request, urllib.parse, ssl, json

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

params = {
    'CMD': '104',
    'CITYNAME': '兴义市',
    'LINENAME': '1路',
    'DIRECTION': '1', # 1 或 0
    'STATIONORDER': '1'
}

data = urllib.parse.urlencode(params).encode('utf-8')
req = urllib.request.Request('https://h5.mygolbs.com/ApiData.do', data=data, headers={
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
    'Origin': 'https://h5.mygolbs.com',
    'Referer': 'https://h5.mygolbs.com/',
    'X-Requested-With': 'XMLHttpRequest'
})

res = urllib.request.urlopen(req, context=ctx, timeout=8)
content = res.read().decode('utf-8', errors='ignore')
print("Response for Xingyi line 1 real-time (CMD 104):")
try:
    obj = json.loads(content)
    print(json.dumps(obj, ensure_ascii=False, indent=2)[:800])
except Exception as e:
    print(content[:500])

