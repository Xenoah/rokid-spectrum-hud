> **非公式・非提携**：本プロジェクトは個人による非公式の開発です。Rokid社およびその関連会社とは提携しておらず、承認・支援・スポンサー提供を受けていません。
> **Unofficial and unaffiliated**: This is an independent, unofficial project. It is not affiliated with, endorsed, supported, or sponsored by Rokid or its affiliates.

# Rokid Spectrum HUD — v1.0.0

**プレリリース / Pre-release** · Android 8.0+ (API 26+) · Java · MIT

Rokid Glasses向けに開発している、マイク入力のオーディオスペクトルアナライザです。黒背景に緑単色のHUDを表示します。
An experimental microphone audio spectrum analyzer for Rokid Glasses, with a green-on-black HUD.

[v1.0.0のAPK・ソース / APK and source](https://github.com/Xenoah/rokid-spectrum-hud/releases/tag/v1.0.0) · [全プレリリース / All prereleases](https://github.com/Xenoah/rokid-spectrum-hud/releases) · [日本語の詳細](README-ja.md)

## 日本語

### この版の変更 — 初版：3つの解析表示

- 48バンドのスペクトル、ウォーターフォール、12 msの波形表示を実装。
- 8,192点FFT・Hann窓、最大成分周波数、AC RMS／ピークdBFS、クリップ検出、ピーク保持。
- 緑単色の480 × 400 HUD、タップ／スワイプ／キー操作、HOLD、マイクを使わないDEMO。
- AUTOは対応時のRAW、VOICE、MIC、DEFAULTを試し、48 / 44.1 / 32 / 16 kHzに対応。

**確認状況・制限:** 実機ではUIの起動とMIC BUSY表示が報告されています。実音の解析開始は確認できていません。添付の実機画像はこの不具合の記録です。アシスタントとの併用を保証する版ではありません。

### 機能と使い方

- SPECTRUM、WATERFALL、WAVEFORMの3画面。最大成分周波数、RMS／ピークdBFS、ピーク保持を表示。
- マイク入力を端末内で解析します。音声の保存・送信機能はなく、INTERNET／CAMERA権限も使いません。
- DEMOは合成信号です。**dBFSは校正済みのdB SPL／dBAではありません。**
- HUDは480 × 400を中央配置。Rokid SDKやGoogle Playサービスへの依存はありません。機種・ファームウェアの差は未検証です。

下のAssetsから`RokidSpectrum-1.0.0.apk`を入手し、APKを導入できる端末にインストールしてください。初回はマイク権限を許可します。

```sh
adb install -r -g RokidSpectrum-1.0.0.apk
adb shell am start -n dev.xenoah.rokidspectrum/dev.xenoah.spectrum.MainActivity
```

同じ署名で新しいversionCodeへ更新できます。公開APKは元の開発用署名を保持しています。ローカルで新たに作った鍵では配布APKを上書きできません。

| 操作 | 動作 |
| --- | --- |
| タップ／Enter | HOLD・再開（HOLD中はマイクを解放） |
| スワイプ／左右キー | 3画面の切り替え |
| Back | メニューを開く／戻る |
| メニューでスワイプ・タップ | 項目選択・実行 |
| 長押し | ピーク・履歴を消去 |
| INPUT: DEMO | マイクなしで描画確認 |

### 検証とビルド

保存済みの検証結果は数値・状態処理27件、AudioEngine 10件、Activity 0件、描画12状態／文字境界198件です。
Android APIの代替実装とJava2Dによる検証であり、実機での測定保証ではありません。APKの構造・署名・バージョンも確認しています。詳細は[verification/](verification/)。

JDK 17、Python 3、Android SDKを用意し、以下のSDK直接ビルドを利用できます。Gradle構成（AGP 8.9.2、Gradle 8.11.1、compileSdk 35）も付属しますが、配布APKは直接ビルドで生成しました。

```sh
export ANDROID_JAR=/path/to/android-sdk/platforms/android-35/android.jar
export ANDROID_BUILD_TOOLS=/path/to/android-sdk/build-tools/35.0.0
python3 tools/build_apk.py
python3 tools/test_core.py
python3 tools/test_audio_lifecycle.py
```

## English

### Changes in this version — Initial spectrum HUD

- Adds a 48-band spectrum, waterfall and 12 ms waveform view.
- Uses an 8,192-point Hann-windowed FFT with dominant frequency, AC RMS/peak dBFS, clipping and peak hold.
- Provides a green 480 × 400 HUD, tap/swipe/key controls, HOLD and a microphone-free DEMO mode.
- AUTO tries supported RAW, VOICE, MIC and DEFAULT inputs with 48 / 44.1 / 32 / 16 kHz fallback.

**Status and limitations:** Device feedback confirms that the UI opens but reports MIC BUSY. Successful live microphone analysis has not been confirmed. The device screenshot documents this issue. Assistant coexistence is not guaranteed.

### Features and usage

- SPECTRUM, WATERFALL and WAVEFORM views, with dominant frequency, RMS/peak dBFS and peak hold.
- Audio is processed locally. The app does not save or transmit audio and requests neither INTERNET nor CAMERA permission.
- DEMO uses generated signals. **dBFS is not calibrated dB SPL or dBA.**
- The 480 × 400 HUD is centered on the display. No Rokid SDK or Google Play services are required. Device and firmware variations remain unverified.

Download `RokidSpectrum-1.0.0.apk` from this version's release Assets, install it on a device that accepts sideloaded APKs and grant microphone permission. The ADB commands above install and launch it. Updates to a higher versionCode use the same original development signing certificate. A locally generated key cannot update the distributed APK.

| Control | Action |
| --- | --- |
| Tap / Enter | HOLD/resume; HOLD releases the microphone |
| Swipe / Left / Right | Switch among the three views |
| Back | Open / close the menu |
| Swipe and tap in the menu | Select and apply an item |
| Long press | Clear peaks and history |
| INPUT: DEMO | Check the display without a microphone |

### Verification and building

Archived results cover 27 numerical/state tests, 10 AudioEngine tests, 0 Activity tests and 12 render states with 198 text-bound checks. These use Android framework doubles and Java2D, not physical-device measurement. APK structure, signing and version metadata have also been checked. See [verification/](verification/).

The commands above perform an SDK-direct build with JDK 17, Python 3 and Android SDK tools. The included Gradle project targets AGP 8.9.2 / Gradle 8.11.1 / compileSdk 35; that build route was not used for the distributed APK. Minimum SDK is 26 and target SDK is 32. Signing keys are excluded. `SPECTRUM_KEYSTORE` and `SPECTRUM_STOREPASS` can select a local key; the build creates a new development key if none exists.

### 画面 / Screenshots

以下はv1.0.0の共有Java描画コードによる**合成入力のDEMOプレビュー**です。実機やエミュレータのスクリーンショット、実音測定の証拠ではありません。
These are **synthetic DEMO previews** rendered with this version's shared Java renderer, not device/emulator screenshots or evidence of live capture.

![Spectrum, waterfall and waveform — synthetic DEMO](verification/renders/RokidSpectrum-preview.png)

入力状態の描画例 / Input-state rendering preview:

![Input-state preview, v1.0.0](verification/renders/mic-busy.png)

**実機の不具合記録 / Actual device issue report — v1.0.0, MIC BUSY.**
この写真はv1.0.0の報告です。解析開始の成功例ではありません。
This photo documents the issue in v1.0.0; it does not demonstrate successful microphone analysis in v1.0.0.

<img src="docs/screenshots/v1.0.0-mic-busy.jpg" alt="Actual Rokid device report: v1.0.0 MIC BUSY" width="440">

[実機写真一覧 / Device screenshots](docs/screenshots/README.md)


## 公開履歴 / Release history

アプリのソースと署名済みAPKは各版の保存データに基づきます。公開にあたり日英ドキュメント、写真、公開用ワークフローを追加しています。全版プレリリースです。
Application source and signed APKs are preserved from each archived version. Bilingual documentation, photos and a publication workflow are added for the repository. Every version is a prerelease.

| Version | 主な変更 / Main change | Status |
| --- | --- | --- |
| [v1.0.0](https://github.com/Xenoah/rokid-spectrum-hud/releases/tag/v1.0.0) | 初版：3つの解析表示 / Initial spectrum HUD | Pre-release |

## 公開ファイル / Release assets

各プレリリースには署名済みAPK、そのコミットのソースZIP、画像、SHA256SUMS.txtを添付します。ソースZIPは公開コミットから生成し、APKを含みません。元のアプリコードを変更せず、公開用の説明を追加しています。
Each prerelease includes the original signed APK, a source ZIP from its published commit, images and SHA256SUMS.txt. Source ZIPs exclude the APK and include the publication documentation alongside the unchanged application code.

`.release/`はその版の公開用APKとメタデータです。mainへの各版のpush後、[GitHub Actions](.github/workflows/prerelease.yml)がチェックサムを確認し、画像とソースを添付してプレリリースを公開します。署名秘密鍵はリポジトリ・配布物に含めません。
`.release/` contains that version's APK and metadata. After each version is pushed to main, the workflow verifies checksums and publishes its prerelease. No signing private key is included in the repository or releases.

## License / 参考仕様

[MIT License](LICENSE). Rokid and product names belong to their respective owners; use of those names does not imply affiliation.

- [Android audio input sharing](https://developer.android.com/media/platform/sharing-audio-input)
- [Android AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)
- [AudioRecord.Builder.setPrivacySensitive](https://developer.android.com/reference/android/media/AudioRecord.Builder#setPrivacySensitive(boolean))
