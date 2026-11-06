import re

with open(r"c:\Users\a3991\Documents\my_pro\mybus.js", "r", encoding="utf-8") as f:
    mybus_code = f.read()

with open(r"c:\Users\a3991\Documents\my_pro\encrypt-decrypt.js", "r", encoding="utf-8") as f:
    enc_code = f.read()

print("--- API Endpoints in mybus.js ---")
urls = set(re.findall(r'https?://[a-zA-Z0-9\.\-_/:]+|/WxBusServer/[a-zA-Z0-9\.\-_/]+', mybus_code))
for u in sorted(urls):
    if any(k in u for k in ['Bus', 'bus', 'line', 'station', 'city', 'api', 'Wx']):
        print("  *", u)

print("\n--- Encryption functions in encrypt-decrypt.js ---")
funcs = set(re.findall(r'function\s+([a-zA-Z0-9_$]+)\s*\(', enc_code))
print("Functions:", funcs)
keys = re.findall(r'["\']([a-zA-Z0-9]{16,32})["\']', enc_code)
print("Potential Keys:", set(keys))

