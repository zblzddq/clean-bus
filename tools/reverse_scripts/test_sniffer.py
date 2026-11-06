import urllib.request, ssl, re, json

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

def test_sniffer():
    print("[1] Fetching h5.mygolbs.com HTML...")
    req = urllib.request.Request('https://h5.mygolbs.com', headers={'User-Agent': 'Mozilla/5.0'})
    html = urllib.request.urlopen(req, context=ctx, timeout=6).read().decode('utf-8', errors='ignore')
    
    # 查找 mybus.js 和 encrypt-decrypt.js
    scripts = re.findall(r'src=["\']?([^"\' >]+\.js[^"\' >]*)', html)
    mybus_url = None
    enc_url = None
    for s in scripts:
        if 'mybus.js' in s:
            mybus_url = s if s.startswith('http') else 'https://h5.mygolbs.com/' + s.lstrip('/')
        if 'encrypt-decrypt.js' in s:
            enc_url = s if s.startswith('http') else 'https://h5.mygolbs.com/' + s.lstrip('/')
    
    print("Found mybus URL:", mybus_url)
    print("Found enc URL:", enc_url)

    # 提取 apiroot
    apiroot = "ApiData.do"
    if mybus_url:
        js = urllib.request.urlopen(urllib.request.Request(mybus_url, headers={'User-Agent': 'Mozilla/5.0'}), context=ctx, timeout=6).read().decode('utf-8', errors='ignore')
        m = re.search(r'apiroot\s*=\s*["\']([^"\']+)["\']', js)
        if m:
            apiroot = m.group(1)
            print("Successfully extracted apiroot:", apiroot)

    # 提取 strDec 密钥
    keys = ['sg', 'h5', 'jm']
    if enc_url:
        enc_js = urllib.request.urlopen(urllib.request.Request(enc_url, headers={'User-Agent': 'Mozilla/5.0'}), context=ctx, timeout=6).read().decode('utf-8', errors='ignore')
        m_dec = re.search(r'strDec\s*\(\s*data\s*,\s*[\'"]([^\'"]+)[\'"]\s*,\s*[\'"]([^\'"]+)[\'"]\s*,\s*[\'"]([^\'"]+)[\'"]\s*\)', enc_js)
        if m_dec:
            keys = [m_dec.group(1), m_dec.group(2), m_dec.group(3)]
            print("Successfully extracted 3DES keys:", keys)

    # 冒烟测试
    full_api_url = f"https://h5.mygolbs.com/{apiroot.lstrip('/')}"
    print(f"[2] Smoke testing extracted endpoint: {full_api_url}")
    post_data = urllib.parse.urlencode({'CMD': '102', 'CITYNAME': '兴义市', 'KEYWORD': '1', 'CITYKEY': ''}).encode('utf-8')
    req_post = urllib.request.Request(full_api_url, data=post_data, headers={
        'User-Agent': 'Mozilla/5.0',
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'Origin': 'https://h5.mygolbs.com',
        'Referer': 'https://h5.mygolbs.com/',
        'X-Requested-With': 'XMLHttpRequest'
    })
    res_post = urllib.request.urlopen(req_post, context=ctx, timeout=6)
    obj = json.loads(res_post.read().decode('utf-8'))
    lines = obj.get('buslines', [])
    print(f"Smoke test passed! Lines found: {len(lines)}")
    return True

if __name__ == '__main__':
    test_sniffer()

