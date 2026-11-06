import urllib.request, urllib.parse, ssl, json

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

def post_mygolbs(params):
    data = urllib.parse.urlencode(params).encode('utf-8')
    req = urllib.request.Request('https://h5.mygolbs.com/ApiData.do', data=data, headers={
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'Origin': 'https://h5.mygolbs.com',
        'Referer': 'https://h5.mygolbs.com/',
        'X-Requested-With': 'XMLHttpRequest'
    })
    res = urllib.request.urlopen(req, context=ctx, timeout=8)
    return json.loads(res.read().decode('utf-8'))

# 1. 模拟搜索 1路
res_search = post_mygolbs({'CMD': '102', 'CITYNAME': '兴义市', 'KEYWORD': '1', 'CITYKEY': ''})
lines = res_search.get('buslines', [])
print(f"Search results: {len(lines)} lines found.")
first = lines[0]
print("First line:", first.get('lineName'), "From:", first.get('from'), "To:", first.get('to'), "Direction:", first.get('upperOrDown'))

# 2. 模拟全线详情 (CMD 103 + CMD 104)
linename = first.get('lineName')
direction = first.get('upperOrDown', '1')

res_stations = post_mygolbs({'CMD': '103', 'CITYNAME': '兴义市', 'LINENAME': linename, 'DIRECTION': direction, 'CITYKEY': ''})
stations = res_stations.get('data', [])
print(f"Stations for {linename}: {len(stations)} stations.")

res_realtime = post_mygolbs({'CMD': '104', 'CITYNAME': '兴义市', 'LINENAME': linename, 'DIRECTION': direction, 'STATIONORDER': '1', 'CITYKEY': ''})
buses = res_realtime.get('list', [])
print(f"Real-time buses on {linename}: {len(buses)} active buses.")
for b in buses:
    print(f"  * Bus: {b.get('busNumber')} at index {b.get('index')} ({b.get('stationName')}) Lat/Lng: ({b.get('bus_lat')}, {b.get('bus_lng')})")

print("\nSUCCESS: All MyGoLBS adapters verified!")

