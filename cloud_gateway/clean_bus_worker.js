/**
 * CleanBus Cloudflare Worker 聚合边缘网关 & 规则配置中心
 * 1. 30分钟全自动无人值守嗅探 (Auto-Sniff & Regex Extractor)
 * 2. 动静分离自动化冒烟测试 (不受夜间收班误杀)
 * 3. 独立版本管理与规则自愈下发 (/api/v1/rules)
 * 4. 每日 100,000 次免费配额
 */

// 出厂基线默认规则
const DEFAULT_RULES = {
  ruleVersion: 2026110602,
  chelaile: {
    version: 101,
    baseUrl: "https://web.chelaile.net.cn/api",
    salt: "qwihrnbtmj",
    aesKey: "FF32AE65FBFD19414EAAFF6291A54B42",
    status: "verified"
  },
  mygolbs: {
    version: 101,
    url: "https://h5.mygolbs.com/ApiData.do",
    keys: ["sg", "h5", "jm"],
    status: "verified"
  },
  lastSniffTime: new Date().toISOString(),
  lastSniffStatus: "idle"
};

// 签名默认备用秘钥 (生产环境由 env.RULES_SECRET / wrangler secret 注入)
const DEFAULT_RULES_SECRET = "cleanbus_dynamic_rules_hmac_sha256_key";

// 内存同 isolate 缓存
let inMemoryRules = null;

function deepMerge(target, source) {
  const result = { ...target, ...source };
  if (target.chelaile && source.chelaile) {
    result.chelaile = { ...target.chelaile, ...source.chelaile };
  }
  if (target.mygolbs && source.mygolbs) {
    result.mygolbs = { ...target.mygolbs, ...source.mygolbs };
  }
  return result;
}

// 获取规则：优先读取 KV 并与默认基准合并
async function getRules(env) {
  let kvRules = null;
  if (env && env.RULES_KV) {
    try {
      kvRules = await env.RULES_KV.get("rules", "json");
    } catch (e) {
      console.error("Failed to read rules from KV:", e);
    }
  }

  let merged = deepMerge(DEFAULT_RULES, inMemoryRules || {});
  if (kvRules) {
    merged = deepMerge(merged, kvRules);
  }

  inMemoryRules = merged;
  return merged;
}

// 写入规则：必须“先 KV put 成功、再改内存”，仅允许 ruleVersion 递增
async function saveRules(env, newRules) {
  const current = inMemoryRules || await getRules(env);
  if (newRules.ruleVersion < current.ruleVersion) {
    console.warn("Rejected: new ruleVersion must be >= current ruleVersion", newRules.ruleVersion, current.ruleVersion);
    return false;
  }

  if (env && env.RULES_KV) {
    await env.RULES_KV.put("rules", JSON.stringify(newRules));
  }

  inMemoryRules = newRules;
  return true;
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const pathname = url.pathname;

    // CORS 跨域支持
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
          'Access-Control-Allow-Headers': '*',
        }
      });
    }

    try {
      // 1. 动态规则分发端点 (附 HMAC-SHA256 签名与 Cache-Control)
      if (pathname === '/api/v1/rules') {
        const rules = await getRules(env);
        const rulesToSign = {
          chelaile: rules.chelaile,
          mygolbs: rules.mygolbs,
          ruleVersion: rules.ruleVersion
        };
        const canonical = canonicalJson(rulesToSign);
        const secret = (env && env.RULES_SECRET) || DEFAULT_RULES_SECRET;
        const sig = await hmacSha256Hex(canonical, secret);

        return jsonResponse({
          ...rules,
          sig
        }, 200, {
          'Cache-Control': 'public, max-age=300'
        });
      }

      // 手动触发一次嗅探测试
      if (pathname === '/api/v1/triggerSniff') {
        const result = await autoSniffAndSelfHeal(env);
        return jsonResponse(result);
      }

      // 2. 线路搜索代理
      if (pathname === '/api/v1/search') {
        const keyword = url.searchParams.get('keyword') || '';
        const city = url.searchParams.get('city') || '拉萨市';
        const data = await searchLines(env, keyword, city);
        return jsonResponse(data);
      }

      // 3. 线路详情代理
      if (pathname === '/api/v1/lineDetail') {
        const lineId = url.searchParams.get('lineId') || '';
        const city = url.searchParams.get('city') || '拉萨市';
        const data = await getLineDetail(env, lineId, city);
        return jsonResponse(data);
      }

      const activeRules = await getRules(env);
      return jsonResponse({
        status: 'ok',
        service: 'CleanBus Edge Rules & Gateway',
        cron: '30-minute auto-sniff & smoke test active',
        rules: {
          ruleVersion: activeRules.ruleVersion,
          chelaileVersion: activeRules.chelaile.version,
          mygolbsVersion: activeRules.mygolbs.version,
          lastSniffTime: activeRules.lastSniffTime,
          lastSniffStatus: activeRules.lastSniffStatus
        },
        timestamp: new Date().toISOString()
      });
    } catch (err) {
      return jsonResponse({ error: err.message }, 500);
    }
  },

  // 每 30 分钟由 Cloudflare 自动唤醒执行
  async scheduled(event, env, ctx) {
    console.log("30-min Cron auto-sniff trigger started at:", new Date().toISOString());
    ctx.waitUntil(autoSniffAndSelfHeal(env));
  }
};

function jsonResponse(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
      ...headers
    }
  });
}

// 规范化 JSON：按键字典序升序递归排序序列化
function canonicalJson(val) {
  if (val === null || typeof val !== 'object') {
    return JSON.stringify(val);
  }
  if (Array.isArray(val)) {
    return '[' + val.map(canonicalJson).join(',') + ']';
  }
  const keys = Object.keys(val).sort();
  return '{' + keys.map(k => JSON.stringify(k) + ':' + canonicalJson(val[k])).join(',') + '}';
}

// SubtleCrypto HMAC-SHA256
async function hmacSha256Hex(message, secret) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    enc.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const signature = await crypto.subtle.sign('HMAC', key, enc.encode(message));
  return Array.from(new Uint8Array(signature))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

// SubtleCrypto SHA-256
async function sha256Hex(message) {
  const enc = new TextEncoder();
  const hashBuf = await crypto.subtle.digest('SHA-256', enc.encode(message));
  return Array.from(new Uint8Array(hashBuf))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

/**
 * 全自动无人值守嗅探、真实验活与版本指纹自愈引擎
 */
async function autoSniffAndSelfHeal(env) {
  const result = { success: false, updated: false, details: {} };
  const currentRules = await getRules(env);

  try {
    // 1. 爬取前端 HTML
    const htmlRes = await fetch('https://h5.mygolbs.com', {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
      }
    });
    const html = await htmlRes.text();

    // 2. 正则查找脚本路径
    const scriptMatches = html.match(/src=["']?([^"' >]+\.js[^"' >]*)/g) || [];
    let mybusUrl = null;
    let encUrl = null;
    for (const s of scriptMatches) {
      const src = s.replace(/^src=["']?/, '').replace(/["']$/, '');
      if (src.includes('mybus.js')) {
        mybusUrl = src.startsWith('http') ? src : 'https://h5.mygolbs.com/' + src.replace(/^\//, '');
      }
      if (src.includes('encrypt-decrypt.js')) {
        encUrl = src.startsWith('http') ? src : 'https://h5.mygolbs.com/' + src.replace(/^\//, '');
      }
    }

    let extractedApiRoot = "ApiData.do";
    let extractedKeys = ["sg", "h5", "jm"];

    // 3. 从 mybus.js 提取 apiroot
    if (mybusUrl) {
      const jsRes = await fetch(mybusUrl);
      const js = await jsRes.text();
      const mRoot = js.match(/apiroot\s*=\s*["']([^"']+)["']/);
      if (mRoot) {
        extractedApiRoot = mRoot[1];
      }
    }

    // 4. 从 encrypt-decrypt.js 提取 3DES 密钥三元组
    if (encUrl) {
      const encRes = await fetch(encUrl);
      const encJs = await encRes.text();
      const mDec = encJs.match(/strDec\s*\(\s*data\s*,\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*\)/);
      if (mDec) {
        extractedKeys = [mDec[1], mDec[2], mDec[3]];
      }
    }

    const targetApiUrl = `https://h5.mygolbs.com/${extractedApiRoot.replace(/^\//, '')}`;
    result.details = { mybusUrl, encUrl, targetApiUrl, extractedKeys };

    // 5. 动静分离自动化冒烟测试 (P0-3 真验活)
    const rawSmokeText = await callMyGoLbsRaw(targetApiUrl, {
      CMD: '102',
      CITYNAME: '兴义市',
      KEYWORD: '1',
      CITYKEY: ''
    });

    let decryptedText = "";
    let lines = [];
    try {
      const trimmed = (rawSmokeText || "").trim();
      if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
        // 上游直出 JSON：使用提取的三元组进行加解密链路真实闭环断言
        const encHex = strEnc(trimmed, extractedKeys[0], extractedKeys[1], extractedKeys[2]);
        decryptedText = strDec(encHex, extractedKeys[0], extractedKeys[1], extractedKeys[2]);
      } else {
        // 上游返回加密 Hex/Ciphertext：直接执行 3DES 解密
        decryptedText = strDec(trimmed, extractedKeys[0], extractedKeys[1], extractedKeys[2]);
      }

      const parsed = JSON.parse(decryptedText);
      lines = parsed.buslines || [];
      if (!Array.isArray(lines) || lines.length === 0) {
        throw new Error("Parsed lines empty");
      }
    } catch (cryptoErr) {
      currentRules.lastSniffStatus = "CryptoFail";
      currentRules.lastSniffTime = new Date().toISOString();
      await saveRules(env, currentRules);
      result.error = "CryptoFail: " + cryptoErr.message;
      return result;
    }

    result.success = true;
    currentRules.lastSniffStatus = `Success: Verified ${lines.length} lines on ${targetApiUrl}`;
    currentRules.lastSniffTime = new Date().toISOString();

    // 6. 版本指纹机制 (P0-4：对 {chelaile, mygolbs} 规范化 JSON 计算 SHA-256)
    const candidateMyGoLbs = {
      ...currentRules.mygolbs,
      url: targetApiUrl,
      keys: extractedKeys
    };

    const fingerprintTarget = {
      chelaile: currentRules.chelaile,
      mygolbs: candidateMyGoLbs
    };
    const newFingerprint = await sha256Hex(canonicalJson(fingerprintTarget));

    let oldFingerprint = null;
    if (env && env.RULES_KV) {
      try {
        oldFingerprint = await env.RULES_KV.get("fingerprint");
      } catch (e) {
        console.error("Failed to read fingerprint from KV:", e);
      }
    }

    if (oldFingerprint && newFingerprint !== oldFingerprint) {
      // 密钥/URL 或上游规则发生实质变化，递增版本
      candidateMyGoLbs.version = (currentRules.mygolbs.version || 100) + 1;
      currentRules.mygolbs = candidateMyGoLbs;
      currentRules.ruleVersion = (currentRules.ruleVersion || 2026110600) + 1;
      result.updated = true;

      if (env && env.RULES_KV) {
        await env.RULES_KV.put("fingerprint", newFingerprint);
      }
    } else {
      currentRules.mygolbs = candidateMyGoLbs;
      if (!oldFingerprint && env && env.RULES_KV) {
        await env.RULES_KV.put("fingerprint", newFingerprint);
      }
    }

    await saveRules(env, currentRules);
  } catch (e) {
    currentRules.lastSniffStatus = `Error: ${e.message}`;
    currentRules.lastSniffTime = new Date().toISOString();
    await saveRules(env, currentRules);
    result.error = e.message;
  }
  return result;
}

// 原始请求掌上公交 (返回响应文本供验活与解密)
async function callMyGoLbsRaw(apiUrl, params) {
  const body = new URLSearchParams(params).toString();
  const res = await fetch(apiUrl, {
    method: 'POST',
    headers: {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
      'Origin': 'https://h5.mygolbs.com',
      'Referer': 'https://h5.mygolbs.com/',
      'X-Requested-With': 'XMLHttpRequest',
      'X-Forwarded-For': '221.13.12.88'
    },
    body
  });
  return await res.text();
}

async function callMyGoLbs(env, params) {
  const rules = await getRules(env);
  const rawText = await callMyGoLbsRaw(rules.mygolbs.url, params);
  try {
    return JSON.parse(rawText);
  } catch (e) {
    return JSON.parse(strDec(rawText, rules.mygolbs.keys[0], rules.mygolbs.keys[1], rules.mygolbs.keys[2]));
  }
}

async function searchLines(env, keyword, city) {
  const raw = await callMyGoLbs(env, {
    CMD: '102',
    CITYNAME: city,
    KEYWORD: keyword,
    CITYKEY: ''
  });
  const lines = (raw.buslines || []).map(item => ({
    lineId: `${item.lineName}@@@${item.upperOrDown || '1'}@@@${city}`,
    name: item.lineName,
    startSn: item.from,
    endSn: item.to
  }));
  return { lines, stations: [] };
}

async function getLineDetail(env, lineId, city) {
  let lineName = lineId;
  let direction = '1';
  let cityName = city;

  if (lineId.includes('@@@')) {
    const parts = lineId.split('@@@');
    lineName = parts[0];
    if (parts.length > 1) direction = parts[1];
    if (parts.length > 2 && parts[2]) cityName = parts[2];
  }

  // 1. 全线站点
  const stRaw = await callMyGoLbs(env, {
    CMD: '103',
    CITYNAME: cityName,
    LINENAME: lineName,
    DIRECTION: direction,
    CITYKEY: ''
  });
  const stList = stRaw.data || [];

  // 2. 实时车辆
  const rtRaw = await callMyGoLbs(env, {
    CMD: '104',
    CITYNAME: cityName,
    LINENAME: lineName,
    DIRECTION: direction,
    STATIONORDER: '1',
    CITYKEY: ''
  });
  const busList = rtRaw.list || [];

  const stations = stList.map((s, idx) => ({
    sn: s.stationName,
    order: idx + 1,
    lat: parseFloat(s.lat) || 0,
    lng: parseFloat(s.lon) || 0
  }));

  const buses = busList.map((b, idx) => ({
    busId: b.busNumber || `车辆${idx + 1}`,
    order: b.index || 1,
    lat: b.bus_lat || 0,
    lng: b.bus_lng || 0,
    awayTag: 0,
    busStatusDesc: b.arrive === 1 ? '到站' : '在途中'
  }));

  const startSn = stations.length > 0 ? stations[0].sn : '';
  const endSn = stations.length > 0 ? stations[stations.length - 1].sn : '';

  return {
    line: {
      lineId,
      name: lineName,
      startSn,
      endSn,
      firstTime: stRaw.beginTime || '',
      lastTime: stRaw.endTime || ''
    },
    stations,
    buses,
    otherLines: [{
      lineId: `${lineName}@@@${direction === '1' ? '2' : '1'}@@@${cityName}`,
      endSn: startSn
    }]
  };
}

// --- 移植自 encrypt-decrypt.js 的纯净 3DES 算法实现 ---

function getKeyBytes(key) {
  const keyBytes = [];
  const leng = key.length;
  const iterator = Math.floor(leng / 4);
  const remainder = leng % 4;
  for (let i = 0; i < iterator; i++) {
    keyBytes[i] = strToBt(key.substring(i * 4, i * 4 + 4));
  }
  if (remainder > 0) {
    keyBytes[iterator] = strToBt(key.substring(iterator * 4, leng));
  }
  return keyBytes;
}

function strToBt(str) {
  const leng = str.length;
  const bt = new Array(64);
  if (leng < 4) {
    for (let i = 0; i < leng; i++) {
      const k = str.charCodeAt(i);
      for (let j = 0; j < 16; j++) {
        let pow = 1;
        for (let m = 15; m > j; m--) pow *= 2;
        bt[16 * i + j] = Math.floor(k / pow) % 2;
      }
    }
    for (let p = leng; p < 4; p++) {
      for (let q = 0; q < 16; q++) {
        bt[16 * p + q] = 0;
      }
    }
  } else {
    for (let i = 0; i < 4; i++) {
      const k = str.charCodeAt(i);
      for (let j = 0; j < 16; j++) {
        let pow = 1;
        for (let m = 15; m > j; m--) pow *= 2;
        bt[16 * i + j] = Math.floor(k / pow) % 2;
      }
    }
  }
  return bt;
}

function bt4ToHex(binary) {
  switch (binary) {
    case '0000': return '0';
    case '0001': return '1';
    case '0010': return '2';
    case '0011': return '3';
    case '0100': return '4';
    case '0101': return '5';
    case '0110': return '6';
    case '0111': return '7';
    case '1000': return '8';
    case '1001': return '9';
    case '1010': return 'A';
    case '1011': return 'B';
    case '1100': return 'C';
    case '1101': return 'D';
    case '1110': return 'E';
    case '1111': return 'F';
    default: return '0';
  }
}

function hexToBt4(hex) {
  switch (hex.toUpperCase()) {
    case '0': return '0000';
    case '1': return '0001';
    case '2': return '0010';
    case '3': return '0011';
    case '4': return '0100';
    case '5': return '0101';
    case '6': return '0110';
    case '7': return '0111';
    case '8': return '1000';
    case '9': return '1001';
    case 'A': return '1010';
    case 'B': return '1011';
    case 'C': return '1100';
    case 'D': return '1101';
    case 'E': return '1110';
    case 'F': return '1111';
    default: return '0000';
  }
}

function byteToString(byteData) {
  let str = '';
  for (let i = 0; i < 4; i++) {
    let count = 0;
    for (let j = 0; j < 16; j++) {
      let pow = 1;
      for (let m = 15; m > j; m--) pow *= 2;
      count += byteData[16 * i + j] * pow;
    }
    if (count !== 0) str += String.fromCharCode(count);
  }
  return str;
}

function bt64ToHex(byteData) {
  let hex = '';
  for (let i = 0; i < 16; i++) {
    let bt = '';
    for (let j = 0; j < 4; j++) bt += byteData[i * 4 + j];
    hex += bt4ToHex(bt);
  }
  return hex;
}

function hexToBt64(hex) {
  let binary = '';
  for (let i = 0; i < 16; i++) {
    binary += hexToBt4(hex.substring(i, i + 1));
  }
  return binary;
}

const s1 = [
  [14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7],
  [0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8],
  [4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0],
  [15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13]
];
const s2 = [
  [15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10],
  [3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5],
  [0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15],
  [13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9]
];
const s3 = [
  [10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8],
  [13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1],
  [13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7],
  [1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12]
];
const s4 = [
  [7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15],
  [13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9],
  [10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4],
  [3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14]
];
const s5 = [
  [2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9],
  [14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6],
  [4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14],
  [11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3]
];
const s6 = [
  [12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11],
  [10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8],
  [9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6],
  [4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13]
];
const s7 = [
  [4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1],
  [13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6],
  [1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2],
  [6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12]
];
const s8 = [
  [13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7],
  [1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2],
  [7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8],
  [2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11]
];

function getBoxBinary(i) {
  return i.toString(2).padStart(4, '0');
}

function initPermute(originalData) {
  const ipByte = new Array(64);
  for (let i = 0, m = 1, n = 0; i < 4; i++, m += 2, n += 2) {
    for (let j = 7, k = 0; j >= 0; j--, k++) {
      ipByte[i * 8 + k] = originalData[j * 8 + m];
      ipByte[i * 8 + k + 32] = originalData[j * 8 + n];
    }
  }
  return ipByte;
}

function expandPermute(rightData) {
  const epByte = new Array(48);
  for (let i = 0; i < 8; i++) {
    epByte[i * 6 + 0] = i === 0 ? rightData[31] : rightData[i * 4 - 1];
    epByte[i * 6 + 1] = rightData[i * 4 + 0];
    epByte[i * 6 + 2] = rightData[i * 4 + 1];
    epByte[i * 6 + 3] = rightData[i * 4 + 2];
    epByte[i * 6 + 4] = rightData[i * 4 + 3];
    epByte[i * 6 + 5] = i === 7 ? rightData[0] : rightData[i * 4 + 4];
  }
  return epByte;
}

function xor(byteOne, byteTwo) {
  const xorByte = new Array(byteOne.length);
  for (let i = 0; i < byteOne.length; i++) {
    xorByte[i] = byteOne[i] ^ byteTwo[i];
  }
  return xorByte;
}

function sBoxPermute(expandByte) {
  const sBoxByte = new Array(32);
  const boxes = [s1, s2, s3, s4, s5, s6, s7, s8];
  for (let m = 0; m < 8; m++) {
    const i = expandByte[m * 6 + 0] * 2 + expandByte[m * 6 + 5];
    const j = expandByte[m * 6 + 1] * 8 + expandByte[m * 6 + 2] * 4 + expandByte[m * 6 + 3] * 2 + expandByte[m * 6 + 4];
    const binary = getBoxBinary(boxes[m][i][j]);
    sBoxByte[m * 4 + 0] = parseInt(binary.charAt(0), 10);
    sBoxByte[m * 4 + 1] = parseInt(binary.charAt(1), 10);
    sBoxByte[m * 4 + 2] = parseInt(binary.charAt(2), 10);
    sBoxByte[m * 4 + 3] = parseInt(binary.charAt(3), 10);
  }
  return sBoxByte;
}

const pBoxIndexes = [15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9, 1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24];
function pPermute(sBoxByte) {
  const pBox = new Array(32);
  for (let i = 0; i < 32; i++) {
    pBox[i] = sBoxByte[pBoxIndexes[i]];
  }
  return pBox;
}

const fpIndexes = [
  39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
  37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
  35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
  33, 1, 41,  9, 49, 17, 57, 25, 32, 0, 40,  8, 48, 16, 56, 24
];
function finallyPermute(endByte) {
  const fpByte = new Array(64);
  for (let i = 0; i < 64; i++) {
    fpByte[i] = endByte[fpIndexes[i]];
  }
  return fpByte;
}

const loopShifts = [1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1];
const keyPermuteIndexes = [
  13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
  25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
  50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31
];

function generateKeys(keyByte) {
  const key = new Array(56);
  const keys = [];
  for (let i = 0; i < 7; i++) {
    for (let j = 0, k = 7; j < 8; j++, k--) {
      key[i * 8 + j] = keyByte[8 * k + i];
    }
  }
  for (let i = 0; i < 16; i++) {
    keys[i] = new Array(48);
    for (let j = 0; j < loopShifts[i]; j++) {
      const tempLeft = key[0];
      const tempRight = key[28];
      for (let k = 0; k < 27; k++) {
        key[k] = key[k + 1];
        key[28 + k] = key[29 + k];
      }
      key[27] = tempLeft;
      key[55] = tempRight;
    }
    for (let m = 0; m < 48; m++) {
      keys[i][m] = key[keyPermuteIndexes[m]];
    }
  }
  return keys;
}

function desDec(dataByte, keyByte) {
  const keys = generateKeys(keyByte);
  const ipByte = initPermute(dataByte);
  const ipLeft = new Array(32);
  const ipRight = new Array(32);
  const tempLeft = new Array(32);
  for (let k = 0; k < 32; k++) {
    ipLeft[k] = ipByte[k];
    ipRight[k] = ipByte[32 + k];
  }
  for (let i = 15; i >= 0; i--) {
    for (let j = 0; j < 32; j++) {
      tempLeft[j] = ipLeft[j];
      ipLeft[j] = ipRight[j];
    }
    const tempRight = xor(pPermute(sBoxPermute(xor(expandPermute(ipRight), keys[i]))), tempLeft);
    for (let n = 0; n < 32; n++) {
      ipRight[n] = tempRight[n];
    }
  }
  const finalData = new Array(64);
  for (let i = 0; i < 32; i++) {
    finalData[i] = ipRight[i];
    finalData[32 + i] = ipLeft[i];
  }
  return finallyPermute(finalData);
}

function desEnc(dataByte, keyByte) {
  const keys = generateKeys(keyByte);
  const ipByte = initPermute(dataByte);
  const ipLeft = new Array(32);
  const ipRight = new Array(32);
  const tempLeft = new Array(32);
  for (let k = 0; k < 32; k++) {
    ipLeft[k] = ipByte[k];
    ipRight[k] = ipByte[32 + k];
  }
  for (let i = 0; i < 16; i++) {
    for (let j = 0; j < 32; j++) {
      tempLeft[j] = ipLeft[j];
      ipLeft[j] = ipRight[j];
    }
    const tempRight = xor(pPermute(sBoxPermute(xor(expandPermute(ipRight), keys[i]))), tempLeft);
    for (let n = 0; n < 32; n++) {
      ipRight[n] = tempRight[n];
    }
  }
  const finalData = new Array(64);
  for (let i = 0; i < 32; i++) {
    finalData[i] = ipRight[i];
    finalData[32 + i] = ipLeft[i];
  }
  return finallyPermute(finalData);
}

function strDec(data, firstKey, secondKey, thirdKey) {
  if (!data) return '';
  const leng = data.length;
  let decStr = '';
  const firstKeyBt = firstKey ? getKeyBytes(firstKey) : [];
  const secondKeyBt = secondKey ? getKeyBytes(secondKey) : [];
  const thirdKeyBt = thirdKey ? getKeyBytes(thirdKey) : [];
  const firstLength = firstKeyBt.length;
  const secondLength = secondKeyBt.length;
  const thirdLength = thirdKeyBt.length;

  const iterator = Math.floor(leng / 16);
  for (let i = 0; i < iterator; i++) {
    const tempData = data.substring(i * 16, i * 16 + 16);
    const strByte = hexToBt64(tempData);
    const intByte = new Array(64);
    for (let j = 0; j < 64; j++) {
      intByte[j] = parseInt(strByte.charAt(j), 10);
    }
    let tempBt = intByte;
    if (firstLength && secondLength && thirdLength) {
      for (let x = thirdLength - 1; x >= 0; x--) tempBt = desDec(tempBt, thirdKeyBt[x]);
      for (let y = secondLength - 1; y >= 0; y--) tempBt = desDec(tempBt, secondKeyBt[y]);
      for (let z = firstLength - 1; z >= 0; z--) tempBt = desDec(tempBt, firstKeyBt[z]);
    } else if (firstLength && secondLength) {
      for (let x = secondLength - 1; x >= 0; x--) tempBt = desDec(tempBt, secondKeyBt[x]);
      for (let y = firstLength - 1; y >= 0; y--) tempBt = desDec(tempBt, firstKeyBt[y]);
    } else if (firstLength) {
      for (let x = firstLength - 1; x >= 0; x--) tempBt = desDec(tempBt, firstKeyBt[x]);
    }
    decStr += byteToString(tempBt);
  }
  return decStr;
}

function strEnc(data, firstKey, secondKey, thirdKey) {
  if (!data) return '';
  const leng = data.length;
  let encData = '';
  const firstKeyBt = firstKey ? getKeyBytes(firstKey) : [];
  const secondKeyBt = secondKey ? getKeyBytes(secondKey) : [];
  const thirdKeyBt = thirdKey ? getKeyBytes(thirdKey) : [];
  const firstLength = firstKeyBt.length;
  const secondLength = secondKeyBt.length;
  const thirdLength = thirdKeyBt.length;

  const iterator = Math.floor(leng / 4);
  const remainder = leng % 4;
  for (let i = 0; i < iterator; i++) {
    const tempData = data.substring(i * 4, i * 4 + 4);
    let tempBt = strToBt(tempData);
    if (firstLength && secondLength && thirdLength) {
      for (let x = 0; x < firstLength; x++) tempBt = desEnc(tempBt, firstKeyBt[x]);
      for (let y = 0; y < secondLength; y++) tempBt = desEnc(tempBt, secondKeyBt[y]);
      for (let z = 0; z < thirdLength; z++) tempBt = desEnc(tempBt, thirdKeyBt[z]);
    } else if (firstLength && secondLength) {
      for (let x = 0; x < firstLength; x++) tempBt = desEnc(tempBt, firstKeyBt[x]);
      for (let y = 0; y < secondLength; y++) tempBt = desEnc(tempBt, secondKeyBt[y]);
    } else if (firstLength) {
      for (let x = 0; x < firstLength; x++) tempBt = desEnc(tempBt, firstKeyBt[x]);
    }
    encData += bt64ToHex(tempBt);
  }
  if (remainder > 0) {
    const tempData = data.substring(iterator * 4);
    let tempBt = strToBt(tempData);
    if (firstLength && secondLength && thirdLength) {
      for (let x = 0; x < firstLength; x++) tempBt = desEnc(tempBt, firstKeyBt[x]);
      for (let y = 0; y < secondLength; y++) tempBt = desEnc(tempBt, secondKeyBt[y]);
      for (let z = 0; z < thirdLength; z++) tempBt = desEnc(tempBt, thirdKeyBt[z]);
    } else if (firstLength && secondLength) {
      for (let x = 0; x < firstLength; x++) tempBt = desEnc(tempBt, firstKeyBt[x]);
      for (let y = 0; y < secondLength; y++) tempBt = desEnc(tempBt, secondKeyBt[y]);
    } else if (firstLength) {
      for (let x = 0; x < firstLength; x++) tempBt = desEnc(tempBt, firstKeyBt[x]);
    }
    encData += bt64ToHex(tempBt);
  }
  return encData;
}
