package com.geek.cleanbus;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 统一数据仓库调度器（Facade + Chain of Responsibility）
 * 负责各数据源（车来了、掌上公交、高德等）的分发、降级与容灾
 */
public class BusRepository {

    private static final String PREF_NAME = "cleanbus_repo_prefs";
    private static volatile BusRepository sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final List<IBusDataSource> mSources = new ArrayList<>();
    private final ChelaileSource mChelaileSource;
    private final MyGoLbsSource mMyGoLbsSource;

    private BusRepository(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        mChelaileSource = new ChelaileSource();
        mMyGoLbsSource = new MyGoLbsSource();

        // 注册数据源（车来了与掌上公交双源并立）
        mSources.add(mChelaileSource);
        mSources.add(mMyGoLbsSource);
    }

    public static BusRepository getInstance(Context context) {
        if (sInstance == null) {
            synchronized (BusRepository.class) {
                if (sInstance == null) {
                    sInstance = new BusRepository(context);
                }
            }
        }
        return sInstance;
    }

    public List<IBusDataSource> getRegisteredSources() {
        return Collections.unmodifiableList(mSources);
    }

    /**
     * 获取指定城市绑定的数据源，若未绑定则返回第一优先数据源
     */
    public IBusDataSource getSourceForCity(String cityId) {
        String savedSourceId = mPrefs.getString("city_source_" + cityId, null);
        if (savedSourceId != null) {
            for (IBusDataSource source : mSources) {
                if (source.getSourceId().equals(savedSourceId)) {
                    return source;
                }
            }
        }
        return mSources.get(0);
    }

    /**
     * 记忆某城市首选数据源
     */
    public void recordCitySource(String cityId, String sourceId) {
        if (cityId != null && sourceId != null) {
            mPrefs.edit().putString("city_source_" + cityId, sourceId).apply();
        }
    }

    /**
     * 查询周边站点（支持责任链自动降级）
     */
    public JSONObject getNearbyStops(double lat, double lng, String cityId) throws Exception {
        IBusDataSource primary = getSourceForCity(cityId);
        Exception firstEx = null;
        try {
            JSONObject result = primary.getNearbyStops(lat, lng, cityId);
            if (result != null && result.has("nearSts")) {
                JSONArray arr = result.optJSONArray("nearSts");
                if (arr != null && arr.length() > 0) {
                    recordCitySource(cityId, primary.getSourceId());
                    return result;
                }
            }
        } catch (Exception e) {
            firstEx = e;
            if (mSources.size() <= 1) throw e;
        }

        // 若首选源无数据或异常，尝试备用源（如从车来了平滑切到掌上公交）
        for (IBusDataSource source : mSources) {
            if (source.getSourceId().equals(primary.getSourceId())) continue;
            try {
                JSONObject fallback = source.getNearbyStops(lat, lng, cityId);
                if (fallback != null && fallback.has("nearSts")) {
                    JSONArray fArr = fallback.optJSONArray("nearSts");
                    if (fArr != null && fArr.length() > 0) {
                        recordCitySource(cityId, source.getSourceId());
                        return fallback;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 全部降级失败后直接抛出首次异常，避免重复请求 primary
        if (firstEx != null) {
            throw firstEx;
        }
        JSONObject empty = new JSONObject();
        empty.put("nearSts", new JSONArray());
        return empty;
    }

    /**
     * 站点实时到站补齐
     */
    public JSONObject getStopDetail(String physicalStId, String namesakeStId, String firstLineId, String cityId, double lat, double lng) throws Exception {
        if (firstLineId != null && firstLineId.contains("@@@")) {
            return mMyGoLbsSource.getStopDetail(physicalStId, namesakeStId, firstLineId, cityId, lat, lng);
        }
        return getSourceForCity(cityId).getStopDetail(physicalStId, namesakeStId, firstLineId, cityId, lat, lng);
    }

    public static final String DEFAULT_GATEWAY_URL = "https://cleanbus.277011444.workers.dev";

    public CloudGatewaySource getCloudGateway() {
        String gw = mPrefs.getString("cloud_gateway_url", DEFAULT_GATEWAY_URL);
        if (gw != null && !gw.isEmpty()) {
            return new CloudGatewaySource(gw);
        }
        return null;
    }

    /**
     * 线路全线站点与在途车辆详情 (本地极速直连优先，CF 作为终极容灾)
     */
    public JSONObject getLineDetail(String lineId, String cityId, double lat, double lng) throws Exception {
        // 1. 若 lineId 带有 @@@ 特征，表明是掌上公交格式的线路
        if (lineId != null && lineId.contains("@@@")) {
            try {
                JSONObject r = mMyGoLbsSource.getLineDetail(lineId, cityId, lat, lng);
                HealthReporter.recordSuccess("mygolbs");
                return r;
            } catch (Exception e) {
                HealthReporter.recordFailure("mygolbs", classifyFailure(e));
                DynamicRuleManager.getInstance().recordApiFailure("mygolbs", e);
            }
        }

        // 2. 本地首选源（国内直连，80ms极速）
        IBusDataSource primary = getSourceForCity(cityId);
        try {
            JSONObject r = primary.getLineDetail(lineId, cityId, lat, lng);
            HealthReporter.recordSuccess(primary.getSourceId());
            return r;
        } catch (Exception e) {
            HealthReporter.recordFailure(primary.getSourceId(), classifyFailure(e));
            DynamicRuleManager.getInstance().recordApiFailure(primary.getSourceId(), e);
            // 尝试本地备用源
            for (IBusDataSource src : mSources) {
                if (src.getSourceId().equals(primary.getSourceId())) continue;
                try {
                    return src.getLineDetail(lineId, cityId, lat, lng);
                } catch (Exception ignored) {}
            }

            // 3. 本地全挂时，唤醒 Cloudflare 边缘网关终极兜底
            CloudGatewaySource gw = getCloudGateway();
            if (gw != null) {
                try {
                    return gw.getLineDetail(lineId, cityId, lat, lng);
                } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    /**
     * 搜索线路（本地极速直连优先，CF 终极兜底）
     */
    public JSONObject search(String keyword, String cityId, double lat, double lng) throws Exception {
        // 1. 本地首选源搜索
        IBusDataSource primary = getSourceForCity(cityId);
        JSONObject res = null;
        try {
            res = primary.search(keyword, cityId, lat, lng);
            HealthReporter.recordSuccess(primary.getSourceId());
        } catch (Exception e) {
            HealthReporter.recordFailure(primary.getSourceId(), classifyFailure(e));
        }

        JSONArray pLines = res != null ? res.optJSONArray("lines") : null;
        if (pLines != null && pLines.length() > 0) {
            return res;
        }

        // 2. 本地备用源搜索 (如车来了无结果切到掌上公交)
        for (IBusDataSource source : mSources) {
            if (source.getSourceId().equals(primary.getSourceId())) continue;
            try {
                JSONObject sRes = source.search(keyword, cityId, lat, lng);
                JSONArray sLines = sRes != null ? sRes.optJSONArray("lines") : null;
                if (sLines != null && sLines.length() > 0) {
                    recordCitySource(cityId, source.getSourceId());
                    return sRes;
                }
            } catch (Exception ignored) {}
        }

        // 3. 本地两家都无结果，尝试云端网关
        CloudGatewaySource gw = getCloudGateway();
        if (gw != null) {
            try {
                JSONObject gRes = gw.search(keyword, cityId, lat, lng);
                JSONArray gLines = gRes != null ? gRes.optJSONArray("lines") : null;
                if (gLines != null && gLines.length() > 0) {
                    return gRes;
                }
            } catch (Exception ignored) {}
        }

        return res != null ? res : new JSONObject();
    }

    /** 把异常归类成 Worker 侧认的 kind，避免把堆栈原样外发 */
    private static String classifyFailure(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return "timeout";
        if (e instanceof java.io.IOException) return "http-error";
        if (e instanceof org.json.JSONException) return "parse-fail";
        return "parse-fail";
    }
}
