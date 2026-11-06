import re

with open(r"c:\Users\a3991\Documents\my_pro\mybus.js", "r", encoding="utf-8") as f:
    code = f.read()

# 查找包含 ajax 的代码段
ajax_blocks = re.findall(r'(\$\.ajax\s*\(\{[^}]+\}\))', code)
print("Found ajax blocks:", len(ajax_blocks))
for b in ajax_blocks[:5]:
    print("--- AJAX BLOCK ---")
    print(b[:300])

# 查找 url: 相关的匹配
url_matches = re.findall(r'url\s*:\s*([^,\n]+)', code)
print("\n--- URL properties ---")
for u in set(url_matches[:20]):
    print("  url:", u.strip())

