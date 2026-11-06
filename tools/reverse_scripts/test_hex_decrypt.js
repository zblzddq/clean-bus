const fs = require('fs');

const encCode = fs.readFileSync('c:\\Users\\a3991\\Documents\\my_pro\\encrypt-decrypt.js', 'utf-8');
eval(encCode);

const base64Str = "0vx0czK73ykwCHaLBTiKh0iCXQINzRvSZw4+b/H8FgE52U=";
// 转为 Buffer 然后转为 Hex
const hexStr = Buffer.from(base64Str, 'base64').toString('hex');
console.log("Hex Str:", hexStr);

try {
    const res = decrypt(hexStr);
    console.log("Decrypted from Hex:", res);
} catch (e) {
    console.error(e);
}

