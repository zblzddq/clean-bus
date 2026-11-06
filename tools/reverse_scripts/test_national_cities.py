import urllib.request
import urllib.parse
import ssl
import json
import time

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

# 1. 掌上公交 (MyGoLBS) 测试器
def test_mygolbs(city_name, line_name="1路"):
    try:
        # A. 搜索线路
        data = urllib.parse.urlencode({
            'CMD': '102',
            'CITYNAME': city_name,
            'KEYWORD': line_name,
            'CITYKEY': ''
        }).encode('utf-8')
        req = urllib.request.Request('https://h5.mygolbs.com/ApiData.do', data=data, headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
            'Origin': 'https://h5.mygolbs.com',
            'Referer': 'https://h5.mygolbs.com/',
            'X-Requested-With': 'XMLHttpRequest'
        })
        res = urllib.request.urlopen(req, context=ctx, timeout=6)
        obj = json.loads(res.read().decode('utf-8'))
        lines = obj.get('buslines', [])
        if not lines:
            return {"hit": False, "msg": "未搜索到线路"}
        
        target = lines[0]
        direction = target.get('upperOrDown', '1')

        # B. 查在途车辆 (CMD 104)
        rt_data = urllib.parse.urlencode({
            'CMD': '104',
            'CITYNAME': city_name,
            'LINENAME': line_name,
            'DIRECTION': direction,
            'STATIONORDER': '1',
            'CITYKEY': ''
        }).encode('utf-8')
        req_rt = urllib.request.Request('https://h5.mygolbs.com/ApiData.do', data=rt_data, headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
            'Origin': 'https://h5.mygolbs.com',
            'Referer': 'https://h5.mygolbs.com/',
            'X-Requested-With': 'XMLHttpRequest'
        })
        res_rt = urllib.request.urlopen(req_rt, context=ctx, timeout=6)
        rt_obj = json.loads(res_rt.read().decode('utf-8'))
        buses = rt_obj.get('list', [])

        return {
            "hit": True,
            "line": f"{target.get('from')} ➔ {target.get('to')}",
            "bus_count": len(buses),
            "sample_bus": buses[0].get('busNumber') if buses else "无在途实车(可能班次间隔中)"
        }
    except Exception as e:
        return {"hit": False, "msg": str(e)[:30]}

# 2. 待测城市矩阵（覆盖一二线、三四五线、地州县级市）
CITIES_TO_TEST = [
    # 四五线 / 地州与县级市
    {"name": "兴义市", "desc": "贵州黔西南州州府(县级市)", "line": "1路"},
    {"name": "遵义市", "desc": "贵州地级市", "line": "1路"},
    {"name": "六盘水市", "desc": "贵州地级市", "line": "1路"},
    {"name": "西昌市", "desc": "四川凉山州州府(县级市)", "line": "1路"},
    {"name": "宜宾市", "desc": "四川地级市", "line": "1路"},
    {"name": "日喀则市", "desc": "西藏地级市", "line": "1路"},
    {"name": "林芝市", "desc": "西藏地级市", "line": "1路"},
    {"name": "义乌市", "desc": "浙江知名县级市", "line": "1路"},
    {"name": "延吉市", "desc": "吉林延边州州府(县级市)", "line": "1路"},
    # 二三线省会/强地市
    {"name": "拉萨市", "desc": "西藏自治区首府", "line": "16路"},
    {"name": "贵阳市", "desc": "贵州省会", "line": "1路"},
    {"name": "成都市", "desc": "四川省会(新一线)", "line": "1路"},
    {"name": "昆明市", "desc": "云南省会", "line": "1路"},
    {"name": "南宁市", "desc": "广西首府", "line": "1路"},
    {"name": "兰州市", "desc": "甘肃省会", "line": "1路"},
    {"name": "乌鲁木齐市", "desc": "新疆首府", "line": "1路"},
    # 一线超级大都市
    {"name": "北京市", "desc": "直辖市/首都", "line": "1路"},
    {"name": "上海市", "desc": "直辖市", "line": "1路"},
    {"name": "深圳市", "desc": "一线城市", "line": "1路"},
]

print("=" * 70)
print(f"开始全国多梯级城市实时公交数据源连通性测试 (共 {len(CITIES_TO_TEST)} 个典型城市)...")
print("=" * 70)

results = []
for c in CITIES_TO_TEST:
    city_name = c["name"]
    line_name = c["line"]
    res = test_mygolbs(city_name, line_name)
    results.append({
        "city": city_name,
        "desc": c["desc"],
        "line": line_name,
        "res": res
    })
    status_icon = "✅" if res["hit"] else "❌"
    bus_info = f"在途车: {res.get('bus_count', 0)}辆 (样本: {res.get('sample_bus', '')})" if res["hit"] else res.get("msg", "")
    print(f"{status_icon} [{city_name}] ({c['desc']}) -> {line_name}: {bus_info}")
    time.sleep(0.3)

print("\n" + "=" * 70)
hit_count = sum(1 for r in results if r["res"]["hit"])
print(f"测试完毕: 成功覆盖 {hit_count}/{len(CITIES_TO_TEST)} 个城市 (覆盖率: {round(hit_count / len(CITIES_TO_TEST) * 100, 1)}%)")

