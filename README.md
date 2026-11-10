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

初回起動後はフォアグラウンドサービスが hint 0 を維持し、`BOOT_COMPLETED` で再起動後も自動起動する。「テレビを含むすべての音声をCubeから出す」をオンにすると全音声モード（TV は無音）になる。

## 仕組み（要約）

- Alexa TTS は `FLAG_AUTO_OUT` (`0x10000000`) 付きの `AudioAttributes` で再生される。
- `AmazonAudioManager.setHdmiOutStateHint(0)` を維持すると、AUTO_OUT のトラックだけが本体スピーカー（output 13 / `AudioOut_D`）へ行く。
- 通常の動画・音楽（`flags=0`）は HDMI（`AudioOut_15`）のまま。
- 実 Alexa 応答が Cube 本体スピーカーから聞こえることを実機で確認済み（再起動後も自動）。Alexa 応答中だけ TV 音声が約 1〜3 秒止まる。
- **HDMI を含む全音声を Cube から出すモード（任意・v1.2）**: 本体スピーカーに無音の `AudioTrack` を 1 本維持すると、Fire OS が通常メディアも Cube へルーティングし、TV は映像のみ（無音）になる。UI のチェックボックスでオン/オフできる。ユーザーが実音で確認済み（詳細は `docs/README.md` 8.8 / 9.2）。

詳細・実測マトリクス・トレードオフは [`docs/README.md`](docs/README.md) を参照。

## 注意

- 本リポジトリは root なし・システムアプリなしで動作する一般アプリの実装です。
- Alexa の音量は Cube 本体のボリュームボタンで調整できます（TV の音量とは独立）。
- Alexa 経由の音楽も AUTO_OUT のため本体スピーカーから出ます（TV で聴きたいときはアプリを一時的に無効化）。
- 全音声モード中は TV が無音になります（映像はそのまま）。TV と Cube の同時出力はできません。
- `.work/` と `*.apk` は `.gitignore` 対象です。
