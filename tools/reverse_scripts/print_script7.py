with open(r"c:\Users\a3991\Documents\my_pro\h5_index.html", "r", encoding="utf-8") as f:
    html = f.read()

import re
scripts = re.findall(r'<script[^>]*>(.*?)</script>', html, re.DOTALL)
print(scripts[7])

