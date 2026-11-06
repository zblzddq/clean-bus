import urllib.request, urllib.parse, ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

data = urllib.parse.urlencode({'CMD': '101'}).encode('utf-8')
req = urllib.request.Request('https://h5.mygolbs.com/ApiData.do', data=data, headers={
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36',
    'Referer': 'https://h5.mygolbs.com/',
    'X-Requested-With': 'XMLHttpRequest',
    'Accept': 'application/json, text/javascript, */*; q=0.01'
})
res = urllib.request.urlopen(req, context=ctx, timeout=8)
print("Status:", res.status)
print("Headers:")
for k, v in res.headers.items():
    print(f"  {k}: {v}")
body = res.read()
print("Body len:", len(body))
print("Raw Body:", body)

