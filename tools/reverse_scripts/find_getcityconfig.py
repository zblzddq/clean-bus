with open(r"c:\Users\a3991\Documents\my_pro\mybus.js", "r", encoding="utf-8") as f:
    code = f.read()

lines = code.split('\n')
for idx, l in enumerate(lines):
    if 'function getCityConfig' in l:
        print("".join(lines[idx:idx+60]))
        break

