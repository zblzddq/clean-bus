package com.geek.cleanbus;

import org.json.JSONObject;

/**
 * 统一公交数据源契约
 * 所有数据提供方（车来了、掌上公交、高德、Cloudflare Workers网关）均实现此接口
 */
public interface IBusDataSource {

    /**
     * 数据源唯一标识（如 "chelaile", "mygolbs", "amap", "cloud_gateway"）
     */
    String getSourceId();

    /**
     * 数据源显示名称（如 "车来了", "掌上公交"）
     */
    String getSourceName();

    /**
     * 周边站点与到站信息
     */
    JSONObject getNearbyStops(double lat, double lng, String cityId) throws Exception;

    /**
     * 站点全量线路实时到站补齐
     */
    JSONObject getStopDetail(String physicalStId, String namesakeStId, String firstLineId, String cityId, double lat, double lng) throws Exception;

    /**
     * 线路全线站点与在途车辆详情
     */
    JSONObject getLineDetail(String lineId, String cityId, double lat, double lng) throws Exception;

    /**
     * 线路与站点关键词搜索
     */
    JSONObject search(String keyword, String cityId, double lat, double lng) throws Exception;
}
