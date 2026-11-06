import urllib.request
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

headers = {'User-Agent': 'Mozilla/5.0'}

for name in ['js/mybus.js?version=1.2.2', 'js/encrypt-decrypt.js?version=1.0.1']:
    url = f'https://h5.mygolbs.com/{name}'
    print("=== Downloading", url, "===")
    try:
        content = urllib.request.urlopen(urllib.request.Request(url, headers=headers), context=ctx, timeout=5).read().decode('utf-8', errors='ignore')
        with open(f"c:\\Users\\a3991\\Documents\\my_pro\\{name.split('/')[1].split('?')[0]}", 'w', encoding='utf-8') as f:
            f.write(content)
        print("Success! Size:", len(content))
    except Exception as e:
        print("Error:", e)

