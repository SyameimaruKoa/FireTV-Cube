# FireTV-Cube

Fire TV Cube (`AFTR` / `raven`, Fire OS 7 / Android 9) の **Alexa TTS を TV（HDMI）ではなく Cube 本体スピーカーから出す**ための解析・実装リポジトリ。

## 成果物

| パス | 内容 |
|---|---|
| `app/` | 最終アプリ `com.cubespeaker.router` のソース |
| `docs/README.md` | 解析・実装・実機検証の詳細（まずこれを読む） |
| `scripts/build_app.sh` | APK のビルド・署名 |
| `dumps/` | APM / flinger / サービス起動の実機ダンプ |
| `artifacts/` | 実機スクリーンショット |
| `tools/` | 検証用ヘルパー（AudioProbe / Beep2 / Probe） |

## クイックスタート

```bash
# ビルド（JDK / Android SDK / Build Tools のパスは scripts/build_app.sh 内）
./scripts/build_app.sh

# インストールして起動
adb install -r CubeSpeakerRouter.apk
adb shell am start -n com.cubespeaker.router/.MainActivity
```

初回起動後はフォアグラウンドサービスが hint 0 を維持し、`BOOT_COMPLETED` で再起動後も自動起動する。

## 仕組み（要約）

- Alexa TTS は `FLAG_AUTO_OUT` (`0x10000000`) 付きの `AudioAttributes` で再生される。
- `AmazonAudioManager.setHdmiOutStateHint(0)` を維持すると、AUTO_OUT のトラックだけが本体スピーカー（output 13 / `AudioOut_D`）へ行く。
- 通常の動画・音楽（`flags=0`）は HDMI（output 21 / `AudioOut_15`）のまま。

詳細・実測マトリクス・トレードオフは [`docs/README.md`](docs/README.md) を参照。

## 注意

- 本リポジトリは root なし・システムアプリなしで動作する一般アプリの実装です。
- Alexa 経由の音楽も AUTO_OUT のため本体スピーカーから出ます（TV で聴きたいときはアプリを一時的に無効化）。
- `.work/` と `*.apk` は `.gitignore` 対象です。
