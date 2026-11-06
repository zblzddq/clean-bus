package com.geek.cleanbus;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 上游健康上报。
 *
 * 目的：车来了的小程序密钥无法匿名嗅探，规则热更只能靠人工。比"自动自愈"更实际的是
 * 把"用户静默拿到空数据"变成"运维能看见的告警"。
 *
 * 策略：
 *  - 同一数据源 10 分钟内连续失败 >= 3 次才上报一次（抖动不报）
 *  - 每个源每小时最多上报 1 次（避免刷爆 Worker 配额）
 *  - 非阻塞：单线程守护线程池，失败静默
 */
final class HealthReporter {

    private static final long WINDOW_MS = 10 * 60 * 1000L;
    private static final int THRESHOLD = 3;
    private static final long COOLDOWN_MS = 60 * 60 * 1000L;

    private static final Map<String, long[]> STATE = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> LAST_SENT = new ConcurrentHashMap<>();

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cleanbus-health");
        t.setDaemon(true);
        return t;
    });

    private HealthReporter() {
    }

    static void recordFailure(String source, String kind) {
        source = normalize(source);
        if (source == null) return;
        long now = System.currentTimeMillis();
        long[] st = STATE.get(source);
        if (st == null) {
            st = new long[]{0L, now};
            STATE.put(source, st);
        }
        synchronized (st) {
            if (now - st[1] > WINDOW_MS) {
                // 窗口过期，重新计数
                st[0] = 0;
                st[1] = now;
            }
            st[0]++;
            if (st[0] < THRESHOLD) return;
        }
        AtomicLong last = LAST_SENT.get(source);
        if (last != null && now - last.get() < COOLDOWN_MS) return;
        if (last == null) {
            last = new AtomicLong(0);
            LAST_SENT.put(source, last);
        }
        if (now - last.get() < COOLDOWN_MS) return;
        last.set(now);
        send(source, kind);
    }

    static void recordSuccess(String source) {
        source = normalize(source);
        long[] st = source == null ? null : STATE.get(source);
        if (st != null) {
            synchronized (st) {
                st[0] = 0;
            }
        }
    }

    /** 数据源 id（中文）映射到 Worker 侧枚举 */
    private static String normalize(String source) {
        if (source == null) return null;
        if ("车来了".equals(source)) return "chelaile";
        if ("掌上公交".equals(source)) return "mygolbs";
        return null;
    }

    private static void send(final String source, final String kind) {
        EXEC.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String base = BusRepository.DEFAULT_GATEWAY_URL;
                URL url = new URL(base + "/api/v1/report");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                String body = "{\"source\":\"" + source + "\",\"kind\":\"" + kind + "\"}";
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                conn.getResponseCode();
            } catch (Exception ignored) {
                // 上报失败不影响业务
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
