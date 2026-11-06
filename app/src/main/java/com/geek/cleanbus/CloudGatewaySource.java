package com.geek.cleanbus;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Cloudflare Workers 边缘免发版网关数据源实现
 * 支持毫秒级熔断降级 (1500ms 超时限制)
 */
public class CloudGatewaySource implements IBusDataSource {

    public static final String ID = "cloud_gateway";
    private final String mGatewayBaseUrl;

    public CloudGatewaySource(String gatewayBaseUrl) {
        this.mGatewayBaseUrl = gatewayBaseUrl != null ? gatewayBaseUrl.replaceAll("/+$", "") : "";
    }

    public boolean isConfigured() {
        return mGatewayBaseUrl != null && !mGatewayBaseUrl.isEmpty();
    }

    @Override
    public String getSourceId() {
        return ID;
    }

    @Override
    public String getSourceName() {
        return "Cloudflare 边缘网关";
    }

    private JSONObject doGet(String endpoint) throws Exception {
        URL url = new URL(mGatewayBaseUrl + endpoint);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500); // 1.5s 快速超时熔断
            conn.setReadTimeout(2000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

            int code = conn.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("Gateway error: " + code);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = conn.getInputStream()) {
                byte[] buf = new byte[2048];
                int n;
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
            }
            return new JSONObject(baos.toString("UTF-8"));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Override
    public JSONObject getNearbyStops(double lat, double lng, String cityId) throws Exception {
        // 周边站点直连本地更快，或走网关聚合
        throw new UnsupportedOperationException("Fallback to local");
    }

    @Override
    public JSONObject getStopDetail(String physicalStId, String namesakeStId, String firstLineId, String cityId, double lat, double lng) throws Exception {
        throw new UnsupportedOperationException("Fallback to local");
    }

    @Override
    public JSONObject getLineDetail(String lineId, String cityId, double lat, double lng) throws Exception {
        String ep = "/api/v1/lineDetail?lineId=" + URLEncoder.encode(lineId, "UTF-8")
                + "&city=" + URLEncoder.encode(cityId, "UTF-8");
        return doGet(ep);
    }

    @Override
    public JSONObject search(String keyword, String cityId, double lat, double lng) throws Exception {
        String ep = "/api/v1/search?keyword=" + URLEncoder.encode(keyword, "UTF-8")
                + "&city=" + URLEncoder.encode(cityId, "UTF-8");
        return doGet(ep);
    }
}

