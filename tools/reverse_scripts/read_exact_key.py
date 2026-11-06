with open(r"c:\Users\a3991\Documents\my_pro\encrypt-decrypt.js", "r", encoding="utf-8") as f:
    code = f.read()

idx = code.find("strDec(data, 'sg'")
if idx != -1:
    print(code[max(0, idx - 200): min(len(code), idx + 300)])

