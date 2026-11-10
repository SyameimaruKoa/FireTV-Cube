# 検証用ヘルパー

最終アプリの一部ではなく、解析・検証に使った小さなツール群。

| ファイル | 内容 |
|---|---|
| `AudioProbe.java` | `amazon.media.AmazonAudioManager` を反射で取得し、`getHdmiOutStateHint()` の読み取りと `setHdmiOutStateHint()` の設定を行う |
| `Beep2.java` | `AudioAttributes`（content / usage / flags）を指定して短いビープを再生する |
| `SilentSpeaker.java` | 本体スピーカーに無音の `AudioTrack` を維持し、通常メディアまで Cube へルーティングさせる検証用プローブ |
| `Probe.java` | AmazonAudioService の Binder を直接叩く解析用プローブ（`com.probe.untrusted` アプリの実体） |
| `probe-AndroidManifest.xml` | `Probe.java` を debuggable アプリとして動かすための manifest |

## 前提

- JDK 17（このリポジトリでは `.work/jdk`）
- Android SDK `android.jar`（API 28）
- Build Tools 28（`d8`, `aapt`, `apksigner`）

```bash
ROOT=/home/syameimarukoa/FireTV-Cube
JDK=$ROOT/.work/jdk
SDK=/tmp/opencode/sdk/android-9
BT=/tmp/opencode/bt28/android-9
export PATH="$JDK/bin:$PATH"
```

## 1. ヘルパー dex を作る

```bash
cd "$ROOT"
rm -rf tools/classes tools/dex
mkdir -p tools/classes tools/dex
"$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$SDK/android.jar" \
    -d tools/classes tools/AudioProbe.java tools/Beep2.java tools/SilentSpeaker.java
"$BT/d8" --lib "$SDK/android.jar" --output tools/dex tools/classes/*.class
adb push tools/dex/classes.dex /data/local/tmp/helper.dex
```

## 2. ビープでルーティングを確認

シェル（uid 2000）から実行できる。

```bash
# vizzini TTS と同じ属性: content=2, usage=1, flags=0x10000000
adb shell "CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin Beep2 2 1 268435456 4 700"

# 通常メディア: flags=0
adb shell "CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin Beep2 2 1 0 4 700"

# 出力先を確認
adb shell dumpsys media.audio_flinger | grep -E 'AudioOut_|Output device'
```

`hint=0` のとき、AUTO_OUT は `AudioOut_D`（`0x2` / Speaker）、通常メディアは `AudioOut_15`（`0x40000000` / HDMI primary）になる。

### 全音声モードの再現

```bash
# 本体スピーカーに無音トラックを 30 秒維持（usage=1 MEDIA, contentType=2 MUSIC）
adb shell "CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin SilentSpeaker 30"

# 別シェルで通常メディアを鳴らすと、output 13 / AudioOut_D に載る
adb shell "CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin Beep2 2 1 0 4 440"
```

詳細と実測ログは `docs/README.md` 8.8 / 9.2 を参照。

## 3. hint の読み書き（要 MODIFY_AUDIO_SETTINGS）

`AudioProbe` は `MODIFY_AUDIO_SETTINGS` を必要とする。シェルはこの権限を持たないため、`Probe.java` を debuggable アプリとしてインストールし、その UID で `run-as` 経由で実行する。

### Probe アプリをビルド

先に `./scripts/build_app.sh` を実行して `.work/build/debug.keystore` を作っておく（既存の debug keystore を使う場合はパスを変更する）。

```bash
cd "$ROOT"
rm -rf tools/probe-classes tools/probe-dex
mkdir -p tools/probe-classes tools/probe-dex
"$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$SDK/android.jar" \
    -d tools/probe-classes tools/Probe.java
"$BT/d8" --lib "$SDK/android.jar" --output tools/probe-dex tools/probe-classes/*.class
"$BT/aapt" package -f -M tools/probe-AndroidManifest.xml \
    -I "$SDK/android.jar" -F tools/probe-unsigned.apk
(cd tools/probe-dex && "$BT/aapt" add "$ROOT/tools/probe-unsigned.apk" classes.dex >/dev/null)
"$BT/zipalign" -f 4 tools/probe-unsigned.apk tools/probe-aligned.apk
"$BT/apksigner" sign --ks "$ROOT/.work/build/debug.keystore" \
    --ks-pass pass:android --key-pass pass:android \
    --out tools/probe.apk tools/probe-aligned.apk
adb install -r tools/probe.apk
```

### 読み取り

```bash
adb shell "run-as com.probe.untrusted sh -c \
  'CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin AudioProbe'" \
  | grep getHdmiOutStateHint
```

### 設定

```bash
# hint 0: HDMI not active -> AUTO_OUT を本体スピーカーへ
adb shell "run-as com.probe.untrusted sh -c \
  'CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin AudioProbe setHdmiOutStateHint=0'"

# hint 1: HDMI active -> AUTO_OUT を HDMI へ戻す
adb shell "run-as com.probe.untrusted sh -c \
  'CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin AudioProbe setHdmiOutStateHint=1'"
```

## 注意

- `Probe.java` / `probe-AndroidManifest.xml` は解析用の debuggable アプリ（`com.probe.untrusted`）を作る。最終アプリとは別物。
- 検証後は次のように削除してよい:

```bash
adb uninstall com.probe.untrusted
adb shell rm -f /data/local/tmp/helper.dex
```
