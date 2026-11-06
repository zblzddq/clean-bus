package com.geek.cleanbus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 掌上公交 (MyGoLBS) 原生数据源实现
 * 支持全国大量三四五线、地州县级市（如兴义、黔西南等）及主流城市实时在途公交
 */
public class MyGoLbsSource implements IBusDataSource {

    public static final String ID = "mygolbs";
    private static final String DEFAULT_API_URL = "https://h5.mygolbs.com/ApiData.do";

    private static String getApiUrl() {
        DynamicRuleManager mgr = DynamicRuleManager.getInstance();
        return mgr != null ? mgr.getMyGoLbsUrl() : DEFAULT_API_URL;
    }

    @Override
    public String getSourceId() {
        return ID;
    }

    @Override
    public String getSourceName() {
        return "掌上公交";
    }

    /**
     * 发送 POST 表单请求到掌上公交网关
     */
    private JSONObject doMyBusPost(Map<String, String> params) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
              .append("=")
              .append(URLEncoder.encode(entry.getValue() != null ? entry.getValue() : "", "UTF-8"));
        }
        byte[] bodyBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(getApiUrl());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setDoInput(true);

            // 关键请求头伪装
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("Origin", "https://h5.mygolbs.com");
            conn.setRequestProperty("Referer", "https://h5.mygolbs.com/");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");

            conn.getOutputStream().write(bodyBytes);
            conn.getOutputStream().flush();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) {
                throw new IOException("HTTP " + code + ": empty response from " + getApiUrl());
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream stream = is) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = stream.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
            }
            String resStr = baos.toString("UTF-8").trim();
            return new JSONObject(resStr);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String resolveCityName(String cityId, double lat, double lng) {
        if ("043".equals(cityId)) return "拉萨市";
        if (lat >= 24.5 && lat <= 26.0 && lng >= 104.0 && lng <= 106.0) return "兴义市";
        if (cityId != null && !cityId.isEmpty() && !cityId.matches("\\d+")) {
            return cityId.endsWith("市") ? cityId : cityId + "市";
        }
        return "拉萨市";
    }

    @Override
    public JSONObject getNearbyStops(double lat, double lng, String cityId) throws Exception {
        String cityName = resolveCityName(cityId, lat, lng);
        Map<String, String> p = new HashMap<>();
        p.put("CMD", "106");
        p.put("CITYNAME", cityName);
        p.put("LNG", String.valueOf(lng));
        p.put("LAT", String.valueOf(lat));
        p.put("CITYKEY", "");

        JSONObject raw = doMyBusPost(p);
        JSONArray list = raw.optJSONArray("data");

        JSONObject res = new JSONObject();
        JSONArray nearSts = new JSONArray();

        if (list != null) {
            int maxStops = Math.min(list.length(), 6);
            for (int i = 0; i < maxStops; i++) {
                JSONObject s = list.optJSONObject(i);
                if (s == null) continue;
                String sn = s.optString("name", "");
                int dis = s.optInt("dis", 0);

                JSONObject stopObj = new JSONObject();
                stopObj.put("sn", sn);
                stopObj.put("distance", dis);
                stopObj.put("physicalStId", sn);
                stopObj.put("namesakeStId", sn);

                // 查询经由该站点的线路
                JSONArray linesArr = new JSONArray();
                try {
                    Map<String, String> lp = new HashMap<>();
                    lp.put("CMD", "105");
                    lp.put("CITYNAME", cityName);
                    lp.put("STATIONNAME", sn);
                    lp.put("CITYKEY", "");
                    JSONObject lRaw = doMyBusPost(lp);
                    JSONArray lData = lRaw.optJSONArray("data");
                    if (lData != null) {
                        for (int j = 0; j < Math.min(lData.length(), 5); j++) {
                            JSONObject lItem = lData.optJSONObject(j);
                            if (lItem == null) continue;
                            String lName = lItem.optString("lineName", "");
                            String endSn = lItem.optString("to", "");
                            String dir = lItem.optString("upperOrDown", "1");

                            JSONObject lineWrapper = new JSONObject();
                            JSONObject lineInfo = new JSONObject();
                            lineInfo.put("lineId", lName + "@@@" + dir + "@@@" + cityName);
                            lineInfo.put("name", lName);
                            lineInfo.put("endSn", endSn);
                            lineInfo.put("firstTime", lItem.optString("beginTime", ""));
                            lineInfo.put("lastTime", lItem.optString("endTime", ""));
                            lineWrapper.put("line", lineInfo);
                            linesArr.put(lineWrapper);
                        }
                    }
                } catch (Exception ignored) {}

                stopObj.put("lines", linesArr);
                nearSts.put(stopObj);
            }
        }

        res.put("nearSts", nearSts);
        return res;
    }

    @Override
    public JSONObject getStopDetail(String physicalStId, String namesakeStId, String firstLineId, String cityId, double lat, double lng) throws Exception {
        // 掌上公交在 getNearbyStops 中已内联补齐基础线路，此处保持空或返回空 stationList
        JSONObject res = new JSONObject();
        res.put("stationList", new JSONArray());
        return res;
    }

    @Override
    public JSONObject getLineDetail(String lineId, String cityId, double lat, double lng) throws Exception {
        // 解析 lineId (格式: 线路名@@@方向@@@城市名 或 纯线路名)
        String lineName = lineId;
        String direction = "1";
        String cityName = resolveCityName(cityId, lat, lng);

        if (lineId.contains("@@@")) {
            String[] parts = lineId.split("@@@");
            lineName = parts[0];
            if (parts.length > 1) direction = parts[1];
            if (parts.length > 2 && !parts[2].isEmpty()) cityName = parts[2];
        }

        // 1. 获取全线站点列表 (CMD 103)
        Map<String, String> sp = new HashMap<>();
        sp.put("CMD", "103");
        sp.put("CITYNAME", cityName);
        sp.put("LINENAME", lineName);
        sp.put("DIRECTION", direction);
        sp.put("CITYKEY", "");
        JSONObject stRaw = doMyBusPost(sp);
        JSONArray stList = stRaw.optJSONArray("data");

        // 2. 获取实时在途车辆 (CMD 104)
        Map<String, String> rp = new HashMap<>();
        rp.put("CMD", "104");
        rp.put("CITYNAME", cityName);
        rp.put("LINENAME", lineName);
        rp.put("DIRECTION", direction);
        rp.put("STATIONORDER", "1");
        rp.put("CITYKEY", "");
        JSONObject rtRaw = doMyBusPost(rp);
        JSONArray busList = rtRaw.optJSONArray("list");

        // 组装成 CleanBus 标准结构
        JSONObject res = new JSONObject();

        // line 信息
        JSONObject line = new JSONObject();
        line.put("lineId", lineId);
        line.put("name", lineName);
        String startSn = "";
        String endSn = "";
        if (stList != null && stList.length() > 0) {
            JSONObject firstSt = stList.optJSONObject(0);
            JSONObject lastSt = stList.optJSONObject(stList.length() - 1);
            startSn = firstSt != null ? firstSt.optString("stationName", "") : "";
            endSn = lastSt != null ? lastSt.optString("stationName", "") : "";
        }
        line.put("startSn", startSn);
        line.put("endSn", endSn);
        line.put("firstTime", stRaw.optString("beginTime", ""));
        line.put("lastTime", stRaw.optString("endTime", ""));
        res.put("line", line);

        // stations 列表
        JSONArray stations = new JSONArray();
        if (stList != null) {
            for (int i = 0; i < stList.length(); i++) {
                JSONObject s = stList.optJSONObject(i);
                if (s == null) continue;
                JSONObject stObj = new JSONObject();
                stObj.put("sn", s.optString("stationName"));
                stObj.put("order", i + 1);
                stObj.put("lat", s.optDouble("lat", 0.0));
                stObj.put("lng", s.optDouble("lon", 0.0));
                stations.put(stObj);
            }
        }
        res.put("stations", stations);

        // buses 实时在途列表
        JSONArray buses = new JSONArray();
        if (busList != null) {
            for (int i = 0; i < busList.length(); i++) {
                JSONObject b = busList.optJSONObject(i);
                if (b == null) continue;
                JSONObject bObj = new JSONObject();
                String busNumber = b.optString("busNumber", "车辆" + (i + 1));
                bObj.put("busId", busNumber);
                bObj.put("order", b.optInt("index", 1));
                bObj.put("lat", b.optDouble("bus_lat", 0.0));
                bObj.put("lng", b.optDouble("bus_lng", 0.0));
                bObj.put("awayTag", 0);
                bObj.put("busStatusDesc", b.optInt("arrive", 0) == 1 ? "到站" : "在途中");
                buses.put(bObj);
            }
        }
        res.put("buses", buses);

        // otherLines (换向反向线路)
        JSONArray otherLines = new JSONArray();
        String oppDir = "1".equals(direction) ? "2" : "1";
        JSONObject oppLine = new JSONObject();
        oppLine.put("lineId", lineName + "@@@" + oppDir + "@@@" + cityName);
        oppLine.put("endSn", startSn);
        otherLines.put(oppLine);
        res.put("otherlines", otherLines);

        return res;
    }

    @Override
    public JSONObject search(String keyword, String cityId, double lat, double lng) throws Exception {
        String cityName = resolveCityName(cityId, lat, lng);
        Map<String, String> p = new HashMap<>();
        p.put("CMD", "102");
        p.put("CITYNAME", cityName);
        p.put("KEYWORD", keyword);
        p.put("CITYKEY", "");

        JSONObject raw = doMyBusPost(p);
        JSONArray list = raw.optJSONArray("buslines");

        JSONObject res = new JSONObject();
        JSONArray lines = new JSONArray();

        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String lName = item.optString("lineName", "");
                String dir = item.optString("upperOrDown", "1");
                String from = item.optString("from", "");
                String to = item.optString("to", "");

                JSONObject lineObj = new JSONObject();
                lineObj.put("lineId", lName + "@@@" + dir + "@@@" + cityName);
                lineObj.put("name", lName);
                lineObj.put("startSn", from);
                lineObj.put("endSn", to);
                lines.put(lineObj);
            }
        }

        res.put("lines", lines);
        res.put("stations", new JSONArray());
        return res;
    }
}

