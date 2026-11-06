with open(r"c:\Users\a3991\Documents\my_pro\encrypt-decrypt.js", "r", encoding="utf-8") as f:
    lines = f.readlines()

for i, l in enumerate(lines):
    if 'function strDec' in l:
        print("".join(lines[i:i+60]))
        break

