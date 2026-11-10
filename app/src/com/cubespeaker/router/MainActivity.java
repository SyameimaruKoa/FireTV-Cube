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
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Minimal 10-foot UI: enable/disable the router and show the live hint value.
 */
public class MainActivity extends Activity {
    private TextView status;
    private Button toggle;
    private CheckBox allAudio;

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
        root.setPadding(80, 40, 80, 40);
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

        allAudio = new CheckBox(this);
        allAudio.setText("テレビを含むすべての音声をCubeから出す");
        allAudio.setTextSize(20);
        allAudio.setChecked(isAllAudioState());
        allAudio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAllAudioState(allAudio.isChecked());
            }
        });
        root.addView(allAudio);

        TextView note = new TextView(this);
        note.setText("※ Alexa由来の音声はCube本体スピーカーから出ます。\n"
                + "通常の動画・音楽はTV（HDMI）のままです。\n"
                + "「すべての音声」をオンにすると、映像はそのままに\n"
                + "動画・音楽もCubeから鳴り、TVは無音になります。\n"
                + "「テスト音」でスピーカーを確認できます。");
        note.setTextSize(14);
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

    private boolean isAllAudioState() {
        SharedPreferences sp = getSharedPreferences(RouterService.PREFS, MODE_PRIVATE);
        return sp.getBoolean(RouterService.KEY_ALL_AUDIO, false);
    }

    private void setAllAudioState(boolean all) {
        SharedPreferences sp = getSharedPreferences(RouterService.PREFS, MODE_PRIVATE);
        sp.edit().putBoolean(RouterService.KEY_ALL_AUDIO, all).commit();
        if (isEnabledState()) {
            // Reload the running service so it starts/stops the silent
            // speaker track immediately.
            Intent service = new Intent(this, RouterService.class);
            service.setAction(RouterService.ACTION_RELOAD);
            startForegroundService(service);
        }
        updateUi();
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
        allAudio.setChecked(isAllAudioState());

        String hintText;
        try {
            hintText = String.valueOf(HintUtil.get(this));
        } catch (Throwable t) {
            hintText = "取得失敗";
        }
        status.setText((enabled ? "状態: 有効" : "状態: 無効")
                + "\nHDMI hint = " + hintText
                + "\nモード: " + (isAllAudioState()
                        ? "全音声 → Cube（TVは無音）"
                        : "Alexaのみ → Cube / 通常メディア → HDMI")
                + "\nスピーカーmute: " + (enabled ? "解除（サービスが維持）" : "通常"));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
