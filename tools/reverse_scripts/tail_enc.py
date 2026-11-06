with open(r"c:\Users\a3991\Documents\my_pro\encrypt-decrypt.js", "r", encoding="utf-8") as f:
    lines = f.readlines()

print("Total lines in encrypt-decrypt.js:", len(lines))
for i in range(max(0, len(lines) - 50), len(lines)):
    print(f"{i}: {lines[i].rstrip()}")

