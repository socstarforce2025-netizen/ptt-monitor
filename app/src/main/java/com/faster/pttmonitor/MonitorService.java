package com.faster.pttmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MonitorService extends Service {

    private static final String TAG = "PTTMonitor";
    private static final String CHANNEL_ID = "ptt_monitor_channel";
    private static final String CHANNEL_ALERT = "ptt_alert_channel";
    private static final int NOTIF_ID = 1;

    public static boolean isRunning = false;

    private ScheduledExecutorService scheduler;
    private OkHttpClient httpClient;
    private SharedPreferences prefs;

    // 已通知過的文章 ID，避免重複通知
    private Map<String, Set<String>> notifiedIds = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
        prefs = getSharedPreferences("ptt_monitor", MODE_PRIVATE);
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isRunning = true;
        startForeground(NOTIF_ID, buildForegroundNotification());

        int interval = prefs.getInt("interval", 5);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::doCheck, 0, interval, TimeUnit.MINUTES);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (scheduler != null) scheduler.shutdownNow();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── 主要檢查邏輯 ──────────────────────────────────────────────

    private void doCheck() {
        String boardsStr   = prefs.getString("boards", "e-coupon");
        String keywordsStr = prefs.getString("keywords", "衛生紙");
        String lineToken   = prefs.getString("line_token", "");

        List<String> boards   = Arrays.asList(boardsStr.split(","));
        List<String> keywords = Arrays.asList(keywordsStr.split(","));

        for (String board : boards) {
            board = board.trim();
            if (board.isEmpty()) continue;
            try {
                String json = fetchBoard(board);
                checkKeywords(board, json, keywords, lineToken);
            } catch (Exception e) {
                Log.e(TAG, "檢查看板失敗: " + board, e);
            }
        }
    }

    private String fetchBoard(String board) throws IOException {
        String url = "https://www.ptt.cc/bbs/" + board + "/index.json";
        Request req = new Request.Builder()
            .url(url)
            .addHeader("Cookie", "over18=1")
            .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            return resp.body().string();
        }
    }

    private void checkKeywords(String board, String json, List<String> keywords, String lineToken) {
        if (!notifiedIds.containsKey(board)) {
            notifiedIds.put(board, new HashSet<>());
        }
        Set<String> seen = notifiedIds.get(board);

        // 簡單解析 JSON（避免引入 JSON 函式庫）
        // PTT JSON 格式：[{"id":"M.xxx","title":"...","author":"..."}]
        String[] entries = json.split("\\{");
        for (String entry : entries) {
            if (!entry.contains("\"id\"")) continue;

            String id    = extractField(entry, "id");
            String title = extractField(entry, "title");
            String href  = extractField(entry, "href");

            if (id == null || title == null || seen.contains(id)) continue;

            for (String kw : keywords) {
                kw = kw.trim();
                if (!kw.isEmpty() && title.contains(kw)) {
                    seen.add(id);
                    String msg = "[" + board + "] 命中關鍵字「" + kw + "」\n" + title;
                    String link = "https://www.ptt.cc" + (href != null ? href : "");
                    sendAlert(msg, link, lineToken);
                    break;
                }
            }
        }
    }

    private String extractField(String entry, String field) {
        String search = "\"" + field + "\":\"";
        int start = entry.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = entry.indexOf("\"", start);
        if (end < 0) return null;
        return entry.substring(start, end);
    }

    // ── 通知 ─────────────────────────────────────────────────────

    private void sendAlert(String message, String link, String lineToken) {
        Log.i(TAG, "命中: " + message);

        // Android 通知
        showAlertNotification(message);

        // Line Notify
        if (lineToken != null && !lineToken.isEmpty()) {
            sendLineNotify(lineToken, message + "\n" + link);
        }
    }

    private void showAlertNotification(String message) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        int id = (int) System.currentTimeMillis();

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PTT 關鍵字命中！")
            .setContentText(message)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build();

        nm.notify(id, notif);
    }

    private void sendLineNotify(String token, String message) {
        try {
            RequestBody body = new FormBody.Builder()
                .add("message", "\n" + message)
                .build();
            Request req = new Request.Builder()
                .url("https://notify-api.line.me/api/notify")
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                Log.i(TAG, "Line Notify: " + resp.code());
            }
        } catch (Exception e) {
            Log.e(TAG, "Line Notify 失敗", e);
        }
    }

    // ── Notification Channels ─────────────────────────────────────

    private void createNotificationChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel fg = new NotificationChannel(
            CHANNEL_ID, "PTT 監控服務", NotificationManager.IMPORTANCE_LOW);
        fg.setDescription("背景監控服務通知");
        nm.createNotificationChannel(fg);

        NotificationChannel alert = new NotificationChannel(
            CHANNEL_ALERT, "PTT 關鍵字命中", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("關鍵字命中警報");
        nm.createNotificationChannel(alert);
    }

    private Notification buildForegroundNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("PTT 監控中")
            .setContentText("正在監控 PTT 關鍵字...")
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }
}
