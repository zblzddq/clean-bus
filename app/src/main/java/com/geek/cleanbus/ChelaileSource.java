package com.geek.cleanbus;

import org.json.JSONObject;

/**
 * 车来了数据源实现
 */
public class ChelaileSource implements IBusDataSource {

    public static final String ID = "chelaile";

    @Override
    public String getSourceId() {
        return ID;
    }

    @Override
    public String getSourceName() {
        return "车来了";
    }

    @Override
    public JSONObject getNearbyStops(double lat, double lng, String cityId) throws Exception {
        return ChelaileApi.getNearbyStops(lat, lng, cityId);
    }

    @Override
    public JSONObject getStopDetail(String physicalStId, String namesakeStId, String firstLineId, String cityId, double lat, double lng) throws Exception {
        return ChelaileApi.getStopDetail(physicalStId, namesakeStId, firstLineId, cityId, lat, lng);
    }

    @Override
    public JSONObject getLineDetail(String lineId, String cityId, double lat, double lng) throws Exception {
        return ChelaileApi.getLineDetail(lineId, cityId, lat, lng);
    }

    @Override
    public JSONObject search(String keyword, String cityId, double lat, double lng) throws Exception {
        return ChelaileApi.searchLines(keyword, cityId, lat, lng);
    }
}
