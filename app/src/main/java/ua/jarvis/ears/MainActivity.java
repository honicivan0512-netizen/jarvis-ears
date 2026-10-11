package ua.jarvis.ears;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private EditText addr, token;
    private CheckBox mute;
    private TextView logView;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences sp = getSharedPreferences("j", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Джарвіс: слухає у фоні");
        title.setTextSize(20);
        root.addView(title);

        TextView h1 = new TextView(this);
        h1.setText("Адреса Джарвіса (A12 у Tailscale), наприклад http://100.101.102.103:8765");
        root.addView(h1);
        addr = new EditText(this);
        addr.setSingleLine(true);
        addr.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        addr.setText(sp.getString("base", "http://"));
        root.addView(addr);

        TextView h2 = new TextView(this);
        h2.setText("Токен (команда /voice у Telegram)");
        root.addView(h2);
        token = new EditText(this);
        token.setSingleLine(true);
        token.setText(sp.getString("token", ""));
        root.addView(token);

        mute = new CheckBox(this);
        mute.setText("Під час розмови вимикати звуки сповіщень (щоб не пікало)");
        mute.setChecked(sp.getBoolean("mute", true));
        root.addView(mute);

        Button start = new Button(this);
        start.setText("Запустити слухання");
        start.setOnClickListener(v -> startListening());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Зупинити");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ListenService.class));
            ListenService.say("Зупинено.");
        });
        root.addView(stop);

        Button dnd = new Button(this);
        dnd.setText("Дозволити керувати «Не турбувати» (раджу)");
        dnd.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            } catch (Exception e) {
                ListenService.say("Не вдалося відкрити налаштування: " + e.getMessage());
            }
        });
        root.addView(dnd);

        Button batt = new Button(this);
        batt.setText("Не присипляти застосунок (раджу натиснути)");
        batt.setOnClickListener(v -> askBattery());
        root.addView(batt);

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(14);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ListenService.sink = text -> runOnUiThread(() -> {
            logView.setText(text);
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
        logView.setText(ListenService.logText());
    }

    @Override
    protected void onPause() {
        super.onPause();
        ListenService.sink = null;
    }

    private void startListening() {
        String a = addr.getText().toString().trim();
        String t = token.getText().toString().trim();
        if (!a.startsWith("http") || t.length() < 8) {
            ListenService.say("Впиши адресу (з http://) і токен.");
            return;
        }
        getSharedPreferences("j", MODE_PRIVATE).edit().putString("base", a).putString("token", t)
                .putBoolean("mute", mute.isChecked()).apply();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 1);
            ListenService.say("Дозволь мікрофон і натисни «Запустити» ще раз.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        Intent i = new Intent(this, ListenService.class);
        startForegroundService(i);
        ListenService.say("Запускаю...");
    }

    private void askBattery() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                ListenService.say("Уже дозволено працювати у фоні.");
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            ListenService.say("Не вдалося відкрити налаштування: " + e.getMessage());
        }
    }
}
