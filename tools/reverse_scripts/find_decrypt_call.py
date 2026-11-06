import re

with open(r"c:\Users\a3991\Documents\my_pro\mybus.js", "r", encoding="utf-8") as f:
    code = f.read()

matches = re.findall(r'(\b(?:strDec|decrypt|strEnc|enc)\b[^\n;]+)', code)
print("Found encryption calls in mybus.js:", len(matches))
for m in matches[:10]:
    print(" ->", m[:120])

