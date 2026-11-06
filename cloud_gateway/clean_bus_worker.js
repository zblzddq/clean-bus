/**
 * CleanBus Cloudflare Worker 聚合边缘网关 & 规则配置中心
 * 1. 30分钟全自动无人值守嗅探 (Auto-Sniff & Regex Extractor)
 * 2. 动静分离自动化冒烟测试 (不受夜间收班误杀)
 * 3. 独立版本管理与规则自愈下发 (/api/v1/rules)
 * 4. 每日 100,000 次免费配额
 */

// 云端动态维护的最新规则 (支持自动自愈递增)
let DYNAMIC_RULES = {
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
      // 1. 动态规则分发端点 (供客户端定期比对与自愈拉取)
      if (pathname === '/api/v1/rules') {
        return jsonResponse(DYNAMIC_RULES);
      }

      // 手动触发一次嗅探测试
      if (pathname === '/api/v1/triggerSniff') {
        const result = await autoSniffAndSelfHeal();
        return jsonResponse(result);
      }

      // 2. 线路搜索代理
      if (pathname === '/api/v1/search') {
        const keyword = url.searchParams.get('keyword') || '';
        const city = url.searchParams.get('city') || '拉萨市';
        const data = await searchLines(keyword, city);
        return jsonResponse(data);
      }

      // 3. 线路详情代理
      if (pathname === '/api/v1/lineDetail') {
        const lineId = url.searchParams.get('lineId') || '';
        const city = url.searchParams.get('city') || '拉萨市';
        const data = await getLineDetail(lineId, city);
        return jsonResponse(data);
      }

      return jsonResponse({
        status: 'ok',
        service: 'CleanBus Edge Rules & Gateway',
        cron: '30-minute auto-sniff & smoke test active',
        rules: {
          ruleVersion: DYNAMIC_RULES.ruleVersion,
          chelaileVersion: DYNAMIC_RULES.chelaile.version,
          mygolbsVersion: DYNAMIC_RULES.mygolbs.version,
          lastSniffTime: DYNAMIC_RULES.lastSniffTime,
          lastSniffStatus: DYNAMIC_RULES.lastSniffStatus
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
    ctx.waitUntil(autoSniffAndSelfHeal());
  }
};

function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*'
    }
  });
}

/**
 * 全自动无人值守嗅探与正则提取自愈引擎
 */
async function autoSniffAndSelfHeal() {
  const result = { success: false, updated: false, details: {} };
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

    // 4. 从 encrypt-decrypt.js 提取 3DES 密钥
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

    // 5. 动静分离自动化冒烟测试断言 (全天候静态线路查询)
    const smokeRes = await callMyGoLbsWithUrl(targetApiUrl, {
      CMD: '102',
      CITYNAME: '兴义市',
      KEYWORD: '1',
      CITYKEY: ''
    });

    const lines = smokeRes.buslines || [];
    if (lines.length > 0) {
      result.success = true;
      DYNAMIC_RULES.lastSniffStatus = `Success: Verified ${lines.length} lines on ${targetApiUrl}`;
      DYNAMIC_RULES.lastSniffTime = new Date().toISOString();

      // 判断是否有变更，有变更则自动递增版本号发布
      if (targetApiUrl !== DYNAMIC_RULES.mygolbs.url) {
        DYNAMIC_RULES.mygolbs.url = targetApiUrl;
        DYNAMIC_RULES.mygolbs.keys = extractedKeys;
        DYNAMIC_RULES.mygolbs.version += 1;
        DYNAMIC_RULES.ruleVersion += 1;
        result.updated = true;
      }
    } else {
      DYNAMIC_RULES.lastSniffStatus = `Warning: Smoke test returned 0 lines`;
    }
  } catch (e) {
    DYNAMIC_RULES.lastSniffStatus = `Error: ${e.message}`;
    result.error = e.message;
  }
  return result;
}

// 代理转发掌上公交 (MyGoLBS)
async function callMyGoLbsWithUrl(apiUrl, params) {
  const body = new URLSearchParams(params).toString();
  const res = await fetch(apiUrl, {
    method: 'POST',
    headers: {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
      'Origin': 'https://h5.mygolbs.com',
      'Referer': 'https://h5.mygolbs.com/',
      'X-Requested-With': 'XMLHttpRequest',
      'X-Forwarded-For': '221.13.12.88' // 伪装国内区域 IP 避免海外风控
    },
    body
  });
  return await res.json();
}

async function callMyGoLbs(params) {
  return await callMyGoLbsWithUrl(DYNAMIC_RULES.mygolbs.url, params);
}

async function searchLines(keyword, city) {
  const raw = await callMyGoLbs({
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

async function getLineDetail(lineId, city) {
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
  const stRaw = await callMyGoLbs({
    CMD: '103',
    CITYNAME: cityName,
    LINENAME: lineName,
    DIRECTION: direction,
    CITYKEY: ''
  });
  const stList = stRaw.data || [];

  // 2. 实时车辆
  const rtRaw = await callMyGoLbs({
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
