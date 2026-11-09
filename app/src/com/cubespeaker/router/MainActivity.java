package com.cubespeaker.router;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Minimal 10-foot UI: enable/disable the router and show the live hint value.
 */
public class MainActivity extends Activity {
    private TextView status;
    private Button toggle;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateUi();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(80, 80, 80, 80);
        root.setBackgroundColor(Color.rgb(16, 16, 16));

        TextView title = new TextView(this);
        title.setText("Alexa音声をCubeスピーカーから出す");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(20);
        status.setTextColor(Color.LTGRAY);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 40, 0, 40);
        root.addView(status);

        toggle = new Button(this);
        toggle.setTextSize(20);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setEnabledState(!isEnabledState());
            }
        });
        root.addView(toggle);

        Button test = new Button(this);
        test.setText("テスト音を鳴らす（Cubeスピーカー）");
        test.setTextSize(20);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Lift the Fire OS amplifier mute first; the service also
                // re-asserts it, but the button may be pressed right after boot.
                HintUtil.setSpeakerMute(false);
                TestTone.play(MainActivity.this);
            }
        });
        root.addView(test);

        TextView note = new TextView(this);
        note.setText("※ Alexa由来の音声（応答・音楽）がCube本体スピーカーから出ます。\n"
                + "通常の動画アプリの音声はTV（HDMI）のままです。\n"
                + "「テスト音」がCubeから聞こえればスピーカーは動作しています。");
        note.setTextSize(16);
        note.setTextColor(Color.GRAY);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, 40, 0, 0);
        root.addView(note);

        setContentView(root);
        toggle.requestFocus();

        if (isEnabledState()) {
            startForegroundService(new Intent(this, RouterService.class));
        }
        handler.post(refresh);
    }

    private boolean isEnabledState() {
        SharedPreferences sp = getSharedPreferences(RouterService.PREFS, MODE_PRIVATE);
        return sp.getBoolean(RouterService.KEY_ENABLED, true);
    }

    private void setEnabledState(boolean enabled) {
        SharedPreferences sp = getSharedPreferences(RouterService.PREFS, MODE_PRIVATE);
        sp.edit().putBoolean(RouterService.KEY_ENABLED, enabled).commit();
        if (enabled) {
            startForegroundService(new Intent(this, RouterService.class));
        } else {
            stopService(new Intent(this, RouterService.class));
            try {
                HintUtil.set(this, 1);
            } catch (Throwable ignored) {
            }
            // Restore the stock mute (TV mode) so the Cube speaker does not
            // start playing system sounds while the router is disabled.
            HintUtil.restoreSpeakerMuteIfHdmi();
        }
        updateUi();
    }

    private void updateUi() {
        boolean enabled = isEnabledState();
        toggle.setText(enabled ? "無効にする" : "有効にする");

        String hintText;
        try {
            hintText = String.valueOf(HintUtil.get(this));
        } catch (Throwable t) {
            hintText = "取得失敗";
        }
        status.setText((enabled ? "状態: 有効" : "状態: 無効")
                + "\nHDMI hint = " + hintText
                + "\nスピーカーmute: " + (enabled ? "解除（サービスが維持）" : "通常"));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
