package com.geek.cleanbus;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 全国城市自适应定位器 (Zero-Cost & Triple-Fallback)
 * 1. 离线核心城市空间多边形/经纬度盒匹配 (0耗时、零网络依赖)
 * 2. Android 系统原生 Geocoder
 * 3. 开放式 OSM / Nominatim 逆地理编码 (免 Key 公共通道)
 */
public class CityLocator {

    public static class CityResult {
        public String cityName; // 如 "拉萨市", "兴义市"
        public String chelaileCityId; // 若已知则返回对应代码，未知返回 cityName

        public CityResult(String cityName, String chelaileCityId) {
            this.cityName = cityName;
            this.chelaileCityId = chelaileCityId;
        }
    }

    private static final Map<String, String> CITY_CODE_MAP = new HashMap<>();
    static {
        CITY_CODE_MAP.put("拉萨市", "043");
        CITY_CODE_MAP.put("北京市", "001");
        CITY_CODE_MAP.put("上海市", "002");
        CITY_CODE_MAP.put("广州市", "003");
        CITY_CODE_MAP.put("深圳市", "004");
        CITY_CODE_MAP.put("成都市", "075");
        CITY_CODE_MAP.put("重庆市", "060");
        CITY_CODE_MAP.put("贵阳市", "085");
        CITY_CODE_MAP.put("西安市", "029");
        CITY_CODE_MAP.put("武汉市", "027");
        CITY_CODE_MAP.put("杭州市", "0571");
        CITY_CODE_MAP.put("南京市", "025");
    }

    /**
     * 根据 GPS 经纬度智能判定城市
     */
    public static CityResult resolveCity(Context context, double lat, double lng) {
        // 1. 离线经纬度盒匹配 (瞬时判定)
        CityResult offline = matchOfflineBoundingBox(lat, lng);
        if (offline != null) {
            return offline;
        }

        // 2. 原生 Android Geocoder
        if (Geocoder.isPresent()) {
            try {
                Geocoder geocoder = new Geocoder(context, Locale.CHINA);
                List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address addr = addresses.get(0);
                    String city = addr.getLocality();
                    if (city == null || city.isEmpty()) city = addr.getSubAdminArea();
                    if (city == null || city.isEmpty()) city = addr.getAdminArea();
                    if (city != null && !city.isEmpty()) {
                        city = normalizeCityName(city);
                        String code = CITY_CODE_MAP.containsKey(city) ? CITY_CODE_MAP.get(city) : city;
                        return new CityResult(city, code);
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. 网络开放逆地理编码 (OSM Nominatim)
        try {
            String urlStr = "https://nominatim.openstreetmap.org/reverse?format=json&lat=" + lat + "&lon=" + lng + "&zoom=10";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "CleanBusApp/1.0");

            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                JSONObject obj = new JSONObject(baos.toString("UTF-8"));
                JSONObject addr = obj.optJSONObject("address");
                if (addr != null) {
                    String city = addr.optString("city", "");
                    if (city.isEmpty()) city = addr.optString("county", "");
                    if (city.isEmpty()) city = addr.optString("state", "");
                    if (!city.isEmpty()) {
                        city = normalizeCityName(city);
                        String code = CITY_CODE_MAP.containsKey(city) ? CITY_CODE_MAP.get(city) : city;
                        return new CityResult(city, code);
                    }
                }
            }
        } catch (Exception ignored) {}

        // 默认兜底：拉萨市
        return new CityResult("拉萨市", "043");
    }

    private static CityResult matchOfflineBoundingBox(double lat, double lng) {
        // 西藏拉萨 (29.2 ~ 30.2, 90.5 ~ 91.8)
        if (lat >= 29.2 && lat <= 30.2 && lng >= 90.5 && lng <= 91.8) {
            return new CityResult("拉萨市", "043");
        }
        // 贵州兴义 (24.8 ~ 25.4, 104.7 ~ 105.2)
        if (lat >= 24.8 && lat <= 25.4 && lng >= 104.7 && lng <= 105.2) {
            return new CityResult("兴义市", "兴义市");
        }
        // 贵州贵阳 (26.3 ~ 26.9, 106.4 ~ 107.0)
        if (lat >= 26.3 && lat <= 26.9 && lng >= 106.4 && lng <= 107.0) {
            return new CityResult("贵阳市", "085");
        }
        // 四川成都 (30.4 ~ 30.9, 103.8 ~ 104.3)
        if (lat >= 30.4 && lat <= 30.9 && lng >= 103.8 && lng <= 104.3) {
            return new CityResult("成都市", "075");
        }
        // 北京 (39.7 ~ 40.2, 116.1 ~ 116.7)
        if (lat >= 39.7 && lat <= 40.2 && lng >= 116.1 && lng <= 116.7) {
            return new CityResult("北京市", "001");
        }
        // 上海 (31.0 ~ 31.4, 121.2 ~ 121.8)
        if (lat >= 31.0 && lat <= 31.4 && lng >= 121.2 && lng <= 121.8) {
            return new CityResult("上海市", "002");
        }
        // 广州 (23.0 ~ 23.4, 113.1 ~ 113.6)
        if (lat >= 23.0 && lat <= 23.4 && lng >= 113.1 && lng <= 113.6) {
            return new CityResult("广州市", "003");
        }
        // 深圳 (22.4 ~ 22.8, 113.8 ~ 114.4)
        if (lat >= 22.4 && lat <= 22.8 && lng >= 113.8 && lng <= 114.4) {
            return new CityResult("深圳市", "004");
        }
        return null;
    }

    private static String normalizeCityName(String raw) {
        if (raw == null) return "拉萨市";
        String clean = raw.trim();
        // 剔除藏语或其他语言后缀
        if (clean.contains(" ")) {
            clean = clean.split(" ")[0];
        }
        if (!clean.endsWith("市") && !clean.endsWith("州") && !clean.endsWith("区")) {
            clean = clean + "市";
        }
        return clean;
    }
}

