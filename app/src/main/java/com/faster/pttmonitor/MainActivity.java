package com.faster.pttmonitor;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private EditText etBoards, etKeywords, etLineToken, etInterval;
    private Button btnStart, btnStop, btnSave;
    private TextView tvStatus;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("ptt_monitor", MODE_PRIVATE);

        etBoards    = findViewById(R.id.etBoards);
        etKeywords  = findViewById(R.id.etKeywords);
        etLineToken = findViewById(R.id.etLineToken);
        etInterval  = findViewById(R.id.etInterval);
        btnStart    = findViewById(R.id.btnStart);
        btnStop     = findViewById(R.id.btnStop);
        btnSave     = findViewById(R.id.btnSave);
        tvStatus    = findViewById(R.id.tvStatus);

        loadSettings();

        btnSave.setOnClickListener(v -> saveSettings());
        btnStart.setOnClickListener(v -> startMonitor());
        btnStop.setOnClickListener(v -> stopMonitor());

        updateStatus();
    }

    private void loadSettings() {
        etBoards.setText(prefs.getString("boards", "e-coupon,Lifeismoney"));
        etKeywords.setText(prefs.getString("keywords", "衛生紙,面紙"));
        etLineToken.setText(prefs.getString("line_token", ""));
        etInterval.setText(String.valueOf(prefs.getInt("interval", 5)));
    }

    private void saveSettings() {
        String boards   = etBoards.getText().toString().trim();
        String keywords = etKeywords.getText().toString().trim();
        String token    = etLineToken.getText().toString().trim();
        String intervalStr = etInterval.getText().toString().trim();

        if (boards.isEmpty() || keywords.isEmpty()) {
            Toast.makeText(this, "看板和關鍵字不能空白", Toast.LENGTH_SHORT).show();
            return;
        }

        int interval = 5;
        try { interval = Integer.parseInt(intervalStr); } catch (Exception e) { /* use default */ }

        prefs.edit()
            .putString("boards", boards)
            .putString("keywords", keywords)
            .putString("line_token", token)
            .putInt("interval", interval)
            .apply();

        Toast.makeText(this, "設定已儲存", Toast.LENGTH_SHORT).show();
    }

    private void startMonitor() {
        saveSettings();
        Intent intent = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updateStatus();
        Toast.makeText(this, "監控已啟動", Toast.LENGTH_SHORT).show();
    }

    private void stopMonitor() {
        Intent intent = new Intent(this, MonitorService.class);
        stopService(intent);
        updateStatus();
        Toast.makeText(this, "監控已停止", Toast.LENGTH_SHORT).show();
    }

    private void updateStatus() {
        boolean running = MonitorService.isRunning;
        tvStatus.setText(running ? "🟢 監控中" : "🔴 已停止");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }
}
