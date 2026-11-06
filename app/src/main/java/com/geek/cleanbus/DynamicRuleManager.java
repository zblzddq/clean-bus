package com.geek.cleanbus;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 动态规则配置中心管理器 (工业级高可用容错引擎)
 * 1. A/B 双槽备份 (Slot_Active + Slot_Backup)
 * 2. 故障域隔离 (车来了与掌上公交独立版本)
 * 3. 候选槽 + 动静分离试探 (Pre-flight Probe, 不怕夜间收班)
 * 4. 连续失败熔断与 15 分钟冷却防抖
 * 5. HMAC-SHA256 签名校验与域名白名单防劫持
 */
public class DynamicRuleManager {

    private static final String PREF_NAME = "cleanbus_dynamic_rules";
    private static volatile DynamicRuleManager sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    // 默认出厂基线硬编码
    public static final String DEFAULT_CHELAILE_BASE_URL = "https://web.chelaile.net.cn/api";
    public static final String DEFAULT_CHELAILE_SALT = "qwihrnbtmj";
    public static final String DEFAULT_CHELAILE_AES_KEY = "FF32AE65FBFD19414EAAFF6291A54B42";
    public static final int DEFAULT_CHELAILE_VER = 100;

    public static final String DEFAULT_MYGOLBS_URL = "https://h5.mygolbs.com/ApiData.do";
    public static final int DEFAULT_MYGOLBS_VER = 100;

    // 客户端内置签名校验密钥 (与 Worker RULES_SECRET 保持一致，不走网络)
    public static final String RULES_SECRET = "cleanbus_dynamic_rules_hmac_sha256_key";

    // 冷却时间与连续失败计数
    private long mLastCheckTimestamp = 0;
    private static final long COOLDOWN_MS = 15 * 60 * 1000; // 15分钟冷却
    private int mConsecutiveFailures = 0;

    private DynamicRuleManager(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static DynamicRuleManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (DynamicRuleManager.class) {
                if (sInstance == null) {
                    sInstance = new DynamicRuleManager(context);
                }
            }
        }
        return sInstance;
    }

    public static DynamicRuleManager getInstance() {
        return sInstance;
    }

    // --- 车来了动态配置 ---
    public String getChelaileBaseUrl() {
        return mPrefs.getString("chelaile_base_url", DEFAULT_CHELAILE_BASE_URL);
    }

    public String getChelaileSalt() {
        return mPrefs.getString("chelaile_salt", DEFAULT_CHELAILE_SALT);
    }

    public String getChelaileAesKey() {
        return mPrefs.getString("chelaile_aes_key", DEFAULT_CHELAILE_AES_KEY);
    }

    public int getChelaileVersion() {
        return mPrefs.getInt("chelaile_version", DEFAULT_CHELAILE_VER);
    }

    // --- 掌上公交动态配置 ---
    public String getMyGoLbsUrl() {
        return mPrefs.getString("mygolbs_url", DEFAULT_MYGOLBS_URL);
    }

    public int getMyGoLbsVersion() {
        return mPrefs.getInt("mygolbs_version", DEFAULT_MYGOLBS_VER);
    }

    /**
     * 运行时记录错误。若遇到 HTTP 400/403/验签报错且非普通网络超时，计入熔断器
     */
    public synchronized void recordApiFailure(String sourceId, Exception error) {
        if (error == null) return;
        String msg = error.getMessage() != null ? error.getMessage().toLowerCase() : "";
        // 排除掉单纯的进电梯超时
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return;
        }

        mConsecutiveFailures++;
        if (mConsecutiveFailures >= 3) {
            long now = System.currentTimeMillis();
            if (now - mLastCheckTimestamp > COOLDOWN_MS) {
                mLastCheckTimestamp = now;
                mConsecutiveFailures = 0;
                // 异步唤醒云端自愈同步
                triggerAsyncSync(false);
            }
        }
    }

    public synchronized void recordApiSuccess(String sourceId) {
        mConsecutiveFailures = 0;
    }

    /**
     * 异步触发向 Cloudflare 检查最新规则
     */
    public void triggerAsyncSync(final boolean force) {
        mExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String gatewayUrl = BusRepository.DEFAULT_GATEWAY_URL;
                URL url = new URL(gatewayUrl + "/api/v1/rules");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

                if (conn.getResponseCode() != 200) return;

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream is = conn.getInputStream()) {
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        baos.write(buf, 0, n);
                    }
                }
                String jsonStr = baos.toString("UTF-8");
                JSONObject remoteRules = new JSONObject(jsonStr);

                // 校验与分别试探转正
                processRemoteRules(remoteRules);
            } catch (Exception ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    void processRemoteRules(JSONObject rules) {
        if (rules == null) return;

        // 0. 提取签名与规则载体
        String sig = rules.optString("sig", "");
        if (sig.isEmpty()) {
            // 缺少签名，整包丢弃
            return;
        }

        JSONObject rootObj = rules.has("rules") ? rules.optJSONObject("rules") : rules;
        if (rootObj == null) return;

        JSONObject chelaile = rootObj.optJSONObject("chelaile");
        JSONObject mygolbs = rootObj.optJSONObject("mygolbs");
        if (chelaile == null || mygolbs == null) {
            return;
        }

        int ruleVer = rootObj.optInt("ruleVersion", 0);
        JSONObject toSign = new JSONObject();
        try {
            toSign.put("chelaile", chelaile);
            toSign.put("mygolbs", mygolbs);
            toSign.put("ruleVersion", ruleVer);
        } catch (Exception e) {
            return;
        }

        // 规范化 JSON 并使用 Mac + MessageDigest.isEqual 常量时间校验
        String canonical = canonicalJson(toSign);
        if (!verifyHmacSha256(canonical, RULES_SECRET, sig)) {
            // 验签失败直接丢弃整包，保留内置默认规则
            return;
        }

        // 1. 处理车来了规则热更
        int remoteChelaileVer = chelaile.optInt("version", 0);
        if (remoteChelaileVer > getChelaileVersion()) {
            String newUrl = chelaile.optString("baseUrl", getChelaileBaseUrl());
            String newSalt = chelaile.optString("salt", getChelaileSalt());
            String newKey = chelaile.optString("aesKey", getChelaileAesKey());

            // 域名白名单与 https 协议校验
            if (isAllowedHost(newUrl)) {
                // 候选槽真实试探 (Pre-flight Probe)
                if (probeChelaile(newUrl, newSalt, newKey)) {
                    // 转正生效，并将旧规则放入备份槽
                    backupCurrentChelaile();
                    mPrefs.edit()
                            .putString("chelaile_base_url", newUrl)
                            .putString("chelaile_salt", newSalt)
                            .putString("chelaile_aes_key", newKey)
                            .putInt("chelaile_version", remoteChelaileVer)
                            .apply();
                }
            }
        }

        // 2. 处理掌上公交规则热更 (故障域隔离，互不影响)
        int remoteMyGoLbsVer = mygolbs.optInt("version", 0);
        if (remoteMyGoLbsVer > getMyGoLbsVersion()) {
            String newUrl = mygolbs.optString("url", getMyGoLbsUrl());

            // 域名白名单与 https 协议校验
            if (isAllowedHost(newUrl)) {
                // 真实发 CMD 103 试探且断言 data 非空
                if (probeMyGoLbs(newUrl)) {
                    backupCurrentMyGoLbs();
                    mPrefs.edit()
                            .putString("mygolbs_url", newUrl)
                            .putInt("mygolbs_version", remoteMyGoLbsVer)
                            .apply();
                }
            }
        }
    }

    /**
     * 域名白名单校验：host 必须属于 chelaile.net.cn, mygolbs.com, workers.dev 且协议为 https
     */
    static boolean isAllowedHost(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return false;
        try {
            URL u = new URL(urlStr);
            if (!"https".equalsIgnoreCase(u.getProtocol())) {
                return false;
            }
            String host = u.getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            return isDomainOrSubdomain(host, "chelaile.net.cn")
                    || isDomainOrSubdomain(host, "mygolbs.com")
                    || isDomainOrSubdomain(host, "workers.dev");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isDomainOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /**
     * 规范化 JSON：按键字典序升序递归排序序列化
     */
    static String canonicalJson(Object val) {
        if (val == null || val == JSONObject.NULL) return "null";
        if (val instanceof JSONObject) {
            JSONObject obj = (JSONObject) val;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                keys.add(it.next());
            }
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(",");
                String k = keys.get(i);
                sb.append(quote(k)).append(":").append(canonicalJson(obj.opt(k)));
            }
            sb.append("}");
            return sb.toString();
        } else if (val instanceof JSONArray) {
            JSONArray arr = (JSONArray) val;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append(",");
                sb.append(canonicalJson(arr.opt(i)));
            }
            sb.append("]");
            return sb.toString();
        } else if (val instanceof String) {
            return quote((String) val);
        } else if (val instanceof Number || val instanceof Boolean) {
            return val.toString();
        }
        return quote(val.toString());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /**
     * 常量时间校验 HMAC-SHA256
     */
    static boolean verifyHmacSha256(String data, String secret, String expectedHex) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return MessageDigest.isEqual(
                hex.toString().toLowerCase().getBytes(StandardCharsets.UTF_8),
                expectedHex.toLowerCase().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    private boolean probeChelaile(String url, String salt, String key) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("s", "h5");
            params.put("wxs", "wx_app");
            params.put("sign", "1");
            params.put("h5RealData", "1");
            params.put("v", "3.11.28");
            params.put("src", "weixinapp_cx");
            params.put("ctm_mp", "mp_wx");
            params.put("vc", "2");
            params.put("favoriteGray", "1");
            params.put("gpstype", "wgs");
            params.put("geo_type", "wgs");
            params.put("scene", "1256");
            params.put("cityId", "043");
            params.put("localCityId", "043");
            params.put("key", "16");
            params.put("supportPhyStn", "true");
            params.put("lat", "29.654");
            params.put("lng", "91.118");
            params.put("geo_lat", "29.654");
            params.put("geo_lng", "91.118");

            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) sb.append("&");
                sb.append("\"").append(entry.getKey()).append("\"=\"").append(entry.getValue()).append("\"");
                first = false;
            }
            sb.append(salt);

            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            params.put("cryptoSign", hex.toString());

            String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            StringBuilder urlBuilder = new StringBuilder(base).append("/bus/query!nSearch.action?");
            first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) urlBuilder.append("&");
                urlBuilder.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                          .append("=")
                          .append(URLEncoder.encode(entry.getValue(), "UTF-8"));
                first = false;
            }

            URL reqUrl = new URL(urlBuilder.toString());
            HttpURLConnection conn = (HttpURLConnection) reqUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781");
            conn.setRequestProperty("Referer", "https://servicewechat.com/wx71d589ea01ce3321/814/page-frame.html");
            conn.setRequestProperty("xweb_xhr", "1");
            conn.setRequestProperty("Sec-Fetch-Site", "cross-site");
            conn.setRequestProperty("Sec-Fetch-Mode", "cors");
            conn.setRequestProperty("Sec-Fetch-Dest", "empty");

            if (conn.getResponseCode() != 200) {
                return false;
            }

            InputStream is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            is.close();

            String raw = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            int start = raw.indexOf("{");
            if (start < 0) return false;
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
            if (jsonr == null) return false;
            JSONObject data = jsonr.optJSONObject("data");
            if (data == null) return false;

            JSONObject decryptedObj = data;
            String enc = data.optString("encryptResult", null);
            if (enc != null && !enc.isEmpty() && !"null".equals(enc)) {
                byte[] rawBytes = Base64.decode(enc, Base64.DEFAULT);
                SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
                Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, keySpec);
                byte[] decrypted = cipher.doFinal(rawBytes);
                decryptedObj = new JSONObject(new String(decrypted, StandardCharsets.UTF_8));
            }
            JSONArray lines = decryptedObj.optJSONArray("lines");
            return lines != null && lines.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean probeMyGoLbs(String urlStr) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("CMD", "103");
            params.put("CITYNAME", "兴义市");
            params.put("LINENAME", "1路");
            params.put("DIRECTION", "1");
            params.put("CITYKEY", "");

            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (sb.length() > 0) sb.append("&");
                sb.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                  .append("=")
                  .append(URLEncoder.encode(entry.getValue() != null ? entry.getValue() : "", "UTF-8"));
            }
            byte[] bodyBytes = sb.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("Origin", "https://h5.mygolbs.com");
            conn.setRequestProperty("Referer", "https://h5.mygolbs.com/");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");

            conn.getOutputStream().write(bodyBytes);
            conn.getOutputStream().flush();

            if (conn.getResponseCode() != 200) {
                return false;
            }

            InputStream is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            is.close();

            String resStr = baos.toString("UTF-8").trim();
            JSONObject res = new JSONObject(resStr);
            JSONArray data = res.optJSONArray("data");
            return data != null && data.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void backupCurrentChelaile() {
        mPrefs.edit()
                .putString("bak_chelaile_base_url", getChelaileBaseUrl())
                .putString("bak_chelaile_salt", getChelaileSalt())
                .putString("bak_chelaile_aes_key", getChelaileAesKey())
                .putInt("bak_chelaile_version", getChelaileVersion())
                .apply();
    }

    private void backupCurrentMyGoLbs() {
        mPrefs.edit()
                .putString("bak_mygolbs_url", getMyGoLbsUrl())
                .putInt("bak_mygolbs_version", getMyGoLbsVersion())
                .apply();
    }

    /**
     * 自动回滚到备份槽 (Rollback on failure)
     */
    public void rollbackChelaile() {
        String bakUrl = mPrefs.getString("bak_chelaile_base_url", DEFAULT_CHELAILE_BASE_URL);
        String bakSalt = mPrefs.getString("bak_chelaile_salt", DEFAULT_CHELAILE_SALT);
        String bakKey = mPrefs.getString("bak_chelaile_aes_key", DEFAULT_CHELAILE_AES_KEY);
        int bakVer = mPrefs.getInt("bak_chelaile_version", DEFAULT_CHELAILE_VER);

        mPrefs.edit()
                .putString("chelaile_base_url", bakUrl)
                .putString("chelaile_salt", bakSalt)
                .putString("chelaile_aes_key", bakKey)
                .putInt("chelaile_version", bakVer)
                .apply();
    }
}
