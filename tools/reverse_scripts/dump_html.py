import urllib.request
import ssl

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

req = urllib.request.Request('https://h5.mygolbs.com', headers={'User-Agent': 'Mozilla/5.0'})
html = urllib.request.urlopen(req, context=ctx, timeout=5).read().decode('utf-8', errors='ignore')

with open(r"c:\Users\a3991\Documents\my_pro\h5_index.html", "w", encoding="utf-8") as f:
    f.write(html)
print("Saved h5_index.html, length:", len(html))

