const fs = require('fs');

const encCode = fs.readFileSync('c:\\Users\\a3991\\Documents\\my_pro\\encrypt-decrypt.js', 'utf-8');

// 执行 encrypt-decrypt.js 定义的函数
eval(encCode);

// 测试刚刚从 ApiData.do CMD 101 返回的加密串
const testCipher = "0vx0czK73ykwCHaLBTiKh0iCXQINzRvSZw4+b/H8FgE52U=";
console.log("Cipher:", testCipher);

try {
    const decrypted = decrypt(testCipher);
    console.log("Decrypted result:", decrypted);
} catch (e) {
    console.error("Decryption error:", e);
}

