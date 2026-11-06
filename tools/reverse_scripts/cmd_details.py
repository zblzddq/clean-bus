with open(r"c:\Users\a3991\Documents\my_pro\mybus.js", "r", encoding="utf-8") as f:
    lines = f.readlines()

cmd_lines = [
    (314, "106"),
    (632, "115"),
    (898, "114"),
    (1012, "110"),
    (1134, "102"),
    (1296, "119"),
    (2544, "103"),
    (3516, "120"),
    (3896, "104"),
    (5222, "105"),
    (6042, "111"),
    (6232, "118"),
    (6584, "112"),
    (7932, "113"),
    (8834, "101"),
]

for l_no, cmd in cmd_lines:
    # 向上寻找 function 关键字
    func_name = "unknown"
    for j in range(l_no, max(0, l_no - 40), -1):
        if 'function' in lines[j]:
            func_name = lines[j].strip()
            break
    # 打印请求数据
    data_params = []
    for k in range(l_no - 10, min(len(lines), l_no + 15)):
        if ':' in lines[k] and not lines[k].strip().startswith('//'):
            data_params.append(lines[k].strip())
    print(f"CMD {cmd} | Func: {func_name[:50]} | Params: {', '.join(data_params[:6])}")

