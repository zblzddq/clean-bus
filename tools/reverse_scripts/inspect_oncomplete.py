with open(r"c:\Users\a3991\Documents\my_pro\mybus.js", "r", encoding="utf-8") as f:
    lines = f.readlines()

for i in range(130, 180):
    if i < len(lines):
        print(f"{i}: {lines[i].rstrip()}")

