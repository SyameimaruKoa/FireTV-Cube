# Fire TV Cube (AFTR / raven) — Alexa TTSを本体スピーカーから出す

最終更新: 2026-10-22
対象: Amazon Fire TV Cube (型番 `AFTR`, device `raven`), Fire OS 7.7.1.3 (PS7713/5443), build `PS7713.5443N`, Android 9
実機: ADB `192.168.55.40`, locale `ja-JP`, region `JP`
状態: **実装・実機検証済み**（`com.cubespeaker.router` をインストール済み）

---

## TL;DR

- Alexa の TTS は `AudioAttributes` に **`FLAG_AUTO_OUT` (`0x10000000`)** を付けて再生される。
- Fire OS の `AmazonAudioManager.setHdmiOutStateHint(0)` を維持すると、`FLAG_AUTO_OUT` のトラックだけが **Cube 本体スピーカー**（output 13 / `AudioOut_D` / `AUDIO_DEVICE_OUT_SPEAKER`）へルーティングされる。
- 通常の動画・音楽（`flags=0`）は **HDMI**（output 21 / `AudioOut_15` / `0x40000000`）のまま。音量も変化しない。
- 本リポジトリの `com.cubespeaker.router` が hint 0 を 500 ms ごとに再設定するフォアグラウンドサービス。`BOOT_COMPLETED` で自動起動し、UI で有効/無効を切替できる。
- 実測: AUTO_OUT ビープ → 本体スピーカー、通常メディアビープ → HDMI。hint を手動で 1 にしても 500 ms 以内に 0 へ復帰。再起動後もサービス自動起動を確認。

---

## 1. 症状と制約

### 症状

Fire TV Cube の Alexa 応答（TTS）が、Cube 本体のスピーカーではなく TV（HDMI）から出る。TV の電源が入っている通常の Fire TV モードで発生する。

### 端末の制約

| 項目 | 値 |
|---|---|
| root / `su` | なし |
| `ro.build.type` | `user` |
| `ro.debuggable` | `0` |
| `ro.secure` | `1` |
| 署名権限 | なし（vizzini の APK 改変・システムアプリのインストール不可） |
| ADB | 利用可能（shell uid 2000） |

このため、vizzini の改造やシステム専用 API の直接利用はできない。**一般アプリから呼べる API だけで完結させる**必要がある。

---

## 2. 音声ルーティングの解析

### AudioPolicyManager (APM)

`adb shell dumpsys media.audio_policy` の抜粋（`dumps/audio_policy_dump.txt`）:

```text
Primary Output: 21
Phone state: AUDIO_MODE_NORMAL
Force use for communications 0
Force use for media 12          # FORCE_HDMI_SYSTEM_AUDIO_ENFORCED
Force use for record 0
Force use for dock 8
Force use for system 0
Force use for hdmi system audio 0
Force use for encoded surround output 13
TTS output not available
```

利用可能な出力デバイス:

| デバイス | id | type | 備考 |
|---|---:|---|---|
| HDMI-Out | 35426 | `AUDIO_DEVICE_OUT_AUX_DIGITAL` | TV |
| Speaker | 6 | `AUDIO_DEVICE_OUT_SPEAKER` (`0x2`) | Cube 本体 |
| Stub-Out | 9 | `AUDIO_DEVICE_OUT_DEFAULT` | HDMI primary の実体 |

出力ストリーム:

| Output | Devices | 実体 |
|---:|---|---|
| 13 | `0x00000002` | Speaker (`AudioOut_D`) |
| 21 | `0x40000000` | `AUDIO_DEVICE_OUT_DEFAULT｜AUDIO_DEVICE_OUT_STUB` (`AudioOut_15`, HDMI primary) |

`dumpsys media.audio_flinger` でも、通常メディアは `AudioOut_15`（`0x40000000`）、AUTO_OUT は `AudioOut_D`（`0x2`）に出ることを確認済み。

---

## 3. 鍵となる仕組み: `FLAG_AUTO_OUT` + HDMI hint

### 定数

`amazon.media.AmazonAudioManager` の実行時定数:

| 定数 | 値 |
|---|---:|
| `FLAG_HDMI_OUT` | `0x01000000` |
| `FLAG_SPEAKER_OUT` | `0x02000000` |
| `FLAG_A2DP_OUT` | `0x04000000` |
| `FLAG_STUB_OUT` | `0x08000000` |
| `FLAG_AUTO_OUT` | `0x10000000` |
| `FOR_MEDIA` | `1` |
| `FOR_SYSTEM` | `4` |
| `FORCE_SPEAKER` | `1` |
| `FORCE_HDMI_SYSTEM_AUDIO_ENFORCED` | `12` |
| `ROUTING_MODE_SPEAKER` | `1` |
| `ROUTING_MODE_HDMI` | `18` |
| `HDMI_OUT_ACTIVE` | `1` |
| `HDMI_NOT_ACTIVE` | `0` |
| `HDMI_STATE_UNKNOWN` | `2` |

### `get/setHdmiOutStateHint`

`AmazonAudioService$BinderService` の実装:

- `getHdmiOutStateHint()`
- `setHdmiOutStateHint(int)`

いずれも要求する権限は **`android.permission.MODIFY_AUDIO_SETTINGS` のみ**。一般アプリ（`untrusted_app`）から呼べることを実機で確認した。`set` は値をサービス内に保持し、ネイティブ APM の `setAudioDeviceStateHint(state)` に渡す。

### システム側の hint 更新元

`com.amazon.tv.devicecontrol` の dual-mode 実装:

- `HdmiStateToAudioManagerReporter`: `HDMI_ACTIVE → hint 1`、`HDMI_NOT_ACTIVE → hint 0`
- `ReportHdmiStateOnModeChange`:
  - `ModeState.FIRE_TV → hint 1`（TV を見ている通常モード）
  - `ModeState.ECHO → hint 0`（TV オフ / Echo モード）

つまり、TV がオンの `FIRE_TV` モードではシステムが hint 1 を設定する。これが「Alexa TTS が HDMI から出る」直接の原因。

`dual_mode_behavior_config` は `Settings.Global` の int（`0`=manual, `1`=automatic）。手動で ECHO 固定にすれば hint 0 になるが、TV 表示や HDMI 動作にも影響するため採用しなかった。

### 採用しなかった API

以下は権限不足・未サポート・副作用のため採用していない:

- `setSpeakerMute`, `setAudioRoutingMode`, `enableDualOutput`, `notifySpeechState`, `setForceUse`, `setForceDeviceRouting`
- システムの `HdmiStateToAudioManagerReporter` / `ReportHdmiStateOnModeChange` の無効化
- vizzini APK の改変（Amazon 署名が必要）
- `mute_tts_hdmi` などの `mute_tts_*` 設定（この経路には効かないことを実測）

---

## 4. vizzini TTS の属性

`vizzini` の `VizziniInitComponent.smali`:

```smali
new-instance v6, Landroid/media/AudioAttributes$Builder;
invoke-direct {v6}, Landroid/media/AudioAttributes$Builder;-><init>()V
const/4 v7, 0x2
invoke-virtual {v6, v7}, Landroid/media/AudioAttributes$Builder;->setContentType(I)Landroid/media/AudioAttributes$Builder;
...
sget v7, Lamazon/media/AmazonAudioManager;->FLAG_AUTO_OUT:I
invoke-virtual {v6, v7}, Landroid/media/AudioAttributes$Builder;->setFlags(I)Landroid/media/AudioAttributes$Builder;
```

- `setContentType(2)` = `CONTENT_TYPE_MUSIC`
- `setFlags(FLAG_AUTO_OUT)`
- usage は明示されないため **デフォルトの `USAGE_MEDIA` (`1`)**

したがって TTS トラックは次の属性になる:

```text
usage = 1 (USAGE_MEDIA)
content = 2 (CONTENT_TYPE_MUSIC)
flags = 0x10000000 (FLAG_AUTO_OUT)
```

`dumpsys media.audio_flinger` の過去ログでは、毎日 09:00 前後に 24 kHz モノラル・flags `0x201`・約 10 秒・-10 dB の TTS トラックが `AudioOut_D`（本体スピーカー）に出ている。これは本アプリ導入前から記録されており、vizzini の TTS が本体スピーカー経路を使えることの実機証拠である。

---

## 5. ルーティング実測マトリクス

`hint=0`、`com.cubespeaker.router` 実行中に、`AudioAttributes` を変えたビープで実測（`dumps/flinger_routing_matrix.txt`）:

| hint | content | usage | flags | 出力 |
|---:|---:|---:|---|---|
| 0 | 2 (MUSIC) | 1 (MEDIA) | `0x10000000` (AUTO_OUT) | **Speaker** (`AudioOut_D`, `0x2`) |
| 0 | 2 (MUSIC) | 1 (MEDIA) | `0` | HDMI (`AudioOut_15`, `0x40000000`) |
| 1 | 2 (MUSIC) | 1 (MEDIA) | `0x10000000` | HDMI |
| 0 | 2 (MUSIC) | 13 (ASSISTANCE_SONIFICATION) | `0x10000000` | **Speaker** |
| 任意 | — | legacy `STREAM_TTS=9` | — | HDMI（vizzini は使わない） |

このように、**hint 0 は AUTO_OUT トラックにだけ効く**。通常のメディア（flags 0）は HDMI のままで、音量も -1.4 dB のまま変化しない。

---

## 6. 実装

### アプリ構成

| ファイル | 役割 |
|---|---|
| `app/AndroidManifest.xml` | package `com.cubespeaker.router`, min/target 28, 権限, service/receiver/activity |
| `app/src/com/cubespeaker/router/HintUtil.java` | `amazon.media.AmazonAudioManager` の反射ラッパー（コンパイル時依存なし） |
| `app/src/com/cubespeaker/router/RouterService.java` | 500 ms ごとに hint を取得し、0 以外なら 0 に再設定するフォアグラウンドサービス |
| `app/src/com/cubespeaker/router/BootReceiver.java` | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` / `HDMI_AUDIO_PLUG` でサービス起動 |
| `app/src/com/cubespeaker/router/MainActivity.java` | 有効/無効トグル、現在の hint 表示（10-foot UI） |
| `scripts/build_app.sh` | ビルド・署名スクリプト |

### 権限

```xml
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

`MODIFY_AUDIO_SETTINGS` は一般権限のため、インストール時に自動付与される。

### サービス動作

- 起動時に `startForeground()` で通知を出す（チャンネル `router`, `IMPORTANCE_LOW`）。
- 500 ms ごとに `getHdmiOutStateHint()` を呼ぶ。
- 値が 0 以外なら `setHdmiOutStateHint(0)` を呼び、logcat に `hint 1 -> 0` のように記録。
- 取得失敗時は 5 秒間隔でリトライ（ログのスパム防止）。
- `START_STICKY` でシステムによる再起動に備える。
- 明示的な停止（UI の「無効にする」）時は hint 1 に戻してから停止。

### 通知

フォアグラウンドサービスのため常時通知が出る。`IMPORTANCE_LOW` で音は鳴らない。TV の通知シェードを開かなければ目立たない。

---

## 7. ビルドとインストール

### 前提

- JDK 17（この環境では `.work/jdk`）
- Android SDK `android.jar`（API 28 / Android 9）
- Build Tools 28（`aapt`, `d8`, `zipalign`, `apksigner`）

`scripts/build_app.sh` は上記のローカルパスを前提にしている。別環境ではスクリプト冒頭の `JDK` / `SDK` / `BT` を変更する。

### ビルド

```bash
cd /home/syameimarukoa/FireTV-Cube
./scripts/build_app.sh
# => CubeSpeakerRouter.apk (debug signed, .gitignore 対象)
```

### インストール

```bash
ADB=/tmp/opencode/pt/platform-tools/adb
$ADB install -r CubeSpeakerRouter.apk
# 初回は一度起動してサービスを開始（以降は BOOT_COMPLETED で自動起動）
$ADB shell am start -n com.cubespeaker.router/.MainActivity
```

サービスだけを直接開始する場合:

```bash
$ADB shell am start-foreground-service -n com.cubespeaker.router/.RouterService
```

### 状態確認

```bash
$ADB shell dumpsys activity services com.cubespeaker.router
$ADB logcat -s CubeSpeaker
$ADB shell am start -n com.cubespeaker.router/.MainActivity   # UI に hint を表示
```

---

## 8. 実機検証

### 8.1 ルーティング

`hint=0` で、vizzini TTS と同一属性のビープを再生:

```bash
# AUTO_OUT (vizzini TTS と同じ属性) -> 本体スピーカー
$ADB shell "CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin Beep2 2 1 268435456 4 700"
# 通常メディア -> HDMI
$ADB shell "CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin Beep2 2 1 0 4 700"
```

`dumpsys media.audio_flinger` の結果:

```text
AUTO_OUT:   AudioOut_D  | Output device: 0x2 (AUDIO_DEVICE_OUT_SPEAKER)
plain:      AudioOut_15 | Output device: 0x40000000 (AUDIO_DEVICE_OUT_DEFAULT|AUDIO_DEVICE_OUT_STUB)
```

証拠: `dumps/flinger_routing_matrix.txt`

### 8.2 hint の再設定

`AudioProbe` で手動で hint 1 を設定:

```bash
$ADB shell "run-as com.probe.untrusted sh -c 'CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin AudioProbe setHdmiOutStateHint=1'"
```

結果:

```text
I/CubeSpeaker: hint 1 -> 0
getHdmiOutStateHint() = 0
```

500 ms 以内にサービスが 0 に戻すことを確認。証拠: `dumps/service_and_boot_evidence.txt`

### 8.3 再起動後の自動起動

`adb reboot` 後、`BOOT_COMPLETED` が `com.cubespeaker.router.BootReceiver` に配信され、サービスが自動起動した:

```text
Start proc 8716:com.cubespeaker.router/u0a118 for broadcast com.cubespeaker.router/.BootReceiver
...
isForeground=true foregroundId=1129660997
getHdmiOutStateHint() = 0
```

### 8.4 通常メディアへの影響

SmartTube 起動、HOME、通常メディアビープのいずれでも hint は 0 のまま。HDMI 出力の音量・ルーティングは変化しなかった。

### 8.5 実 Alexa 応答について

音声入力の直接トリガー（`TextRequestActivity`, `SpeechToTextService`, wake word, remote の音声キー）は署名権限でブロックされており、実 Alexa 応答を ADB からオンデマンドに発生させることはできなかった。検証は次の 2 点で行っている:

1. vizzini の TTS 属性（usage=1, content=2, flags=AUTO_OUT）と完全一致するビープが、hint 0 で本体スピーカーに出ること。
2. 導入前から記録されている毎日 09:00 の TTS トラックが `AudioOut_D` に出ていること。

---

## 9. トレードオフと注意

### Alexa 経由の音楽も本体スピーカーになる

`FLAG_AUTO_OUT` は TTS だけでなく、Alexa 経由の音楽（`com.amazon.alexamediaplayer.runtime.ftv`）にも付く。このため、Alexa に「音楽をかけて」と頼んだ場合も Cube 本体スピーカーから再生される。

- 単体の Amazon Music アプリ（`com.amazon.bueller.music`）は `FLAG_AUTO_OUT` を使っておらず、UI から再生する場合は HDMI のまま。
- TV のスピーカーで Alexa 音楽を聴きたい場合は、本アプリを一時的に「無効」にする（hint 1 に戻る）。

### 通知

フォアグラウンドサービスの常時通知が出る。`IMPORTANCE_LOW` のため音は鳴らない。

### モード変更直後の短いウィンドウ

システムが `FIRE_TV` モードへの変更で hint 1 を設定してから、サービスが 0 に戻すまで最大 500 ms のウィンドウがある。TTS の開始までに通常は間に合うが、直後に発話した場合だけは HDMI に出る可能性がゼロではない。気になる場合は `POLL_MS` を 250 ms に短縮できる（`RouterService.java`）。

### 強制停止された場合

Fire OS がアプリを強制停止した場合、次回のモード変更で hint 1 に戻る可能性がある。アプリを起動し直すか、再起動すればサービスが再開する。foreground service + `START_STICKY` で通常は停止しない。

### OS アップデート

Fire OS のアップデートで `AmazonAudioManager` の実装や hint の意味が変わる可能性がある。アップデート後は `dumpsys media.audio_flinger` で再確認する。

---

## 10. 元に戻す / アンインストール

### アプリで無効化

`MainActivity` を開き、リモコンの決定ボタンで「無効にする」を選ぶ。hint 1 に戻り、サービスが停止する。

### アンインストール

```bash
$ADB uninstall com.cubespeaker.router
```

アンインストール前に必ず「無効にする」を実行するか、後述の `AudioProbe` で `setHdmiOutStateHint=1` を実行してから削除する。再起動すれば devicecontrol がモードに応じて hint を再設定するため、通常は問題ない。

### 検証用アプリの削除

```bash
$ADB uninstall com.probe.untrusted
$ADB shell rm -f /data/local/tmp/helper.dex
```

---

## 11. 付録: 主要コマンド

```bash
ADB=/tmp/opencode/pt/platform-tools/adb

# 現在の hint を読む（検証用 Probe アプリの UID で実行）
$ADB shell "run-as com.probe.untrusted sh -c 'CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin AudioProbe'" | grep Hint

# hint を 0/1 に設定
$ADB shell "run-as com.probe.untrusted sh -c 'CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin AudioProbe setHdmiOutStateHint=0'"
$ADB shell "run-as com.probe.untrusted sh -c 'CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin AudioProbe setHdmiOutStateHint=1'"

# AUTO_OUT ビープ（vizzini TTS と同じ属性）
$ADB shell "CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin Beep2 2 1 268435456 4 700"

# 通常メディアビープ
$ADB shell "CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin Beep2 2 1 0 4 700"

# ルーティング確認
$ADB shell dumpsys media.audio_flinger | grep -E 'AudioOut_|Output device'

# サービス状態
$ADB shell dumpsys activity services com.cubespeaker.router

# ログ
$ADB logcat -s CubeSpeaker
```

検証用ヘルパーのソースは `tools/` に置いてある（`tools/README.md` 参照）。

> 実機検証後、検証用の `com.probe.untrusted` と `/data/local/tmp/helper.dex` は削除済み。再検証する場合は `tools/README.md` の手順で再構築する。最終アプリ `com.cubespeaker.router` は残している。

---

## 12. ファイル一覧

| パス | 内容 |
|---|---|
| `app/` | 最終アプリのソース |
| `scripts/build_app.sh` | ビルド・署名スクリプト |
| `docs/README.md` | 本ドキュメント |
| `dumps/audio_policy_dump.txt` | APM の初期ダンプ |
| `dumps/flinger_routing_matrix.txt` | hint 0 でのルーティング実測 |
| `dumps/service_and_boot_evidence.txt` | サービス・再起動・hint 再設定の証拠 |
| `artifacts/router_ui.png` | アプリ UI のスクリーンショット |
| `tools/` | 検証用ヘルパー（AudioProbe / Beep2 / Probe） |

---

## 13. 参考: 解析の要点（コードリーディング）

- `AmazonAudioService$BinderService.setHdmiOutStateHint` は `MODIFY_AUDIO_SETTINGS` をチェックし、`access$1502` に値を保持して `setAudioDeviceStateHint(state)` を呼ぶ。
- `HdmiReporterModule.provideHdmiStateReporter`: dual-mode 非対応なら `NoOpHdmiStateReporter`、`AmazonAudioManager.getDualModeAudioCapability()` が true なら `HdmiStateToAudioManagerReporter`。
- `ReportHdmiStateOnModeChange.onModeChanged`: `ECHO → HDMI_NOT_ACTIVE (0)`, `FIRE_TV → HDMI_ACTIVE (1)`。
- `ModeState`: `FIRE_TV(0)`, `ECHO(1)`。
- `VizziniInitComponent`: `setContentType(2)` + `FLAG_AUTO_OUT`。usage はデフォルト 1。
- legacy の `STREAM_TTS=9` は `AudioOut_15`（HDMI）に固定され、`mute_tts_hdmi` を設定しても変わらない。vizzini は legacy TTS ストリームを使わない。
