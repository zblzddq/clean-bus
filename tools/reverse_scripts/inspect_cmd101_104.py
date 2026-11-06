with open(r"c:\Users\a3991\Documents\my_pro\mybus.js", "r", encoding="utf-8") as f:
    lines = f.readlines()

print("=== CMD 101 (getCityList) ===")
for i in range(8820, 8860):
    if i < len(lines):
        print(f"{i}: {lines[i].rstrip()}")

print("\n=== CMD 104 (getRealTime) ===")
for i in range(3890, 3940):
    if i < len(lines):
        print(f"{i}: {lines[i].rstrip()}")

