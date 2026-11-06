package com.geek.cleanbus;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LineDetailActivity extends Activity {

    private static final int AUTO_REFRESH_INTERVAL_MS = 10000; // 10秒静默自动刷新
    private static final String PREFS_NAME = "cleanbus_prefs";

    private String mLineId;
    private String mLineName;
    private String mEndSn;
    private String mCityId;
    private double mLat;
    private double mLng;
    private String mTargetStopName; // 当前所选中的乘车站名

    // 换向缓存
    private String mOppositeLineId;
    private String mOppositeEndSn;
    private Button mBtnSwitchDir;
    private Button mBtnFav;

    private LinearLayout mTopBar;
    private FrameLayout mContentFrame;
    private TextView mTvLineTitle;
    private TextView mTvLineSub;
    private TextView mTvMetaInfo;
    private TextView mTvTargetArrivalBanner;
    private ScrollView mScrollView;
    private LinearLayout mStationsContainer;
    private ProgressBar mProgressBar;

    // 到站临近震动提醒状态
    private boolean mNotifyEnabled = false;
    private boolean mHasNotified = false;

    private JSONObject mCurrentData; // 缓存当前数据，供点击切换站点时即时重绘
    private boolean mHasScrolledToTarget = false;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences mPrefs;

    private final Runnable mAutoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !isDestroyed()) {
                loadData(true);
                mMainHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS);
            }
        }
    };

    private String getLineKey(String name) {
        if (name == null) return "";
        return name.replace("路", "").trim();
    }

    /**
     * 智能模糊匹配站名：去除括号、后缀、空格等干扰，兼容对向站点细微差异
     */
    private boolean isStopMatch(String stName, String targetName) {
        if (stName == null || targetName == null) return false;
        String s1 = stName.trim();
        String s2 = targetName.trim();
        if (s1.isEmpty() || s2.isEmpty()) return false;
        if (s1.equals(s2)) return true;

        String c1 = s1.replaceAll("[\\(（\\[].*?[\\)）\\]]", "").replace("公交站", "").trim();
        String c2 = s2.replaceAll("[\\(（\\[].*?[\\)）\\]]", "").replace("公交站", "").trim();
        if (!c1.isEmpty() && !c2.isEmpty()) {
            if (c1.equals(c2) || c1.contains(c2) || c2.contains(c1)) {
                return true;
            }
        }
        return s1.contains(s2) || s2.contains(s1);
    }

    private static int parseTimeToMinutes(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return -1;
        String[] parts = timeStr.trim().split(":");
        if (parts.length < 2) return -1;
        try {
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 推算首末班与运营状态
     */
    public static String formatScheduleStatus(String firstTime, String lastTime) {
        if (firstTime == null || lastTime == null || firstTime.isEmpty() || lastTime.isEmpty()) {
            return "等待发车 / 暂无在途车辆";
        }
        try {
            int firstMin = parseTimeToMinutes(firstTime);
            int lastMin = parseTimeToMinutes(lastTime);
            if (firstMin < 0 || lastMin < 0) {
                return "等待发车 / 暂无在途车辆";
            }

            Calendar cal = Calendar.getInstance();
            int nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

            if (firstMin <= lastMin) {
                // 当日普通班次
                if (nowMin < firstMin) {
                    return "🌙 未到运营时间 (首班 " + firstTime + " 发车)";
                }
                if (nowMin > lastMin) {
                    return "🌙 今日已收班 (末班 " + lastTime + ")";
                }
            } else {
                // 跨零点班次 (如 22:00 ~ 02:00)
                // 运营区间: [firstMin, 1440) U [0, lastMin]
                // 非运营区间: (lastMin, firstMin)
                if (nowMin > lastMin && nowMin < firstMin) {
                    return "🌙 未到运营时间 (首班 " + firstTime + " 发车)";
                }
            }
            return "⏳ 运营中 · 暂无在途车辆 (预计发班间隔 10~15 分钟)";
        } catch (Exception e) {
            return "等待发车 / 暂无在途车辆";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        mLineId = getIntent().getStringExtra("lineId");
        mLineName = getIntent().getStringExtra("lineName");
        mEndSn = getIntent().getStringExtra("endSn");
        mCityId = getIntent().getStringExtra("cityId");
        if (mCityId == null || mCityId.isEmpty()) mCityId = "043";
        mLat = getIntent().getDoubleExtra("lat", 29.654);
        mLng = getIntent().getDoubleExtra("lng", 91.118);
        mTargetStopName = getIntent().getStringExtra("targetStopName");

        // 核心功能：读取上一轮选择的方向偏好 (记住路线方向)
        String key = getLineKey(mLineName);
        if (!key.isEmpty()) {
            String savedLineId = mPrefs.getString("pref_lineid_" + key, null);
            String savedEndSn = mPrefs.getString("pref_endsn_" + key, null);
            if (savedLineId != null && savedEndSn != null && !savedLineId.isEmpty()) {
                mLineId = savedLineId;
                mEndSn = savedEndSn;
            }
        }

        setupEdgeToEdge();
        initViews();
        loadData(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMainHandler.removeCallbacks(mAutoRefreshRunnable);
        mMainHandler.postDelayed(mAutoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMainHandler.removeCallbacks(mAutoRefreshRunnable);
    }

    @Override
    protected void onDestroy() {
        mMainHandler.removeCallbacksAndMessages(null);
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void setupEdgeToEdge() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#0d1117")));
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        try {
            Method setNavBarContrast = Window.class.getMethod("setNavigationBarContrastEnforced", boolean.class);
            setNavBarContrast.invoke(window, false);
        } catch (Throwable ignored) {}

        try {
            Method m = Window.class.getMethod("setDecorFitsSystemWindows", boolean.class);
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

        TextView btnBack = new TextView(this);
        btnBack.setText("‹ 返回");
        btnBack.setTextColor(Color.parseColor("#58a6ff"));
        btnBack.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        btnBack.setTypeface(Typeface.DEFAULT_BOLD);
        btnBack.setPadding(0, dp(4), dp(12), dp(4));
        btnBack.setOnClickListener(v -> finish());
        mTopBar.addView(btnBack);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        titleBox.setLayoutParams(titleLp);

        mTvLineTitle = new TextView(this);
        mTvLineTitle.setText(mLineName != null ? (mLineName.endsWith("路") ? mLineName : mLineName + "路") : "线路详情");
        mTvLineTitle.setTextColor(Color.parseColor("#f0f6fc"));
        mTvLineTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mTvLineTitle.setTypeface(Typeface.DEFAULT_BOLD);
        titleBox.addView(mTvLineTitle);

        mTvLineSub = new TextView(this);
        mTvLineSub.setText(mEndSn != null ? "开往: " + mEndSn : "");
        mTvLineSub.setTextColor(Color.parseColor("#8b949e"));
        mTvLineSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleBox.addView(mTvLineSub);

        mTopBar.addView(titleBox);

        // 顶栏收藏关注按钮
        mBtnFav = new Button(this);
        updateFavButtonState();
        mBtnFav.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mBtnFav.setPadding(dp(10), dp(4), dp(10), dp(4));
        mBtnFav.setOnClickListener(v -> {
            boolean nowFav = FavoritesManager.toggleFavorite(LineDetailActivity.this, mLineName, mLineId, mEndSn, mTargetStopName);
            updateFavButtonState();
            Toast.makeText(LineDetailActivity.this, nowFav ? "⭐ 已收藏置顶本线路" : "已取消收藏", Toast.LENGTH_SHORT).show();
        });
        mTopBar.addView(mBtnFav);

        // 刷新按钮
        Button btnRefresh = new Button(this);
        btnRefresh.setText("刷新");
        btnRefresh.setTextColor(Color.WHITE);
        btnRefresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#238636"));
        btnBg.setCornerRadius(dp(6));
        btnRefresh.setBackground(btnBg);
        btnRefresh.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        refLp.setMargins(dp(6), 0, 0, 0);
        btnRefresh.setLayoutParams(refLp);
        btnRefresh.setOnClickListener(v -> loadData(false));
        mTopBar.addView(btnRefresh);

        root.addView(mTopBar);

        // 2. Direction Switch Bar (换向按钮栏)
        LinearLayout switchBar = new LinearLayout(this);
        switchBar.setOrientation(LinearLayout.HORIZONTAL);
        switchBar.setGravity(Gravity.CENTER_VERTICAL);
        switchBar.setPadding(dp(16), dp(8), dp(16), dp(8));
        switchBar.setBackgroundColor(Color.parseColor("#131920"));

        mBtnSwitchDir = new Button(this);
        mBtnSwitchDir.setText("⇄ 换向");
        mBtnSwitchDir.setTextColor(Color.parseColor("#58a6ff"));
        mBtnSwitchDir.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mBtnSwitchDir.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable switchBg = new GradientDrawable();
        switchBg.setColor(Color.parseColor("#21262d"));
        switchBg.setCornerRadius(dp(8));
        switchBg.setStroke(dp(1), Color.parseColor("#388bfd"));
        mBtnSwitchDir.setBackground(switchBg);
        mBtnSwitchDir.setPadding(dp(16), dp(6), dp(16), dp(6));
        mBtnSwitchDir.setVisibility(View.GONE);

        mBtnSwitchDir.setOnClickListener(v -> {
            if (mOppositeLineId != null && !mOppositeLineId.isEmpty()) {
                mLineId = mOppositeLineId;
                if (mOppositeEndSn != null && !mOppositeEndSn.isEmpty()) {
                    mEndSn = mOppositeEndSn;
                }
                String key = getLineKey(mLineName);
                if (!key.isEmpty()) {
                    mPrefs.edit()
                            .putString("pref_lineid_" + key, mLineId)
                            .putString("pref_endsn_" + key, mEndSn)
                            .apply();
                }
                updateFavButtonState();
                mHasScrolledToTarget = false;
                loadData(false);
            } else {
                Toast.makeText(LineDetailActivity.this, "暂无反方向线路数据", Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mBtnSwitchDir.setLayoutParams(switchLp);
        switchBar.addView(mBtnSwitchDir);
        root.addView(switchBar);

        // 3. Info Meta Banner & 选中站点实时预测 Banner
        LinearLayout bannerBox = new LinearLayout(this);
        bannerBox.setOrientation(LinearLayout.VERTICAL);
        bannerBox.setBackgroundColor(Color.parseColor("#1c2128"));
        bannerBox.setPadding(dp(16), dp(8), dp(16), dp(8));

        mTvMetaInfo = new TextView(this);
        mTvMetaInfo.setTextColor(Color.parseColor("#79c0ff"));
        mTvMetaInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mTvMetaInfo.setText("正在加载线路与实时车辆位置...");
        bannerBox.addView(mTvMetaInfo);

        mTvTargetArrivalBanner = new TextView(this);
        mTvTargetArrivalBanner.setTextColor(Color.parseColor("#3fb950"));
        mTvTargetArrivalBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mTvTargetArrivalBanner.setTypeface(Typeface.DEFAULT_BOLD);
        mTvTargetArrivalBanner.setPadding(0, dp(4), 0, 0);
        mTvTargetArrivalBanner.setVisibility(View.GONE);
        bannerBox.addView(mTvTargetArrivalBanner);

        root.addView(bannerBox);

        // 4. Scroll Content
        mContentFrame = new FrameLayout(this);
        LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        mContentFrame.setLayoutParams(frameLp);

        mScrollView = new ScrollView(this);
        mScrollView.setFillViewport(true);
        mScrollView.setClipToPadding(false);

        mStationsContainer = new LinearLayout(this);
        mStationsContainer.setOrientation(LinearLayout.VERTICAL);
        mStationsContainer.setPadding(dp(16), dp(12), dp(16), dp(32));
        mScrollView.addView(mStationsContainer);

        mContentFrame.addView(mScrollView);

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
            mStationsContainer.setPadding(dp(16), dp(12), dp(16), dp(32) + bottom);
            return insets.consumeSystemWindowInsets();
        });

        setContentView(root);
    }

    private void updateFavButtonState() {
        if (mBtnFav == null) return;
        boolean isFav = FavoritesManager.isFavorite(this, mLineName, mEndSn);
        mBtnFav.setText(isFav ? "★ 已关注" : "☆ 关注");
        GradientDrawable favBg = new GradientDrawable();
        if (isFav) {
            favBg.setColor(Color.parseColor("#382800"));
            favBg.setStroke(dp(1), Color.parseColor("#f1e05a"));
            mBtnFav.setTextColor(Color.parseColor("#f1e05a"));
        } else {
            favBg.setColor(Color.parseColor("#21262d"));
            favBg.setStroke(dp(1), Color.parseColor("#30363d"));
            mBtnFav.setTextColor(Color.parseColor("#c9d1d9"));
        }
        favBg.setCornerRadius(dp(6));
        mBtnFav.setBackground(favBg);
    }

    private void loadData(final boolean isSilent) {
        if (!isSilent) {
            mProgressBar.setVisibility(View.VISIBLE);
            mTvMetaInfo.setText("正在拉取全线车辆位置...");
        }

        mExecutor.execute(() -> {
            try {
                final JSONObject data = BusRepository.getInstance(LineDetailActivity.this).getLineDetail(mLineId, mCityId, mLat, mLng);
                mCurrentData = data;
                mMainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    renderData(data, isSilent);
                });
            } catch (final Exception e) {
                mMainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    mProgressBar.setVisibility(View.GONE);
                    if (!isSilent) {
                        mTvMetaInfo.setText("加载失败: " + e.getMessage());
                        Toast.makeText(LineDetailActivity.this, "请求失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
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

    static class TargetArrivalResult {
        String displayText;
        boolean hasDeviatedFirst;
    }

    private TargetArrivalResult calculateTargetArrival(JSONArray buses, int targetOrder, String firstTime, String lastTime) {
        TargetArrivalResult res = new TargetArrivalResult();
        if (buses == null || buses.length() == 0) {
            res.displayText = formatScheduleStatus(firstTime, lastTime);
            res.hasDeviatedFirst = false;
            return res;
        }

        List<JSONObject> upstreamBuses = new ArrayList<>();
        for (int i = 0; i < buses.length(); i++) {
            JSONObject b = buses.optJSONObject(i);
            if (b == null) continue;
            int busOrder = b.optInt("order", 0);
            if (busOrder <= targetOrder) {
                upstreamBuses.add(b);
            }
        }

        if (upstreamBuses.isEmpty()) {
            res.displayText = formatScheduleStatus(firstTime, lastTime);
            res.hasDeviatedFirst = false;
            return res;
        }

        upstreamBuses.sort((b1, b2) -> {
            int d1 = targetOrder - b1.optInt("order", 0);
            int d2 = targetOrder - b2.optInt("order", 0);
            return Integer.compare(d1, d2);
        });

        JSONObject firstBus = upstreamBuses.get(0);
        int firstOrder = firstBus.optInt("order", 0);
        int firstStopsAway = targetOrder - firstOrder;
        boolean firstDeviated = isBusDeviated(firstBus);

        if (!firstDeviated) {
            res.displayText = "📍 最近车辆: " + buildBusTimeDistanceText(firstBus, firstStopsAway);
            res.hasDeviatedFirst = false;
            return res;
        }

        res.hasDeviatedFirst = true;
        String firstDevDesc = "⚠️ 最近车辆 (还有 " + (firstStopsAway == 0 ? "即将到站" : firstStopsAway + " 站") + ") 可能已偏离路线";

        JSONObject nextNormalBus = null;
        int nextStopsAway = 0;
        for (int i = 1; i < upstreamBuses.size(); i++) {
            JSONObject b = upstreamBuses.get(i);
            if (!isBusDeviated(b)) {
                nextNormalBus = b;
                nextStopsAway = targetOrder - b.optInt("order", 0);
                break;
            }
        }

        if (nextNormalBus != null) {
            res.displayText = firstDevDesc + "\n➔ 下一辆正常班次: 还有 " + nextStopsAway + " 站 " + buildBusTimeDistanceText(nextNormalBus, -1);
        } else {
            res.displayText = firstDevDesc + "\n➔ 暂无后续正常在途班次，等待发车";
        }
        return res;
    }

    private String buildBusTimeDistanceText(JSONObject bus, int stopsAway) {
        int distance = bus.optInt("distanceToDest", 0);
        int travelSec = bus.optInt("travelTime", 0);
        int speed = bus.optInt("speed", 0);

        StringBuilder sb = new StringBuilder();
        if (stopsAway >= 0) {
            if (stopsAway == 0 || (distance > 0 && distance <= 80) || (travelSec > 0 && travelSec <= 30)) {
                sb.append("即将到站");
            } else {
                sb.append("还有 ").append(stopsAway).append(" 站");
            }
        }

        if (travelSec > 0 || distance > 0) {
            sb.append(" (");
            if (travelSec > 0) {
                sb.append("约 ").append(String.format("%.1f", travelSec / 60.0)).append(" 分钟");
                if (distance > 0) sb.append("，");
            }
            if (distance > 0) {
                sb.append("距 ").append(distance).append(" 米");
            }
            if (speed > 0) {
                sb.append("，车速 ").append(speed).append("km/h");
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private void renderData(JSONObject data, boolean isSilent) {
        if (data == null) return;
        mProgressBar.setVisibility(View.GONE);
        mStationsContainer.removeAllViews();

        JSONObject line = data.optJSONObject("line");
        if (line == null) {
            mTvMetaInfo.setText("未获取到线路详情");
            return;
        }

        String name = line.optString("name", mLineName);
        String endSn = line.optString("endSn", mEndSn);
        String firstTime = line.optString("firstTime", "");
        String lastTime = line.optString("lastTime", "");
        String price = line.optString("price", "");

        mTvLineTitle.setText(name.endsWith("路") ? name : name + "路");
        mTvLineSub.setText("终点: " + endSn);
        updateFavButtonState();

        JSONArray otherlines = data.optJSONArray("otherlines");
        if (otherlines != null && otherlines.length() > 0) {
            JSONObject ol = otherlines.optJSONObject(0);
            if (ol != null) {
                mOppositeLineId = ol.optString("lineId", null);
                mOppositeEndSn = ol.optString("endSn", "");
                if (mOppositeLineId != null && !mOppositeLineId.isEmpty()) {
                    mBtnSwitchDir.setVisibility(View.VISIBLE);
                    mBtnSwitchDir.setText("⇄ 换向 (切换至开往: " + mOppositeEndSn + ")");
                }
            }
        } else {
            mBtnSwitchDir.setVisibility(View.GONE);
        }

        JSONArray stations = data.optJSONArray("stations");
        JSONArray buses = data.optJSONArray("buses");
        int busCount = buses != null ? buses.length() : 0;

        StringBuilder meta = new StringBuilder();
        if (!firstTime.isEmpty() && !lastTime.isEmpty()) meta.append("首末班: ").append(firstTime).append("~").append(lastTime).append("  |  ");
        if (!price.isEmpty()) meta.append("票价: ").append(price).append("  |  ");
        meta.append("全线在途: ").append(busCount).append(" 辆");
        mTvMetaInfo.setText(meta.toString());

        if (stations == null || stations.length() == 0) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("该线路暂无站点列表数据");
            tvEmpty.setTextColor(Color.parseColor("#8b949e"));
            mStationsContainer.addView(tvEmpty);
            mTvTargetArrivalBanner.setVisibility(View.GONE);
            return;
        }

        int targetOrder = -1;
        String matchedTargetSn = null;

        if (mTargetStopName != null && !mTargetStopName.isEmpty()) {
            for (int i = 0; i < stations.length(); i++) {
                JSONObject st = stations.optJSONObject(i);
                if (st == null) continue;
                String sn = st.optString("sn", "");
                if (isStopMatch(sn, mTargetStopName)) {
                    targetOrder = st.optInt("order", i + 1);
                    matchedTargetSn = sn;
                    mTargetStopName = sn;
                    break;
                }
            }
        }

        if (targetOrder == -1 && stations.length() > 0) {
            JSONObject firstSt = stations.optJSONObject(0);
            if (firstSt != null) {
                targetOrder = firstSt.optInt("order", 1);
                matchedTargetSn = firstSt.optString("sn", "");
                mTargetStopName = matchedTargetSn;
            }
        }

        TargetArrivalResult arrivalResult = calculateTargetArrival(buses, targetOrder, firstTime, lastTime);
        String targetArrivalText = arrivalResult.displayText;
        boolean hasDeviated = arrivalResult.hasDeviatedFirst;

        // 到站临近震动提醒触发研判
        checkAndTriggerArrivalVibration(buses, targetOrder, matchedTargetSn);

        mTvTargetArrivalBanner.setVisibility(View.VISIBLE);
        if (hasDeviated) {
            mTvTargetArrivalBanner.setTextColor(Color.parseColor("#f0883e"));
        } else {
            mTvTargetArrivalBanner.setTextColor(Color.parseColor("#3fb950"));
        }
        mTvTargetArrivalBanner.setText("🎯 聚焦站点: 【" + matchedTargetSn + "】\n" + targetArrivalText);

        Map<Integer, List<JSONObject>> busMap = new HashMap<>();
        if (buses != null) {
            for (int i = 0; i < buses.length(); i++) {
                JSONObject b = buses.optJSONObject(i);
                if (b != null) {
                    int order = b.optInt("order", 0);
                    if (!busMap.containsKey(order)) {
                        busMap.put(order, new ArrayList<>());
                    }
                    busMap.get(order).add(b);
                }
            }
        }

        View targetStationView = null;
        int stationCount = stations.length();

        for (int i = 0; i < stationCount; i++) {
            JSONObject st = stations.optJSONObject(i);
            if (st == null) continue;

            final int order = st.optInt("order", i + 1);
            final String sn = st.optString("sn", "未知站点");
            final boolean isTarget = (order == targetOrder);
            final boolean isFirst = (i == 0);
            final boolean isLast = (i == stationCount - 1);

            // 在对应站点上方插入正在开往此站的实时车辆卡片（带导轨衔接）
            List<JSONObject> currentBuses = busMap.get(order);
            if (currentBuses != null && !currentBuses.isEmpty()) {
                for (JSONObject b : currentBuses) {
                    mStationsContainer.addView(createBusMarkerCard(b, sn));
                }
            }

            // 站点卡片（带地铁贯穿导轨时间轴）
            String arrivalText = isTarget ? targetArrivalText : null;
            View stView = createStationRow(order, sn, isTarget, arrivalText, hasDeviated, isFirst, isLast);
            mStationsContainer.addView(stView);

            if (isTarget && targetStationView == null) {
                targetStationView = stView;
            }
        }

        if (targetStationView != null && !mHasScrolledToTarget) {
            final View finalTargetView = targetStationView;
            finalTargetView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    finalTargetView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    if (mHasScrolledToTarget) return;
                    scrollToStation(finalTargetView);
                    mHasScrolledToTarget = true;
                }
            });
        }
    }

    private void checkAndTriggerArrivalVibration(JSONArray buses, int targetOrder, String targetSn) {
        if (!mNotifyEnabled || mHasNotified || buses == null) return;
        for (int i = 0; i < buses.length(); i++) {
            JSONObject b = buses.optJSONObject(i);
            if (b == null || isBusDeviated(b)) continue;
            int busOrder = b.optInt("order", 0);
            if (busOrder <= targetOrder) {
                int stopsAway = targetOrder - busOrder;
                int dist = b.optInt("distanceToDest", 0);
                if (stopsAway <= 1 || (dist > 0 && dist <= 500)) {
                    try {
                        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                        if (vibrator != null && vibrator.hasVibrator()) {
                            vibrator.vibrate(new long[]{0, 400, 200, 400}, -1);
                        }
                    } catch (Exception ignored) {}
                    Toast.makeText(LineDetailActivity.this, "🔔 【" + targetSn + "】关注的公交即将进站，请准备上车！", Toast.LENGTH_LONG).show();
                    mHasNotified = true;
                    break;
                }
            }
        }
    }

    private void scrollToStation(final View targetView) {
        if (targetView == null || mScrollView == null) return;
        mScrollView.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            int targetTop = targetView.getTop();
            int scrollHeight = mScrollView.getHeight();
            int targetHeight = targetView.getHeight();
            int scrollToY = Math.max(0, targetTop - (scrollHeight / 2) + (targetHeight / 2));
            mScrollView.smoothScrollTo(0, scrollToY);
        });
    }

    /**
     * 实时车辆卡片（左侧带地铁贯通导轨）
     */
    private View createBusMarkerCard(JSONObject bus, String approachingStation) {
        boolean isDeviated = isBusDeviated(bus);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));

        // 1. 左侧贯通导轨轴
        FrameLayout trackCol = new FrameLayout(this);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.MATCH_PARENT);
        trackCol.setLayoutParams(trackLp);

        View rail = new View(this);
        rail.setBackgroundColor(Color.parseColor("#388bfd")); // 车辆所在区间亮蓝轨道
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(dp(2), FrameLayout.LayoutParams.MATCH_PARENT);
        railLp.gravity = Gravity.CENTER_HORIZONTAL;
        rail.setLayoutParams(railLp);
        trackCol.addView(rail);

        TextView icon = new TextView(this);
        icon.setText(isDeviated ? "⚠️" : "🚍");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        iconLp.gravity = Gravity.CENTER;
        icon.setLayoutParams(iconLp);
        trackCol.addView(icon);

        row.addView(trackCol);

        // 2. 右侧车辆卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        cardLp.setMargins(dp(6), 0, 0, 0);
        card.setLayoutParams(cardLp);

        GradientDrawable bg = new GradientDrawable();
        if (isDeviated) {
            bg.setColor(Color.parseColor("#2a1d0d"));
            bg.setCornerRadius(dp(8));
            bg.setStroke(dp(1), Color.parseColor("#d29922"));
        } else {
            bg.setColor(Color.parseColor("#1f6feb"));
            bg.setCornerRadius(dp(8));
        }
        card.setBackground(bg);

        int travelSec = bus.optInt("travelTime", 0);
        int distance = bus.optInt("distanceToDest", 0);
        int speed = bus.optInt("speed", 0);

        TextView tvBusStatus = new TextView(this);
        if (isDeviated) {
            tvBusStatus.setText("车辆可能已偏离路线 ⚠️ → 开往【" + approachingStation + "】");
            tvBusStatus.setTextColor(Color.parseColor("#f0883e"));
        } else if (distance > 0 && distance <= 80 || travelSec <= 30) {
            tvBusStatus.setText("车辆已即将到站 → 【" + approachingStation + "】");
            tvBusStatus.setTextColor(Color.WHITE);
        } else {
            tvBusStatus.setText("实时车辆正在驶来 → 开往【" + approachingStation + "】");
            tvBusStatus.setTextColor(Color.WHITE);
        }
        tvBusStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvBusStatus.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(tvBusStatus);

        TextView tvSub = new TextView(this);
        StringBuilder subText = new StringBuilder();
        if (isDeviated) {
            subText.append("偏离警示: 车辆脱离预定轨迹，到站时间仅供参考  ");
        }
        if (travelSec > 0) subText.append("预计 ").append(String.format("%.1f", travelSec / 60.0)).append(" 分钟  ");
        if (distance > 0) subText.append("距该站 ").append(distance).append(" 米  ");
        if (speed > 0) subText.append("车速 ").append(speed).append(" km/h");
        tvSub.setText(subText.toString().trim());
        tvSub.setTextColor(isDeviated ? Color.parseColor("#ffc680") : Color.parseColor("#ddf4ff"));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        card.addView(tvSub);

        row.addView(card);
        return row;
    }

    /**
     * 站点卡片（左侧带地铁贯穿导轨时间轴 + 右侧内嵌震动提醒）
     */
    private View createStationRow(final int order, final String stationName, final boolean isTarget,
                                  final String targetArrivalDesc, final boolean hasDeviatedFirst,
                                  final boolean isFirst, final boolean isLast) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, 0);
        row.setClickable(true);

        // 主行容器
        LinearLayout mainRow = new LinearLayout(this);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setGravity(Gravity.CENTER_VERTICAL);
        mainRow.setPadding(dp(4), dp(4), dp(8), dp(4));

        GradientDrawable rowBg = new GradientDrawable();
        if (isTarget) {
            rowBg.setColor(Color.parseColor("#161f2c"));
            rowBg.setCornerRadius(dp(8));
            rowBg.setStroke(dp(1), Color.parseColor("#388bfd"));
        } else {
            rowBg.setColor(Color.TRANSPARENT);
            rowBg.setCornerRadius(dp(8));
        }
        mainRow.setBackground(rowBg);

        mainRow.setOnClickListener(v -> {
            mTargetStopName = stationName;
            mHasScrolledToTarget = false;
            mHasNotified = false; // 切换站点时重置提醒
            if (mCurrentData != null) {
                renderData(mCurrentData, true);
            }
        });

        // 1. 左侧地铁式垂直导轨轴 (上半线 + 圆点 + 下半线)
        LinearLayout axisCol = new LinearLayout(this);
        axisCol.setOrientation(LinearLayout.VERTICAL);
        axisCol.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams axisLp = new LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT);
        axisCol.setLayoutParams(axisLp);

        View topRail = new View(this);
        topRail.setBackgroundColor(isFirst ? Color.TRANSPARENT : Color.parseColor("#30363d"));
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(dp(2), dp(10));
        topRail.setLayoutParams(trLp);
        axisCol.addView(topRail);

        TextView dot = new TextView(this);
        if (isTarget) {
            dot.setText("●");
            dot.setTextColor(hasDeviatedFirst ? Color.parseColor("#f0883e") : Color.parseColor("#3fb950"));
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        } else {
            dot.setText("○");
            dot.setTextColor(Color.parseColor("#8b949e"));
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        }
        axisCol.addView(dot);

        View bottomRail = new View(this);
        bottomRail.setBackgroundColor(isLast ? Color.TRANSPARENT : Color.parseColor("#30363d"));
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(dp(2), dp(10));
        bottomRail.setLayoutParams(brLp);
        axisCol.addView(bottomRail);

        mainRow.addView(axisCol);

        // 2. 站名
        TextView tvName = new TextView(this);
        tvName.setText(order + ". " + stationName);
        if (isTarget) {
            tvName.setTextColor(Color.parseColor("#58a6ff"));
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            tvName.setTextColor(Color.parseColor("#c9d1d9"));
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        }
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tvName.setLayoutParams(nameLp);
        mainRow.addView(tvName);

        // 3. 右侧状态/切换提示
        TextView tvHint = new TextView(this);
        if (isTarget) {
            tvHint.setText("📍 当前所选");
            tvHint.setTextColor(hasDeviatedFirst ? Color.parseColor("#f0883e") : Color.parseColor("#3fb950"));
            tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tvHint.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            tvHint.setText("切换 ➔");
            tvHint.setTextColor(Color.parseColor("#484f58"));
            tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        }
        mainRow.addView(tvHint);
        row.addView(mainRow);

        // 4. 当前所选站点内嵌实时车辆到站预报卡片 + 震动提醒开关
        if (isTarget && targetArrivalDesc != null) {
            LinearLayout arrivalCard = new LinearLayout(this);
            arrivalCard.setOrientation(LinearLayout.VERTICAL);
            arrivalCard.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            aLp.setMargins(dp(26), dp(4), 0, dp(4));
            arrivalCard.setLayoutParams(aLp);

            GradientDrawable aBg = new GradientDrawable();
            if (hasDeviatedFirst) {
                aBg.setColor(Color.parseColor("#2a1d0d"));
                aBg.setCornerRadius(dp(6));
                aBg.setStroke(dp(1), Color.parseColor("#d29922"));
            } else {
                aBg.setColor(Color.parseColor("#12231b"));
                aBg.setCornerRadius(dp(6));
                aBg.setStroke(dp(1), Color.parseColor("#238636"));
            }
            arrivalCard.setBackground(aBg);

            TextView tvArrival = new TextView(this);
            tvArrival.setText(targetArrivalDesc);
            tvArrival.setTextColor(hasDeviatedFirst ? Color.parseColor("#ffb648") : Color.parseColor("#7ee787"));
            tvArrival.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvArrival.setTypeface(Typeface.DEFAULT_BOLD);
            arrivalCard.addView(tvArrival);

            // 到站临近震动提醒开关栏
            LinearLayout notifyRow = new LinearLayout(this);
            notifyRow.setOrientation(LinearLayout.HORIZONTAL);
            notifyRow.setGravity(Gravity.CENTER_VERTICAL);
            notifyRow.setPadding(0, dp(6), 0, 0);

            TextView tvNotifyInfo = new TextView(this);
            tvNotifyInfo.setText(mNotifyEnabled ? "🔔 1站内震动提醒已激活" : "🔕 到站前震动提醒");
            tvNotifyInfo.setTextColor(mNotifyEnabled ? Color.parseColor("#58a6ff") : Color.parseColor("#8b949e"));
            tvNotifyInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            tvNotifyInfo.setLayoutParams(nLp);
            notifyRow.addView(tvNotifyInfo);

            Button btnToggleNotify = new Button(this);
            btnToggleNotify.setText(mNotifyEnabled ? "关闭" : "开启");
            btnToggleNotify.setTextColor(Color.WHITE);
            btnToggleNotify.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            GradientDrawable notifBg = new GradientDrawable();
            notifBg.setColor(mNotifyEnabled ? Color.parseColor("#da3633") : Color.parseColor("#238636"));
            notifBg.setCornerRadius(dp(4));
            btnToggleNotify.setBackground(notifBg);
            btnToggleNotify.setPadding(dp(8), dp(2), dp(8), dp(2));
            btnToggleNotify.setOnClickListener(v -> {
                mNotifyEnabled = !mNotifyEnabled;
                mHasNotified = false;
                if (mNotifyEnabled) {
                    Toast.makeText(LineDetailActivity.this, "已开启【" + stationName + "】1站内到站震动提醒", Toast.LENGTH_SHORT).show();
                }
                if (mCurrentData != null) {
                    renderData(mCurrentData, true);
                }
            });
            notifyRow.addView(btnToggleNotify);

            arrivalCard.addView(notifyRow);
            row.addView(arrivalCard);
        }

        return row;
    }

    private int dp(float dpValue) {
        float scale = getResources().getDisplayMetrics().density;
        return (int) (dpValue * scale + 0.5f);
    }
}
