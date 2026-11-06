import re

with open(r"c:\Users\a3991\Documents\my_pro\mybus.js", "r", encoding="utf-8") as f:
    code = f.read()

# 找出所有的 CMD 赋值及上下文
cmds = re.findall(r'CMD["\']?\s*:\s*["\']?([0-9a-zA-Z_]+)["\']?', code)
print("Unique CMDs:", sorted(set(cmds), key=lambda x: (len(x), x)))

# 打印每个 CMD 所在的函数名或上下文
lines = code.split('\n')
for idx, l in enumerate(lines):
    if '"CMD"' in l or "'CMD'" in l or 'CMD:' in l:
        context = " ".join([lines[max(0, idx - 15)].strip(), lines[max(0, idx - 10)].strip(), lines[max(0, idx - 5)].strip(), l.strip()])
        print(f"Line {idx}: {l.strip()} | Context: {context[:120]}")

