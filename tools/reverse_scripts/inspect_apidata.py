with open(r"c:\Users\a3991\Documents\my_pro\mybus.js", "r", encoding="utf-8") as f:
    lines = f.readlines()

print("=== Lines 110 - 140 ===")
for i in range(110, 140):
    if i < len(lines):
        print(f"{i}: {lines[i].rstrip()}")

print("\n=== Lines 295 - 335 ===")
for i in range(295, 335):
    if i < len(lines):
        print(f"{i}: {lines[i].rstrip()}")

