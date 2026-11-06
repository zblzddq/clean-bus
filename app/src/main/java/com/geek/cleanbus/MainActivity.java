package com.geek.cleanbus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int AUTO_REFRESH_INTERVAL_MS = 10000; // 10秒自动刷新

    private LocationManager mLocationManager;
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(4);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private double mCurrentLat = 29.654;
    private double mCurrentLng = 91.118;
    private String mCityId = "043";
    private String mCityName = "拉萨市";

    private LinearLayout mTopBar;
    private FrameLayout mContentFrame;
    private TextView mTvLocStatus;
    private LinearLayout mStopsContainer;
    private ProgressBar mProgressBar;
    private EditText mEtSearch;
    private boolean mIsSearchMode = false;

    private final Runnable mAutoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !isDestroyed()) {
                if (!mIsSearchMode) {
                    loadNearbyStops(true);
                }
                mMainHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupEdgeToEdge();
        initViews();
        initLocation();
        DynamicRuleManager.getInstance(this).triggerAsyncSync(false);
        loadNearbyStops(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMainHandler.removeCallbacks(mAutoRefreshRunnable);
        mMainHandler.postDelayed(mAutoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
        if (!mIsSearchMode && mCurrentLat != 0.0) {
            loadNearbyStops(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMainHandler.removeCallbacks(mAutoRefreshRunnable);
    }

    @Override
    protected void onDestroy() {
        if (mLocationManager != null && mLocationListener != null) {
            try {
                mLocationManager.removeUpdates(mLocationListener);
            } catch (Exception ignored) {}
        }
        mMainHandler.removeCallbacksAndMessages(null);
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    /**
     * 1. 现代 Android 全面屏沉浸式适配
     */
    private void setupEdgeToEdge() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#0d1117")));
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        // 彻底取消导航栏系统强加的遮罩或对比度黑底
        try {
            java.lang.reflect.Method setNavBarContrast = Window.class.getMethod("setNavigationBarContrastEnforced", boolean.class);
            setNavBarContrast.invoke(window, false);
        } catch (Throwable ignored) {}

        try {
            java.lang.reflect.Method m = Window.class.getMethod("setDecorFitsSystemWindows", boolean.class);
            m.invoke(window, false);
        } catch (Throwable ignored) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void initViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0d1117"));

        // 1. Top Bar
        mTopBar = new LinearLayout(this);
        mTopBar.setOrientation(LinearLayout.HORIZONTAL);
        mTopBar.setGravity(Gravity.CENTER_VERTICAL);
        mTopBar.setBackgroundColor(Color.parseColor("#161b22"));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🚍 纯净实时公交");
        tvTitle.setTextColor(Color.parseColor("#58a6ff"));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tvTitle.setLayoutParams(titleLp);
        mTopBar.addView(tvTitle);

        Button btnRefresh = new Button(this);
        btnRefresh.setText("刷新");
        btnRefresh.setTextColor(Color.WHITE);
        btnRefresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#238636"));
        btnBg.setCornerRadius(dp(6));
        btnRefresh.setBackground(btnBg);
        btnRefresh.setPadding(dp(14), dp(6), dp(14), dp(6));
        btnRefresh.setOnClickListener(v -> loadNearbyStops(false));
        mTopBar.addView(btnRefresh);

        root.addView(mTopBar);

        // 1.5 Search Bar (本地公交线路搜索栏)
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(14), dp(6), dp(14), dp(8));
        searchBar.setBackgroundColor(Color.parseColor("#161b22"));

        mEtSearch = new EditText(this);
        mEtSearch.setHint("🔍 搜索本地线路 (如 16、24)");
        mEtSearch.setHintTextColor(Color.parseColor("#484f58"));
        mEtSearch.setTextColor(Color.WHITE);
        mEtSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mEtSearch.setSingleLine(true);
        mEtSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setColor(Color.parseColor("#21262d"));
        etBg.setCornerRadius(dp(8));
        etBg.setStroke(dp(1), Color.parseColor("#30363d"));
        mEtSearch.setBackground(etBg);
        mEtSearch.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        mEtSearch.setLayoutParams(etLp);
        searchBar.addView(mEtSearch);

        Button btnSearch = new Button(this);
        btnSearch.setText("搜索");
        btnSearch.setTextColor(Color.WHITE);
        btnSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable searchBtnBg = new GradientDrawable();
        searchBtnBg.setColor(Color.parseColor("#1f6feb"));
        searchBtnBg.setCornerRadius(dp(6));
        btnSearch.setBackground(searchBtnBg);
        btnSearch.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.setMargins(dp(8), 0, 0, 0);
        btnSearch.setLayoutParams(btnLp);

        mEtSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch(mEtSearch.getText().toString().trim());
                return true;
            }
            return false;
        });

        btnSearch.setOnClickListener(v -> doSearch(mEtSearch.getText().toString().trim()));
        searchBar.addView(btnSearch);

        root.addView(searchBar);

        // 1.8 快捷线路/站点标签栏
        android.widget.HorizontalScrollView tagScroll = new android.widget.HorizontalScrollView(this);
        tagScroll.setHorizontalScrollBarEnabled(false);
        tagScroll.setBackgroundColor(Color.parseColor("#161b22"));
        tagScroll.setPadding(dp(14), 0, dp(14), dp(8));

        LinearLayout tagLayout = new LinearLayout(this);
        tagLayout.setOrientation(LinearLayout.HORIZONTAL);

        String[] quickTags = new String[]{"16路", "24路", "1路", "2路", "3路", "8路", "堆龙人社局", "德庆镇"};
        for (final String tag : quickTags) {
            TextView tvTag = new TextView(this);
            tvTag.setText(tag);
            tvTag.setTextColor(Color.parseColor("#79c0ff"));
            tvTag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            GradientDrawable tagBg = new GradientDrawable();
            tagBg.setColor(Color.parseColor("#21262d"));
            tagBg.setCornerRadius(dp(12));
            tagBg.setStroke(dp(1), Color.parseColor("#30363d"));
            tvTag.setBackground(tagBg);
            tvTag.setPadding(dp(10), dp(4), dp(10), dp(4));
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tLp.setMargins(0, 0, dp(8), 0);
            tvTag.setLayoutParams(tLp);
            tvTag.setClickable(true);
            tvTag.setOnClickListener(v -> {
                mEtSearch.setText(tag);
                doSearch(tag);
            });
            tagLayout.addView(tvTag);
        }
        tagScroll.addView(tagLayout);
        root.addView(tagScroll);

        // 2. Status Bar
        mTvLocStatus = new TextView(this);
        mTvLocStatus.setTextColor(Color.parseColor("#8b949e"));
        mTvLocStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mTvLocStatus.setPadding(dp(16), dp(8), dp(16), dp(8));
        mTvLocStatus.setBackgroundColor(Color.parseColor("#11161d"));
        mTvLocStatus.setText("正在通过 GPS 获取当前位置...");
        root.addView(mTvLocStatus);

        // 3. Scroll Container
        mContentFrame = new FrameLayout(this);
        LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        mContentFrame.setLayoutParams(frameLp);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);

        mStopsContainer = new LinearLayout(this);
        mStopsContainer.setOrientation(LinearLayout.VERTICAL);
        mStopsContainer.setPadding(dp(14), dp(12), dp(14), dp(24));
        scrollView.addView(mStopsContainer);

        mContentFrame.addView(scrollView);

        mProgressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        pbLp.gravity = Gravity.CENTER;
        mProgressBar.setLayoutParams(pbLp);
        mContentFrame.addView(mProgressBar);

        root.addView(mContentFrame);

        // 处理全面屏窗口 Insets
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            mTopBar.setPadding(dp(16), top + dp(8), dp(16), dp(8));
            mStopsContainer.setPadding(dp(14), dp(12), dp(14), dp(24) + bottom);
            return insets.consumeSystemWindowInsets();
        });

        setContentView(root);
    }

    private void initLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                }, 100);
                return;
            }
        }
        startLocating();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocating();
        }
    }

    private void startLocating() {
        try {
            mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (mLocationManager != null) {
                Location lastGps = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                Location lastNet = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                Location best = lastGps != null ? lastGps : lastNet;
                if (best != null) {
                    updateLocationAndCity(best.getLatitude(), best.getLongitude());
                    loadNearbyStops(false);
                }

                mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 20, mLocationListener);
                mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 20, mLocationListener);
            }
        } catch (SecurityException ignored) {}
    }

    private void updateLocationAndCity(double lat, double lng) {
        mCurrentLat = lat;
        mCurrentLng = lng;
        mExecutor.execute(() -> {
            CityLocator.CityResult cr = CityLocator.resolveCity(MainActivity.this, lat, lng);
            mMainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                mCityId = cr.chelaileCityId;
                mCityName = cr.cityName;
                mTvLocStatus.setText("📍 " + mCityName + " (GPS: " + String.format("%.3f, %.3f", mCurrentLat, mCurrentLng) + ")");
            });
        });
    }

    private final LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            if (loc != null) {
                updateLocationAndCity(loc.getLatitude(), loc.getLongitude());
            }
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    private void loadNearbyStops(final boolean isSilent) {
        mIsSearchMode = false;
        if (!isSilent) {
            mProgressBar.setVisibility(View.VISIBLE);
        }

        mExecutor.execute(() -> {
            try {
                final JSONObject data = BusRepository.getInstance(MainActivity.this).getNearbyStops(mCurrentLat, mCurrentLng, mCityId);
                mMainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    renderStops(data);
                });
            } catch (final Exception e) {
                mMainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    mProgressBar.setVisibility(View.GONE);
                    if (!isSilent) {
                        Toast.makeText(MainActivity.this, "加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void renderStops(JSONObject data) {
        mProgressBar.setVisibility(View.GONE);
        mStopsContainer.removeAllViews();

        // 1. 收藏线路置顶区
        List<FavoritesManager.FavoriteItem> favs = FavoritesManager.getFavorites(this);
        if (favs != null && !favs.isEmpty()) {
            mStopsContainer.addView(createFavoritesSection(favs));
        }

        JSONArray stops = data.optJSONArray("nearSts");
        if (stops == null || stops.length() == 0) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("周边 1000 米内暂无可用站点数据");
            tvEmpty.setTextColor(Color.parseColor("#8b949e"));
            mStopsContainer.addView(tvEmpty);
            return;
        }

        for (int i = 0; i < stops.length(); i++) {
            JSONObject s = stops.optJSONObject(i);
            if (s == null) continue;

            mStopsContainer.addView(createStopCard(s));
        }
    }

    private View createFavoritesSection(List<FavoritesManager.FavoriteItem> favs) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(cardLp);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#181b20"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#d29922"));
        card.setBackground(bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(10), dp(14), dp(10));
        header.setBackgroundColor(Color.parseColor("#262215"));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("⭐ 我的常乘关注 (" + favs.size() + " 条)");
        tvTitle.setTextColor(Color.parseColor("#f1e05a"));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tvTitle.setLayoutParams(tLp);
        header.addView(tvTitle);

        card.addView(header);

        for (final FavoritesManager.FavoriteItem item : favs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setClickable(true);

            row.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, LineDetailActivity.class);
                intent.putExtra("lineId", item.lineId);
                intent.putExtra("lineName", item.lineName);
                intent.putExtra("endSn", item.endSn);
                intent.putExtra("cityId", mCityId);
                intent.putExtra("lat", mCurrentLat);
                intent.putExtra("lng", mCurrentLng);
                if (item.stopName != null && !item.stopName.isEmpty()) {
                    intent.putExtra("targetStopName", item.stopName);
                }
                startActivity(intent);
            });

            LinearLayout meta = new LinearLayout(this);
            meta.setOrientation(LinearLayout.HORIZONTAL);
            meta.setGravity(Gravity.CENTER_VERTICAL);

            TextView badge = new TextView(this);
            badge.setText(item.lineName != null && item.lineName.endsWith("路") ? item.lineName : item.lineName + "路");
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
            GradientDrawable bBg = new GradientDrawable();
            bBg.setColor(Color.parseColor("#d29922"));
            bBg.setCornerRadius(dp(6));
            badge.setBackground(bBg);
            meta.addView(badge);

            TextView dest = new TextView(this);
            dest.setText("  开往 " + (item.endSn != null ? item.endSn : ""));
            dest.setTextColor(Color.parseColor("#f0f6fc"));
            dest.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            dest.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            dest.setLayoutParams(dLp);
            meta.addView(dest);

            TextView del = new TextView(this);
            del.setText("✕ 取消");
            del.setTextColor(Color.parseColor("#8b949e"));
            del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            del.setPadding(dp(8), dp(2), dp(8), dp(2));
            del.setOnClickListener(v -> {
                FavoritesManager.toggleFavorite(MainActivity.this, item.lineName, item.lineId, item.endSn, item.stopName);
                Toast.makeText(MainActivity.this, "已取消关注", Toast.LENGTH_SHORT).show();
                loadNearbyStops(true);
            });
            meta.addView(del);

            row.addView(meta);

            final TextView tvLive = new TextView(this);
            tvLive.setText("正在拉取实时班次...");
            tvLive.setTextColor(Color.parseColor("#8b949e"));
            tvLive.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvLive.setPadding(dp(8), dp(4), dp(8), dp(4));
            GradientDrawable lBg = new GradientDrawable();
            lBg.setColor(Color.parseColor("#1f242c"));
            lBg.setCornerRadius(dp(4));
            tvLive.setBackground(lBg);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.setMargins(0, dp(6), 0, 0);
            tvLive.setLayoutParams(llp);
            row.addView(tvLive);

            // 异步并发查询关注线路在途数据
            mExecutor.execute(() -> {
                try {
                    JSONObject lineDetail = BusRepository.getInstance(MainActivity.this).getLineDetail(item.lineId, mCityId, mCurrentLat, mCurrentLng);
                    JSONArray buses = lineDetail.optJSONArray("buses");
                    JSONObject lineInfo = lineDetail.optJSONObject("line");
                    final String fTime = lineInfo != null ? lineInfo.optString("firstTime", "") : "";
                    final String lTime = lineInfo != null ? lineInfo.optString("lastTime", "") : "";

                    mMainHandler.post(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (buses != null && buses.length() > 0) {
                            tvLive.setText("📍 全线在途 " + buses.length() + " 辆车 (点击查看实时位置)");
                            tvLive.setTextColor(Color.parseColor("#3fb950"));
                            tvLive.setTypeface(Typeface.DEFAULT_BOLD);
                        } else {
                            tvLive.setText(LineDetailActivity.formatScheduleStatus(fTime, lTime));
                            tvLive.setTextColor(Color.parseColor("#8b949e"));
                        }
                    });
                } catch (Exception ignored) {}
            });

            card.addView(row);
        }

        return card;
    }

    private View createStopCard(final JSONObject stop) {
        final String sn = stop.optString("sn", "未知站点");
        int dist = stop.optInt("distance", 0);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(cardLp);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#161b22"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#30363d"));
        card.setBackground(bg);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(12));
        header.setBackgroundColor(Color.parseColor("#1c2128"));

        TextView tvName = new TextView(this);
        tvName.setText("🚏 " + sn);
        tvName.setTextColor(Color.parseColor("#f0f6fc"));
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tvName.setLayoutParams(nameLp);
        header.addView(tvName);

        TextView tvDist = new TextView(this);
        tvDist.setText(dist > 0 ? "距当前 " + dist + " 米" : "");
        tvDist.setTextColor(Color.parseColor("#79c0ff"));
        tvDist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        header.addView(tvDist);

        card.addView(header);

        // Lines
        JSONArray lines = stop.optJSONArray("lines");
        Map<String, TextView> pendingStatusViews = new HashMap<>();
        boolean hasMissingArrivalData = false;

        if (lines != null) {
            for (int j = 0; j < lines.length(); j++) {
                JSONObject lineObj = lines.optJSONObject(j);
                if (lineObj == null) continue;
                TextView statusTv = new TextView(this);
                card.addView(createLineRow(lineObj, statusTv, sn));

                JSONObject info = lineObj.optJSONObject("line");
                if (info != null) {
                    String lId = info.optString("lineId", "");
                    pendingStatusViews.put(lId, statusTv);
                }

                JSONArray buses = lineObj.optJSONArray("stnStates");
                if (buses == null || buses.length() == 0) {
                    hasMissingArrivalData = true;
                }
            }
        }

        // 异步补齐该站点全量实时公交数据
        if (hasMissingArrivalData) {
            fetchStationDetailArrivals(stop, pendingStatusViews);
        }

        return card;
    }

    private void fetchStationDetailArrivals(final JSONObject stop, final Map<String, TextView> statusViews) {
        final String physicalStId = stop.optString("physicalStId", "");
        if (physicalStId.isEmpty()) return;
        final String namesakeStId = stop.optString("namesakeStId", "");
        final String firstLineId = stop.optString("firstLineId", "");

        mExecutor.execute(() -> {
            try {
                JSONObject res = BusRepository.getInstance(MainActivity.this).getStopDetail(physicalStId, namesakeStId, firstLineId, mCityId, mCurrentLat, mCurrentLng);
                JSONArray stationList = res.optJSONArray("stationList");
                if (stationList != null && stationList.length() > 0) {
                    JSONObject st = stationList.optJSONObject(0);
                    if (st == null) return;
                    JSONArray lines = st.optJSONArray("lines");
                    if (lines != null) {
                        final Map<String, String> arrivalMap = new HashMap<>();
                        for (int i = 0; i < lines.length(); i++) {
                            JSONObject li = lines.optJSONObject(i);
                            if (li == null) continue;
                            JSONObject l = li.optJSONObject("line");
                            if (l == null) continue;
                            String lId = l.optString("lineId", "");
                            JSONArray buses = li.optJSONArray("stnStates");
                            if (buses != null && buses.length() > 0) {
                                JSONObject b = buses.optJSONObject(0);
                                if (b == null) continue;
                                int value = b.optInt("value", -1);
                                int dist = b.optInt("distanceToDest", 0);
                                double travelMin = b.optDouble("travelTime", 0) / 60.0;
                                arrivalMap.put(lId, formatBusArrivalText(value, dist, travelMin));
                            }
                        }

                        if (!arrivalMap.isEmpty()) {
                            mMainHandler.post(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                for (Map.Entry<String, String> entry : arrivalMap.entrySet()) {
                                    TextView tv = statusViews.get(entry.getKey());
                                    if (tv != null) {
                                        tv.setText(entry.getValue());
                                        tv.setTextColor(Color.parseColor("#3fb950"));
                                        tv.setTypeface(Typeface.DEFAULT_BOLD);
                                    }
                                }
                            });
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private View createLineRow(final JSONObject lineItem, final TextView tvStatus, final String currentStationName) {
        final JSONObject info = lineItem.optJSONObject("line");
        if (info == null) return new View(this);

        final String lineId = info.optString("lineId", "");
        final String name = info.optString("name", "");
        final String endSn = info.optString("endSn", "");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setClickable(true);

        // 点击某条线路进入全线车辆详情页，并携带当前点击的乘车站名
        row.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, LineDetailActivity.class);

            // 检查是否有该线路已记忆的方向偏好
            SharedPreferences prefs = getSharedPreferences("cleanbus_prefs", MODE_PRIVATE);
            String cleanName = name.replace("路", "").trim();
            String savedLineId = prefs.getString("pref_lineid_" + cleanName, null);
            String savedEndSn = prefs.getString("pref_endsn_" + cleanName, null);

            if (savedLineId != null && savedEndSn != null && !savedLineId.isEmpty()) {
                intent.putExtra("lineId", savedLineId);
                intent.putExtra("endSn", savedEndSn);
            } else {
                intent.putExtra("lineId", lineId);
                intent.putExtra("endSn", endSn);
            }

            intent.putExtra("lineName", name);
            intent.putExtra("cityId", mCityId);
            intent.putExtra("lat", mCurrentLat);
            intent.putExtra("lng", mCurrentLng);
            intent.putExtra("targetStopName", currentStationName);
            startActivity(intent);
        });

        // Top line info: [Badge] Destination >
        LinearLayout metaLine = new LinearLayout(this);
        metaLine.setOrientation(LinearLayout.HORIZONTAL);
        metaLine.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText(name.endsWith("路") ? name : name + "路");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#1f6feb"));
        badgeBg.setCornerRadius(dp(6));
        badge.setBackground(badgeBg);
        metaLine.addView(badge);

        TextView tvDest = new TextView(this);
        tvDest.setText("  开往 " + endSn);
        tvDest.setTextColor(Color.parseColor("#c9d1d9"));
        tvDest.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvDest.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams destLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tvDest.setLayoutParams(destLp);
        metaLine.addView(tvDest);

        TextView tvArrow = new TextView(this);
        tvArrow.setText("实时车辆 ›");
        tvArrow.setTextColor(Color.parseColor("#58a6ff"));
        tvArrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        metaLine.addView(tvArrow);

        // 线路收藏星标切换
        final TextView tvFav = new TextView(this);
        final boolean isFav = FavoritesManager.isFavorite(this, name, endSn);
        tvFav.setText(isFav ? "★" : "☆");
        tvFav.setTextColor(isFav ? Color.parseColor("#f1e05a") : Color.parseColor("#8b949e"));
        tvFav.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvFav.setPadding(dp(8), dp(2), dp(4), dp(2));
        tvFav.setClickable(true);
        tvFav.setOnClickListener(v -> {
            boolean nowFav = FavoritesManager.toggleFavorite(MainActivity.this, name, lineId, endSn, currentStationName);
            tvFav.setText(nowFav ? "★" : "☆");
            tvFav.setTextColor(nowFav ? Color.parseColor("#f1e05a") : Color.parseColor("#8b949e"));
            Toast.makeText(MainActivity.this, nowFav ? "已置顶关注该线路" : "已取消关注", Toast.LENGTH_SHORT).show();
            loadNearbyStops(true);
        });
        metaLine.addView(tvFav);

        row.addView(metaLine);

        // Bottom real-time bus status
        JSONArray buses = lineItem.optJSONArray("stnStates");
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvStatus.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(6), 0, 0);
        tvStatus.setLayoutParams(statusLp);

        GradientDrawable statusBg = new GradientDrawable();
        statusBg.setColor(Color.parseColor("#21262d"));
        statusBg.setCornerRadius(dp(6));
        tvStatus.setBackground(statusBg);

        JSONObject b0 = (buses != null && buses.length() > 0) ? buses.optJSONObject(0) : null;
        if (b0 != null) {
            boolean dev0 = isBusDeviated(b0);
            if (!dev0) {
                int value = b0.optInt("value", -1);
                int dist = b0.optInt("distanceToDest", 0);
                double travelMin = b0.optDouble("travelTime", 0) / 60.0;
                tvStatus.setText(formatBusArrivalText(value, dist, travelMin));
                tvStatus.setTextColor(Color.parseColor("#3fb950"));
            } else {
                // 最近车辆偏离，寻找下一辆正常车
                int val0 = b0.optInt("value", -1);
                String firstDesc = "⚠️ 最近车(" + (val0 <= 0 ? "即将到站" : "还有 " + val0 + " 站") + ")偏离路线";
                JSONObject nextNormal = null;
                for (int k = 1; k < buses.length(); k++) {
                    JSONObject bk = buses.optJSONObject(k);
                    if (!isBusDeviated(bk)) {
                        nextNormal = bk;
                        break;
                    }
                }
                if (nextNormal != null) {
                    int valNext = nextNormal.optInt("value", -1);
                    int distNext = nextNormal.optInt("distanceToDest", 0);
                    double minNext = nextNormal.optDouble("travelTime", 0) / 60.0;
                    tvStatus.setText(firstDesc + " | 下一辆: 还有 " + valNext + " 站 (约 " + String.format("%.1f", minNext) + " 分钟)");
                } else {
                    tvStatus.setText(firstDesc + "，暂无后续正常班次");
                }
                tvStatus.setTextColor(Color.parseColor("#f0883e"));
            }
            tvStatus.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            String fTime = info.optString("firstTime", "");
            String lTime = info.optString("lastTime", "");
            String desc = lineItem.optString("preArrivalTime", "");
            if (!fTime.isEmpty() && !lTime.isEmpty()) {
                tvStatus.setText(LineDetailActivity.formatScheduleStatus(fTime, lTime));
            } else if (!desc.isEmpty()) {
                tvStatus.setText("⏳ " + desc);
            } else {
                tvStatus.setText("⏳ " + info.optString("shortDesc", "等待发车 / 暂无在途车辆"));
            }
            tvStatus.setTextColor(Color.parseColor("#8b949e"));
        }

        row.addView(tvStatus);
        return row;
    }

    public static boolean isBusDeviated(JSONObject bus) {
        if (bus == null) return false;
        String statusDesc = bus.optString("busStatusDesc", "");
        String awayDesc = bus.optString("busAwayDesc", "");
        String delayDesc = bus.optString("delayDesc", "");
        int awayTag = bus.optInt("awayTag", 0);

        if (awayTag == -1) return true;
        if (statusDesc.contains("偏离") || statusDesc.contains("偏移")) return true;
        if (awayDesc.contains("偏离") || awayDesc.contains("偏移")) return true;
        if (delayDesc.contains("偏离") || delayDesc.contains("偏移")) return true;
        return false;
    }

    private String formatBusArrivalText(int value, int distance, double travelMin) {
        String valStr;
        // 当 value <= 0 或距离在 80 米以内，统一视为即将到站，杜绝显示 -1 站
        if (value <= 0 || (distance > 0 && distance <= 80)) {
            valStr = "即将到站";
        } else {
            valStr = "还有 " + value + " 站";
        }

        StringBuilder sb = new StringBuilder("📍 ").append(valStr);
        if (travelMin > 0.05 || distance > 0) {
            sb.append("  (");
            if (travelMin > 0.05) {
                sb.append("约 ").append(String.format("%.1f", travelMin)).append(" 分钟");
                if (distance > 0) sb.append("，");
            }
            if (distance > 0) {
                sb.append("距 ").append(distance).append(" 米");
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        }
    }

    private void doSearch(final String keyword) {
        if (keyword.isEmpty()) {
            mIsSearchMode = false;
            loadNearbyStops(false);
            return;
        }

        mIsSearchMode = true;
        hideKeyboard();
        mProgressBar.setVisibility(View.VISIBLE);
        mTvLocStatus.setText("正在搜索线路: \"" + keyword + "\" ...");

        final String finalQuery;
        final String searchCity;
        if (keyword.contains(" ")) {
            String[] parts = keyword.split("\\s+");
            searchCity = parts[0];
            finalQuery = parts[1];
        } else {
            searchCity = mCityId;
            finalQuery = keyword;
        }

        mExecutor.execute(() -> {
            try {
                final JSONObject res = BusRepository.getInstance(MainActivity.this).search(finalQuery, searchCity, mCurrentLat, mCurrentLng);
                mMainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    renderSearchResults(keyword, res);
                });
            } catch (final Exception e) {
                mMainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    mProgressBar.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "搜索失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void renderSearchResults(String keyword, JSONObject res) {
        mProgressBar.setVisibility(View.GONE);
        mStopsContainer.removeAllViews();
        mTvLocStatus.setText("已完成搜索: \"" + keyword + "\"");

        // 顶部控制返回栏
        LinearLayout returnBar = new LinearLayout(this);
        returnBar.setOrientation(LinearLayout.HORIZONTAL);
        returnBar.setGravity(Gravity.CENTER_VERTICAL);
        returnBar.setPadding(0, 0, 0, dp(12));

        TextView tvCount = new TextView(this);
        tvCount.setText("🔍 搜索关键词: \"" + keyword + "\"");
        tvCount.setTextColor(Color.parseColor("#58a6ff"));
        tvCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvCount.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tvCount.setLayoutParams(cLp);
        returnBar.addView(tvCount);

        Button btnBackNear = new Button(this);
        btnBackNear.setText("返回周边 ↺");
        btnBackNear.setTextColor(Color.parseColor("#c9d1d9"));
        btnBackNear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        GradientDrawable backBg = new GradientDrawable();
        backBg.setColor(Color.parseColor("#21262d"));
        backBg.setCornerRadius(dp(6));
        backBg.setStroke(dp(1), Color.parseColor("#30363d"));
        btnBackNear.setBackground(backBg);
        btnBackNear.setPadding(dp(12), dp(4), dp(12), dp(4));
        btnBackNear.setOnClickListener(v -> {
            mIsSearchMode = false;
            if (mEtSearch != null) mEtSearch.setText("");
            loadNearbyStops(false);
        });
        returnBar.addView(btnBackNear);

        mStopsContainer.addView(returnBar);

        JSONObject result = res.optJSONObject("result");
        if (result == null) result = res;

        JSONArray lines = result.optJSONArray("lines");
        JSONArray stations = result.optJSONArray("stations");

        boolean hasResults = false;

        // 1. 渲染线路结果
        if (lines != null && lines.length() > 0) {
            hasResults = true;
            TextView tvLineHeader = new TextView(this);
            tvLineHeader.setText("🚍 本地线路列表 (" + lines.length() + " 个方向)");
            tvLineHeader.setTextColor(Color.parseColor("#8b949e"));
            tvLineHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvLineHeader.setPadding(0, dp(4), 0, dp(8));
            mStopsContainer.addView(tvLineHeader);

            for (int i = 0; i < lines.length(); i++) {
                JSONObject lineObj = lines.optJSONObject(i);
                if (lineObj == null) continue;
                mStopsContainer.addView(createSearchLineCard(lineObj));
            }
        }

        // 2. 渲染相关站点结果（如果有）
        if (stations != null && stations.length() > 0) {
            hasResults = true;
            TextView tvStationHeader = new TextView(this);
            tvStationHeader.setText("🚏 匹配到的相关站点 (" + stations.length() + " 个)");
            tvStationHeader.setTextColor(Color.parseColor("#8b949e"));
            tvStationHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvStationHeader.setPadding(0, dp(14), 0, dp(8));
            mStopsContainer.addView(tvStationHeader);

            for (int i = 0; i < stations.length(); i++) {
                JSONObject stObj = stations.optJSONObject(i);
                if (stObj == null) continue;
                mStopsContainer.addView(createSearchStationCard(stObj));
            }
        }

        if (!hasResults) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("未找到与 \"" + keyword + "\" 相关的本地公交线路\n可尝试输入数字线路名 (如 16、24)");
            tvEmpty.setTextColor(Color.parseColor("#8b949e"));
            tvEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tvEmpty.setGravity(Gravity.CENTER);
            tvEmpty.setPadding(0, dp(40), 0, dp(40));
            mStopsContainer.addView(tvEmpty);
        }
    }

    private View createSearchLineCard(final JSONObject lineObj) {
        final String lineId = lineObj.optString("lineId", "");
        final String name = lineObj.optString("name", "");
        final String startSn = lineObj.optString("startSn", "");
        final String endSn = lineObj.optString("endSn", "");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setClickable(true);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardLp);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#161b22"));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.parseColor("#30363d"));
        card.setBackground(bg);

        card.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, LineDetailActivity.class);
            intent.putExtra("lineId", lineId);
            intent.putExtra("lineName", name);
            intent.putExtra("endSn", endSn);
            intent.putExtra("cityId", mCityId);
            intent.putExtra("lat", mCurrentLat);
            intent.putExtra("lng", mCurrentLng);
            startActivity(intent);
        });

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText(name.endsWith("路") ? name : name + "路");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#1f6feb"));
        badgeBg.setCornerRadius(dp(6));
        badge.setBackground(badgeBg);
        topRow.addView(badge);

        TextView tvRoute = new TextView(this);
        tvRoute.setText("  " + startSn + "  ➔  " + endSn);
        tvRoute.setTextColor(Color.parseColor("#f0f6fc"));
        tvRoute.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvRoute.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tvRoute.setLayoutParams(rLp);
        topRow.addView(tvRoute);

        TextView tvArrow = new TextView(this);
        tvArrow.setText("实时车辆 ›");
        tvArrow.setTextColor(Color.parseColor("#58a6ff"));
        tvArrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        topRow.addView(tvArrow);

        final TextView tvFav = new TextView(this);
        final boolean isFav = FavoritesManager.isFavorite(this, name, endSn);
        tvFav.setText(isFav ? "★" : "☆");
        tvFav.setTextColor(isFav ? Color.parseColor("#f1e05a") : Color.parseColor("#8b949e"));
        tvFav.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvFav.setPadding(dp(8), dp(2), dp(4), dp(2));
        tvFav.setClickable(true);
        tvFav.setOnClickListener(v -> {
            boolean nowFav = FavoritesManager.toggleFavorite(MainActivity.this, name, lineId, endSn, "");
            tvFav.setText(nowFav ? "★" : "☆");
            tvFav.setTextColor(nowFav ? Color.parseColor("#f1e05a") : Color.parseColor("#8b949e"));
            Toast.makeText(MainActivity.this, nowFav ? "已置顶关注该线路" : "已取消关注", Toast.LENGTH_SHORT).show();
        });
        topRow.addView(tvFav);

        card.addView(topRow);

        TextView tvSub = new TextView(this);
        tvSub.setText("终点站: " + endSn + " (点击查看全线实时车辆位置)");
        tvSub.setTextColor(Color.parseColor("#8b949e"));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvSub.setPadding(0, dp(6), 0, 0);
        card.addView(tvSub);

        return card;
    }

    private View createSearchStationCard(final JSONObject stObj) {
        final String sn = stObj.optString("sn", "");
        final String sId = stObj.optString("sId", "");

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(cardLp);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#11161d"));
        bg.setCornerRadius(dp(8));
        card.setBackground(bg);

        TextView tvName = new TextView(this);
        tvName.setText("🚏 " + sn);
        tvName.setTextColor(Color.parseColor("#c9d1d9"));
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tvName.setLayoutParams(nameLp);
        card.addView(tvName);

        TextView tvSid = new TextView(this);
        tvSid.setText("站点ID: " + sId);
        tvSid.setTextColor(Color.parseColor("#58a6ff"));
        tvSid.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        card.addView(tvSid);

        return card;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
