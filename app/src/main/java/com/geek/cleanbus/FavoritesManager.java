package com.geek.cleanbus;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class FavoritesManager {

    private static final String PREF_NAME = "cleanbus_favorites";
    private static final String KEY_FAVS = "favorite_lines";

    public static class FavoriteItem {
        public String lineName;
        public String lineId;
        public String endSn;
        public String stopName;

        public FavoriteItem(String lineName, String lineId, String endSn, String stopName) {
            this.lineName = lineName;
            this.lineId = lineId;
            this.endSn = endSn;
            this.stopName = stopName;
        }

        public String getKey() {
            String clean = lineName != null ? lineName.replace("路", "").trim() : "";
            return clean + "_" + (endSn != null ? endSn.trim() : "");
        }
    }

    private static SharedPreferences getPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized List<FavoriteItem> getFavorites(Context ctx) {
        List<FavoriteItem> list = new ArrayList<>();
        String raw = getPrefs(ctx).getString(KEY_FAVS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    list.add(new FavoriteItem(
                            obj.optString("lineName"),
                            obj.optString("lineId"),
                            obj.optString("endSn"),
                            obj.optString("stopName")
                    ));
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static synchronized boolean isFavorite(Context ctx, String lineName, String endSn) {
        List<FavoriteItem> list = getFavorites(ctx);
        String targetKey = new FavoriteItem(lineName, "", endSn, "").getKey();
        for (FavoriteItem item : list) {
            if (item.getKey().equals(targetKey)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean toggleFavorite(Context ctx, String lineName, String lineId, String endSn, String stopName) {
        List<FavoriteItem> list = getFavorites(ctx);
        String targetKey = new FavoriteItem(lineName, lineId, endSn, stopName).getKey();
        int foundIndex = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getKey().equals(targetKey)) {
                foundIndex = i;
                break;
            }
        }

        boolean nowFav;
        if (foundIndex >= 0) {
            list.remove(foundIndex);
            nowFav = false;
        } else {
            list.add(0, new FavoriteItem(lineName, lineId, endSn, stopName));
            nowFav = true;
        }

        saveList(ctx, list);
        return nowFav;
    }

    private static void saveList(Context ctx, List<FavoriteItem> list) {
        JSONArray arr = new JSONArray();
        for (FavoriteItem item : list) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("lineName", item.lineName);
                obj.put("lineId", item.lineId);
                obj.put("endSn", item.endSn);
                obj.put("stopName", item.stopName);
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        getPrefs(ctx).edit().putString(KEY_FAVS, arr.toString()).apply();
    }
}

