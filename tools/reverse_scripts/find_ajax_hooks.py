with open(r"c:\Users\a3991\Documents\my_pro\mybus.js", "r", encoding="utf-8") as f:
    code = f.read()

import re
matches = re.findall(r'ajax[A-Za-z]+|\bdataFilter\b|\bconverters\b', code)
print("Ajax hooks in mybus.js:", set(matches))

with open(r"c:\Users\a3991\Documents\my_pro\encrypt-decrypt.js", "r", encoding="utf-8") as f:
    enc_code = f.read()

matches2 = re.findall(r'ajax[A-Za-z]+|\bdataFilter\b|\bconverters\b', enc_code)
print("Ajax hooks in encrypt-decrypt.js:", set(matches2))

