package com.geek.cleanbus;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class ChelaileApi {

    private static String getBaseUrl() {
        DynamicRuleManager mgr = DynamicRuleManager.getInstance();
        return mgr != null ? mgr.getChelaileBaseUrl() : "https://web.chelaile.net.cn/api";
    }

    private static String getSignSalt() {
        DynamicRuleManager mgr = DynamicRuleManager.getInstance();
        return mgr != null ? mgr.getChelaileSalt() : "qwihrnbtmj";
    }

    private static String getAesKey() {
        DynamicRuleManager mgr = DynamicRuleManager.getInstance();
        return mgr != null ? mgr.getChelaileAesKey() : "FF32AE65FBFD19414EAAFF6291A54B42";
    }

    public static JSONObject getNearbyStops(double lat, double lng, String cityId) throws Exception {
        Map<String, String> params = getCommonParams();
        params.put("cityId", cityId != null && !cityId.isEmpty() ? cityId : "043");
        params.put("localCityId", "undefined");
        params.put("lat", String.valueOf(lat));
        params.put("lng", String.valueOf(lng));
        params.put("geo_lat", String.valueOf(lat));
        params.put("geo_lng", String.valueOf(lng));
        params.put("type", "5");
        params.put("permission", "0");

        return doRequest(getBaseUrl() + "/bus/stop!encryptedHomePage.action", params);
    }

    public static JSONObject getStopDetail(String physicalStId, String namesakeStId, String firstLineId, String cityId, double lat, double lng) throws Exception {
        Map<String, String> params = getCommonParams();
        params.put("cityId", cityId != null && !cityId.isEmpty() ? cityId : "043");
        params.put("localCityId", cityId != null && !cityId.isEmpty() ? cityId : "043");
        params.put("physicalStId", physicalStId);
        params.put("namesakeStId", namesakeStId != null ? namesakeStId : "");
        params.put("firstLineId", firstLineId != null ? firstLineId : "");
        params.put("stationId", "");
        params.put("lat", lat > 0 ? String.valueOf(lat) : "");
        params.put("lng", lng > 0 ? String.valueOf(lng) : "");
        params.put("geo_lat", lat > 0 ? String.valueOf(lat) : "");
        params.put("geo_lng", lng > 0 ? String.valueOf(lng) : "");

        return doRequest(getBaseUrl() + "/bus/stop!encryptedPhyStnDetail.action", params);
    }

    public static JSONObject getLineDetail(String lineId, String cityId, double lat, double lng) throws Exception {
        Map<String, String> params = getCommonParams();
        params.put("cityId", cityId != null && !cityId.isEmpty() ? cityId : "043");
        params.put("localCityId", cityId != null && !cityId.isEmpty() ? cityId : "043");
        params.put("lineId", lineId);
        params.put("lat", lat > 0 ? String.valueOf(lat) : "");
        params.put("lng", lng > 0 ? String.valueOf(lng) : "");
        params.put("geo_lat", lat > 0 ? String.valueOf(lat) : "");
        params.put("geo_lng", lng > 0 ? String.valueOf(lng) : "");

        return doRequest(getBaseUrl() + "/bus/line!encryptedLineDetail.action", params);
    }

    public static JSONObject searchLines(String keyword, String cityId, double lat, double lng) throws Exception {
        Map<String, String> params = getCommonParams();
        params.put("cityId", cityId != null && !cityId.isEmpty() ? cityId : "043");
        params.put("localCityId", cityId != null && !cityId.isEmpty() ? cityId : "043");
        params.put("key", keyword);
        params.put("supportPhyStn", "true");
        if (lat > 0) {
            params.put("lat", String.valueOf(lat));
            params.put("lng", String.valueOf(lng));
            params.put("geo_lat", String.valueOf(lat));
            params.put("geo_lng", String.valueOf(lng));
        }
        return doRequest(getBaseUrl() + "/bus/query!nSearch.action", params);
    }

    private static Map<String, String> getCommonParams() {
        Map<String, String> map = new HashMap<>();
        map.put("s", "h5");
        map.put("wxs", "wx_app");
        map.put("sign", "1");
        map.put("h5RealData", "1");
        map.put("v", "3.11.28");
        map.put("src", "weixinapp_cx");
        map.put("ctm_mp", "mp_wx");
        map.put("vc", "2");
        map.put("favoriteGray", "1");
        map.put("gpstype", "wgs");
        map.put("geo_type", "wgs");
        map.put("scene", "1256");
        return map;
    }

    private static JSONObject doRequest(String endpoint, Map<String, String> params) throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) sb.append("&");
            sb.append("\"").append(entry.getKey()).append("\"=\"").append(entry.getValue()).append("\"");
            first = false;
        }
        sb.append(getSignSalt());
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] digest = md5.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        params.put("cryptoSign", hex.toString());

        StringBuilder urlBuilder = new StringBuilder(endpoint).append("?");
        first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) urlBuilder.append("&");
            urlBuilder.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                      .append("=")
                      .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            first = false;
        }

        URL url = new URL(urlBuilder.toString());
        HttpURLConnection conn = null;
        String raw;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781");
            conn.setRequestProperty("Referer", "https://servicewechat.com/wx71d589ea01ce3321/814/page-frame.html");
            conn.setRequestProperty("xweb_xhr", "1");
            conn.setRequestProperty("Sec-Fetch-Site", "cross-site");
            conn.setRequestProperty("Sec-Fetch-Mode", "cors");
            conn.setRequestProperty("Sec-Fetch-Dest", "empty");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
            }
            raw = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        int start = raw.indexOf("{");
        if (start < 0) return new JSONObject();
        int depth = 0;
        int end = start;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i + 1;
                    break;
                }
            }
        }

        JSONObject envelope = new JSONObject(raw.substring(start, end));
        JSONObject jsonr = envelope.optJSONObject("jsonr");
        if (jsonr == null) return new JSONObject();
        JSONObject data = jsonr.optJSONObject("data");
        if (data == null) return new JSONObject();

        if (!data.isNull("encryptResult")) {
            String enc = data.optString("encryptResult", null);
            if (enc != null && !enc.isEmpty() && !"null".equalsIgnoreCase(enc)) {
                try {
                    byte[] rawBytes = Base64.decode(enc, Base64.DEFAULT);
                    SecretKeySpec key = new SecretKeySpec(getAesKey().getBytes(StandardCharsets.UTF_8), "AES");
                    Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                    cipher.init(Cipher.DECRYPT_MODE, key);
                    byte[] decrypted = cipher.doFinal(rawBytes);
                    return new JSONObject(new String(decrypted, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    // 解密失败降级为空 JSONObject 而不是抛崩
                    return new JSONObject();
                }
            }
        }
        return data;
    }
}
