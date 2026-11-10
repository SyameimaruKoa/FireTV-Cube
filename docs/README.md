# Fire TV Cube (AFTR / raven) — Alexa TTSを本体スピーカーから出す

最終更新: 2026-11-10
対象: Amazon Fire TV Cube (型番 `AFTR`, device `raven`), Fire OS 7.7.1.3 (PS7713/5443), build `PS7713.5443N`, Android 9
実機: ADB `192.168.55.40`, locale `ja-JP`, region `JP`
状態: **実機検証済み**（`com.cubespeaker.router` v1.2 インストール済み）。再起動後も実際の Alexa 応答が Cube 本体スピーカーから聞こえることをユーザーが確認済み。Alexa 応答中だけ TV(HDMI) 音声が一時停止する制約がある（9 章）。任意の「全音声を Cube へ」モードも実装し、ユーザーが実音で確認済み。

---

## TL;DR

- Alexa の TTS は `AudioAttributes` に **`FLAG_AUTO_OUT` (`0x10000000`)** を付けて再生される。
- Fire OS の `AmazonAudioManager.setHdmiOutStateHint(0)` を維持すると、`FLAG_AUTO_OUT` のトラックだけが **Cube 本体スピーカー**（output 13 / `AudioOut_D` / `AUDIO_DEVICE_OUT_SPEAKER`）へルーティングされる。
- ただし **ルーティングだけでは無音**。Fire OS は `STREAM_MUSIC` が HDMI にあると `applySpeakerStatus()` で `AudioSystem.setParameters("speaker_mute=true;")` を実行し、本体スピーカーのアンプをミュートする。これが「ルーティングは正しいのに聞こえない」直接の原因だった。
- 本アプリは hint 0 の維持に加えて **`speaker_mute=false`** を設定する。`AudioSystem.setParameters` は署名権限なしの一般アプリから実行でき、実機で `AudioALSAHardware: +setParameters(): speaker_mute=false` を確認済み。
- 通常の動画・音楽（`flags=0`）は **HDMI**（`AudioOut_15` / `0x40000000`）のまま。出力 index（21/29/133 など）は構成により変わる。音量も変化しない。
- 本リポジトリの `com.cubespeaker.router` が hint 0 とアンプmute解除を維持するフォアグラウンドサービス。`BOOT_COMPLETED` で自動起動し、UI で有効/無効を切替できる。
- 実測: AUTO_OUT ビープ → 本体スピーカー、通常メディアビープ → HDMI。hint を手動で 1 にしても 250 ms 以内に 0 へ復帰。`speaker_mute=false` は再起動後も維持され、実 Alexa 応答も本体スピーカーから再生された（ユーザー可聴確認済み）。
- **制約**: Alexa 応答の間だけ、TV（HDMI）の音声が約 1〜3 秒止まる。Fire OS の ASP が再生デバイスを `speaker` / `hdmi` のどちらか一方に切り替え、`hdmi_mute=1` を設定するため。root なしで回避できる方法は確認できていない（9 章）。
- **「全音声を Cube へ」モード（任意・v1.2 で追加）**: 本体スピーカーに**無音の `AudioTrack`** を 1 本だけ維持すると、APM が `Audio Speaker track active 1` → `hdmi_mute=1` とし、通常メディア（`flags=0`）まで output 13 / `AudioOut_D` へ回す。この性質を使い、UI のチェックボックスで「TV を含む全音声を Cube から」に切り替えられる。TV は映像のみ（無音）になる。ユーザーが実音で確認済み（9.2 章）。

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

### スピーカーアンプのミュート（重要）

hint 0 で AUTO_OUT トラックを本体スピーカーへルーティングしても、**音が出ない**。Fire OS の `AmazonAudioServiceShared.applySpeakerStatus()` が次の判定をしている:

```text
device = getDeviceForStream(STREAM_MUSIC)
if ((device & 0x2) != 0)          # STREAM_MUSIC が本体スピーカー
    AudioSettings.setSpeakerStatus(mSpeakerPreference)
else                              # HDMI など、スピーカー以外
    AudioSettings.setSpeakerStatus(1)   # 強制ミュート
```

`AudioSettings.setSpeakerStatus(1)` は `AudioSystem.setParameters("speaker_mute=true;")` を実行し、HAL (`AudioALSAHardware`) が本体スピーカーのアンプをミュートする。逆に status 0 は `speaker_mute=false;`。

FIRE_TV モード（TV オン）では `STREAM_MUSIC` が HDMI にあるため、**`mSpeakerPreference` の値に関係なく必ずミュート**される。このミュートは boot 時 (`initStreamVolumes` / `restoreSpeakerStatus`)、HDMI・有線デバイスの接続変化、Bluetooth A2DP / hearing aid の接続変化、`enableDualOutput()`、`setSpeakerMute()` などで再適用される。

`setSpeakerMute()` は署名権限が必要だが、HAL へ直接 `AudioSystem.setParameters("speaker_mute=false;")` を送る方法は **untrusted_app からでも成功**する（実測）。本アプリはこちらを使う。

実測ログ:

```text
I AudioALSAHardware: +setParameters(): speaker_mute=false
I hash_map_utils: key: 'speaker_mute' value: 'false'
```

`speaker_mute=false` を設定した後、90 秒間 `speaker_mute=true` が再適用されないことも確認した（サービスは 15 秒ごとに安全のため再設定する）。

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
| 0 + 無音 keepalive | 2 (MUSIC) | 1 (MEDIA) | `0` | **Speaker** (`AudioOut_D`) |
| 0 + 無音 keepalive | 2 (MUSIC) | 1 (MEDIA) | `0x10000000` | **Speaker** |
| 任意 | — | legacy `STREAM_TTS=9` | — | HDMI（vizzini は使わない） |

このように、**hint 0 は AUTO_OUT トラックにだけ効く**。通常のメディア（flags 0）は HDMI のままで、音量も -1.4 dB のまま変化しない。ただし本体スピーカーに無音トラックを 1 本維持した場合だけは、APM がメディア全体をスピーカーへ寄せる（v1.2 の全音声モード）。

---

## 6. 実装

### アプリ構成

| ファイル | 役割 |
|---|---|
| `app/AndroidManifest.xml` | package `com.cubespeaker.router`, min/target 28, 権限, service/receiver/activity |
| `app/src/com/cubespeaker/router/HintUtil.java` | `amazon.media.AmazonAudioManager` と `android.media.AudioSystem` の反射ラッパー（hint と `speaker_mute`） |
| `app/src/com/cubespeaker/router/RouterService.java` | hint 0 の維持と `speaker_mute=false` の再適用を行うフォアグラウンドサービス。全音声モードの keepalive も管理 |
| `app/src/com/cubespeaker/router/SpeakerKeepAlive.java` | 全音声モード用の無音 `AudioTrack` を本体スピーカーに維持する |
| `app/src/com/cubespeaker/router/BootReceiver.java` | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` / `HDMI_AUDIO_PLUG` でサービス起動 |
| `app/src/com/cubespeaker/router/MainActivity.java` | 有効/無効トグル、全音声モードのチェックボックス、hint 表示、テスト音ボタン（10-foot UI） |
| `app/src/com/cubespeaker/router/TestTone.java` | 本体スピーカーへ 8 秒の 1 kHz テスト音を鳴らす（UI からのみ） |
| `app/src/com/cubespeaker/router/DebugReceiver.java` | ADB 検証用ブロードキャスト（`SET_ENABLED` / `SET_ALL_AUDIO` / `SET_HINT` / `UNMUTE` / `GET_STATE` / `PLAY_TONE` / `SET_PARAMS`） |
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
- 250 ms ごとに `getHdmiOutStateHint()` を呼ぶ。
- 値が 0 以外なら `setHdmiOutStateHint(0)` を呼び、logcat に `hint 1 -> 0` のように記録。
- 起動時・`onStartCommand` 時・hint 変更時に `speaker_mute=false` を送る。HDMI 接続イベントとの競合に備え、短い間隔で複数回再送する。
- 15 秒ごとに `speaker_mute=false` を再送する安全網を持つ。
- 全音声モードがオンのときは、`USAGE_MEDIA` / 無音（PCM 16-bit, 48 kHz, mono）の `AudioTrack` を 1 本生成し、`setPreferredDevice(TYPE_BUILTIN_SPEAKER)` して流し続ける。オフにすると即座に停止・解放する。オーディオフォーカスは要求しないため、再生中のアプリを止めたり duck したりしない。
- 取得失敗時は 5 秒間隔でリトライ（ログのスパム防止）。
- `START_STICKY` でシステムによる再起動に備える。
- 明示的な停止（UI の「無効にする」）時は hint 1 に戻し、`STREAM_MUSIC` が本体スピーカー以外なら `speaker_mute=true` に戻してから停止する（実測: `music devices=0x40000000` → `speaker_mute=true`）。

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

250 ms 以内にサービスが 0 に戻すことを確認。証拠: `dumps/service_and_boot_evidence.txt`

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

音声入力の直接トリガー（`TextRequestActivity`, `SpeechToTextService`, wake word, remote の音声キー）は署名権限でブロックされており、実 Alexa 応答を ADB からオンデマンドに発生させることはできない。そのため、実際に Alexa へ話しかけて応答を発生させ、logcat と可聴結果を突き合わせて検証した。

実 Alexa TTS 再生中の logcat（抜粋、`/tmp/opencode/tts_speaker.log`）:

```text
Audio Speaker track active 1
setActivePlaybackDevice: speaker
hdmi_mute=1
...
setActivePlaybackDevice: hdmi
hdmi_mute=0
```

- TTS トラックが `AudioOut_D`（本体スピーカー）に出ている。
- 同時に `hdmi_mute=1` になり、TV（HDMI）側は一時停止する。
- 再生終了で `hdmi_mute=0` に戻り、HDMI 音声が復帰する。
- **ユーザーが実機で可聴確認済み**。再起動後も、手動の per-UID 操作なしで Alexa 応答が Cube 本体スピーカーから聞こえることを確認した。

### 8.6 アンプmute解除（`speaker_mute=false`）

アプリ（UID `u0a262`）起動時の logcat:

```text
I CubeSpeaker: service started
I AudioALSAHardware: +setParameters(): speaker_mute=false
I hash_map_utils: key: 'speaker_mute' value: 'false'
```

起動直後に 1.5 秒後・4 秒後の再送も確認:

```text
16:25:10.548 AudioALSAHardware: +setParameters(): speaker_mute=false
16:25:12.050 AudioALSAHardware: +setParameters(): speaker_mute=false
16:25:14.550 AudioALSAHardware: +setParameters(): speaker_mute=false
```

hint は `AudioProbe` で 0 を確認:

```text
getHdmiOutStateHint() = 0
```

**持続性テスト**: `logcat -c` → `speaker_mute=false` → 90 秒待機 → `speaker_mute=true` の出現を確認 → **0 件**。15 秒ごとの安全網による `false` 再送のみ。システムが独断で再ミュートすることはない。

**無効化テスト**: UI で「無効にする」→

```text
I CubeSpeaker: speaker_mute restored (music devices=0x40000000)
I AudioALSAHardware: +setParameters(): speaker_mute=true
I CubeSpeaker: service destroyed
getHdmiOutStateHint() = 1
```

再度「有効にする」→ `service started` / `hint 1 -> 0` / `speaker_mute=false` / hint 0 に復帰。

### 8.7 テスト音

UI の「テスト音を鳴らす（Cubeスピーカー）」ボタンで、`AudioAttributes` = `USAGE_ASSISTANCE_SONIFICATION`、`setPreferredDevice(TYPE_BUILTIN_SPEAKER)` の 8 秒トーンを再生:

```text
I CubeSpeaker: test tone preferred speaker=6 ok=true
I CubeSpeaker: test tone done
```

同時刻の `dumpsys media.audio_flinger`:

```text
Output thread ... name AudioOut_D ... type 0 (MIXER)
  Output device: 0x2 (AUDIO_DEVICE_OUT_SPEAKER)
```

ルーティングとアンプmute解除は確認済み。**ユーザーが実機でテスト音を聞き、Cube 本体スピーカーから鳴ることを確認済み**。Alexa 応答の可聴確認は 8.5 を参照。

### 8.8 全音声モード（v1.2）

UI の「テレビを含むすべての音声をCubeから出す」をオンにすると、`SpeakerKeepAlive` が無音トラックを開始する。`AudioTrack` の属性は `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`、48 kHz・mono・PCM 16-bit、`setPreferredDevice(TYPE_BUILTIN_SPEAKER)`。ゼロサンプルを書き続けるだけで音は出さない。

有効化直後の logcat（アプリ UID `10262`）:

```text
I CubeSpeaker: keepalive active preferred=true speaker=6 session=1825
I APM_AudioPolicyManager: getOutputForAttr() returns output 13 selectedDeviceId 6
I APM_AudioPolicyManager: startOutput() output 13, stream 3, session 1825, caller pid: 6723, uid: 10262
I APM::AudioPolicyEngine_fosExt: Audio Speaker track active 1
I AudioALSAHardware: +setParameters(): hdmi_mute=1
I ASP/ER: setActivePlaybackDevice: speaker
```

この状態で、`flags=0`（通常メディア）の 440 Hz ビープを別プロセス（shell, UID 2000）から再生したところ:

```text
I APM_AudioPolicyManager: startOutput() output 13, stream 3, session 1873, caller pid: 6519, uid: 2000
```

`dumpsys media.audio_flinger` の `AudioOut_D`（`Output device: 0x2 AUDIO_DEVICE_OUT_SPEAKER`）には、keepalive のセッション 1865 とビープのセッション 1873 が同時に active で並んだ。**ユーザーが実機でビープが Cube 本体から鳴ることを確認済み**（TV は `hdmi_mute=1` で無音）。

オフにすると:

```text
I CubeSpeaker: keepalive loop finished
I CubeSpeaker: keepalive stopped
I APM::AudioPolicyEngine_fosExt: Audio Speaker track active 0
I AudioALSAHardware: +setParameters(): hdmi_mute=0
I APM_AudioPolicyManager: stopOutput() output 13, stream 3, session 1825, ...
```

その後、通常メディアは再び HDMI（`AudioOut_15` / output 21 系）へ戻ることを確認した。

---

## 9. トレードオフと注意

### 9.1 Alexa 経由の音楽も本体スピーカーになる

`FLAG_AUTO_OUT` は TTS だけでなく、Alexa 経由の音楽（`com.amazon.alexamediaplayer.runtime.ftv`）にも付く。このため、Alexa に「音楽をかけて」と頼んだ場合も Cube 本体スピーカーから再生される。

- 単体の Amazon Music アプリ（`com.amazon.bueller.music`）は `FLAG_AUTO_OUT` を使っておらず、UI から再生する場合は HDMI のまま。
- TV のスピーカーで Alexa 音楽を聴きたい場合は、本アプリを一時的に「無効」にする（hint 1 に戻る）。

### 9.2 「全音声を Cube へ」モード（v1.2・任意）

Alexa だけでなく、HDMI の動画・音楽も含めて全て Cube 本体スピーカーから出すモードを v1.2 で追加した。UI の「テレビを含むすべての音声をCubeから出す」で切り替える（既定はオフ）。

**仕組み**: 本体スピーカーを preferred device にした**無音の `AudioTrack`**（`USAGE_MEDIA`, 48 kHz mono, ゼロサンプル）を 1 本だけ維持する。Fire OS の APM はこれを `Audio Speaker track active 1` と認識し、`hdmi_mute=1` と `setActivePlaybackDevice: speaker` を実行する。その結果、同じメディア strategy に属する通常の `STREAM_MUSIC`（`flags=0`）も output 13 / `AudioOut_D` へ回る。無音トラック自体は音を出さないので、実際に鳴っているメディアだけが Cube から聞こえる。

**制約**:

- モード中は TV が `hdmi_mute=1` で**無音**になる（映像はそのまま）。TV のスピーカーと Cube の同時出力ではない。
- 全メディアが Cube の 1 本のミキサーに集約されるため、音量は Cube 本体のボリュームで調整する。
- 無音トラックはオーディオフォーカスを要求しない。再生中のアプリを止めたり duck したりはしない。
- アプリを無効化するかモードをオフにすると、トラック停止 → `Audio Speaker track active 0` → `hdmi_mute=0` となり、通常メディアは HDMI に戻る（8.8 で実測）。

**以前試して効果がなかった方法**（記録として）:

| 試した方法 | 結果 |
|---|---|
| `AudioSystem.setForceUse(FOR_MEDIA, FORCE_SPEAKER)` | 戻り値 1。しかし `dumpsys media.audio_policy` の `Force use for media` は 12 のまま変化しない |
| `AmazonAudioManager.setForceDeviceRouting(2)` | `setForceDeviceRouting is not supported on this platform!`（`isPlatformFireTvSmart()` が false） |
| `AudioSystem.setAudioRoutingForUid(uid, 1)`（transaction 52） | 戻り値 0 だが、その UID のメディアは HDMI のまま |
| `AudioSystem.setForceUse` の全 usage × config スイープ | media は 12 のまま。変更できたのは無関係な encoded surround のみ |
| HAL パラメータ（`routing=2`, `output_device=2`, `speaker=1`, `hdmi_mute=1;speaker_mute=false` など） | すべて `startOutput() output 133`（HDMI）のまま |
| `AmazonAudioService.enableDualOutput()`（HDMI + スピーカー同時出力） | 署名権限 `com.amazon.permission.FORCE_SIMULTANEOUS_AUDIO_OUTPUT` が必要で実行不可 |

この端末の HDMI メディア出力は APM が `FORCE_HDMI_SYSTEM_AUDIO_ENFORCED` (12) で固定しており、一般アプリから force-use を変更するレバーは存在しない。しかし「本体スピーカーで active なトラックが存在する」という別の条件が APM の再評価を引き起こし、メディア strategy 全体をスピーカーへ移す。force-use を書き換えるのではなく、APM の判定材料を満たすアプローチである。

なお、同時出力（TV と Cube の両方から）は引き続き非対応。HDMI を含む全音声を Cube から出すには、本モード（TV は無音）を使う。

### 9.3 通知

フォアグラウンドサービスの常時通知が出る。`IMPORTANCE_LOW` のため音は鳴らない。

### 9.4 モード変更直後の短いウィンドウ

システムが `FIRE_TV` モードへの変更で hint 1 を設定してから、サービスが 0 に戻すまで最大 250 ms のウィンドウがある。TTS の開始までに通常は間に合うが、直後に発話した場合だけは HDMI に出る可能性がゼロではない。

### 9.5 強制停止された場合

Fire OS がアプリを強制停止した場合、次回のモード変更で hint 1 に戻る可能性がある。アプリを起動し直すか、再起動すればサービスが再開する。foreground service + `START_STICKY` で通常は停止しない。

### 9.6 OS アップデート

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

# アプリの状態（hint / speaker_mute / enabled）
$ADB logcat -c
$ADB shell am broadcast -n com.cubespeaker.router/.DebugReceiver \
  -a com.cubespeaker.router.GET_STATE
$ADB logcat -d -s CubeSpeaker:* | tail -3
# => debug state enabled=true allAudio=false hint=0 forceUseMedia=12 speakerMute=false

# 全音声モード（TVを含むすべてをCubeから。TVは無音）
$ADB shell am broadcast -n com.cubespeaker.router/.DebugReceiver \
  -a com.cubespeaker.router.SET_ALL_AUDIO --ei enabled 1
$ADB shell am broadcast -n com.cubespeaker.router/.DebugReceiver \
  -a com.cubespeaker.router.SET_ALL_AUDIO --ei enabled 0

# 有効化 / 無効化
$ADB shell am broadcast -n com.cubespeaker.router/.DebugReceiver \
  -a com.cubespeaker.router.SET_ENABLED --ei enabled 1
$ADB shell am broadcast -n com.cubespeaker.router/.DebugReceiver \
  -a com.cubespeaker.router.SET_ENABLED --ei enabled 0

# hint を手動で 0/1 に設定（サービスが 250 ms 以内に 0 へ戻す）
$ADB shell am broadcast -n com.cubespeaker.router/.DebugReceiver \
  -a com.cubespeaker.router.SET_HINT --ei value 1

# アンプmute解除を即時再送
$ADB shell am broadcast -n com.cubespeaker.router/.DebugReceiver \
  -a com.cubespeaker.router.UNMUTE

# テスト音（Cube スピーカー）
$ADB shell am broadcast -n com.cubespeaker.router/.DebugReceiver \
  -a com.cubespeaker.router.PLAY_TONE

# HAL パラメータ送信（診断用）
$ADB shell am broadcast -n com.cubespeaker.router/.DebugReceiver \
  -a com.cubespeaker.router.SET_PARAMS --es value 'speaker_mute=false'

# ルーティング確認
$ADB shell dumpsys media.audio_flinger | grep -E 'AudioOut_|Output device'

# サービス状態
$ADB shell dumpsys activity services com.cubespeaker.router

# ログ
$ADB logcat -s CubeSpeaker
```

検証用ヘルパー（`AudioProbe` / `Beep2`）のソースは `tools/` に置いてある（`tools/README.md` 参照）。アプリの `DebugReceiver` だけで hint・mute・状態確認は完結するため、通常はヘルパーは不要。

> 実機検証後、検証用の `com.probe.untrusted` と `/data/local/tmp/helper.dex` は削除済み。再検証する場合は `tools/README.md` の手順で再構築する。最終アプリ `com.cubespeaker.router` は残している。

---

## 12. ファイル一覧

| パス | 内容 |
|---|---|
| `app/` | 最終アプリのソース（`HintUtil`, `RouterService`, `SpeakerKeepAlive`, `MainActivity`, `TestTone`, `DebugReceiver`, `BootReceiver`） |
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
