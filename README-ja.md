> **非公式・非提携**：本プロジェクトは個人による非公式の開発です。Rokid社およびその関連会社とは提携しておらず、承認・支援・スポンサー提供を受けていません。
> **Unofficial and unaffiliated**: This is an independent, unofficial project. It is not affiliated with, endorsed, supported, or sponsored by Rokid or its affiliates.

**公開時の補足:** この版の排他的なマイク設定は、アシスタントとの同時利用を妨げる可能性があります。修正過程を残すための過去版です。実機でMIC BUSYが解消した確認はありません。併用向けの設定変更は1.0.2以降です。

[日英README・スクリーンショット](README.md)。以下はこの版の配布時点の操作・実装説明です。

# Rokid Spectrum 1.0.1

Rokid Glasses上で動く、マイク入力のオーディオスペクトルアナライザです。
インストール後の動作にスマートフォン、Bluetooth、インターネットは不要です。
音声を保存・送信する機能はありません。解析画面を閉じるかHOLDにするとマイクを解放します。

## 1.0.1の修正

Rokid実機での「MIC BUSY」表示の報告を受け、マイク取得と復帰処理を修正しました。
旧版は、一度入力を受けた後にAndroid側で無音化されると、自動切替が進まない場合がありました。
競合元のアプリやサービスは、写真だけでは特定できていません。

- Android 11/API 30以上では `AudioRecord.Builder.setPrivacySensitive(true)` で同時収録を避ける要求を明示。
- AUTOの先頭を標準MICに変更。VOICE、対応時のRAW、CAM、DEFAULTも試します。
- 入力途中の無音化を検出した場合も、ストリームを解放して別候補へ切り替えます。
- 候補を1巡しても競合する場合は、再試行で開けた入力を維持し、競合解消後の復帰を待ちます。際限なく開き直しません。
- 端末全体のミュートはMIC MUTEDとして表示。マイク設定や音声モードをアプリが勝手に変更する処理はありません。
- エラー画面に入力方式・レート・PRIVATE/DEFAULTを追加。
- INPUT: CAMを追加。Android標準のCAMCORDER音声入力であり、カメラ映像は取得しません。

**元のアプリと同じ署名・パッケージ名です。そのまま上書きできます。更新後はINPUTをAUTOにしてください。**
これは対策版であり、この版でのRokid実機の復旧はまだ確認していません。

## インストール

`RokidSpectrum-1.0.1.apk` を、これまでRokidにAPKを入れていた方法でインストールしてください。
アプリ一覧では **Rokid Spectrum** と表示されます。初回はマイクを許可してください。

ADBを使う場合（インストール時だけPCが必要）:

```sh
adb install -r -g RokidSpectrum-1.0.1.apk
adb shell am start -n dev.xenoah.rokidspectrum/dev.xenoah.spectrum.MainActivity
```

複数端末が接続されている場合は `adb -s <Rokidのシリアル番号> ...` で対象を選んでください。
Android 8.0/API 26以上を対象とした、単一の署名済みAPKです。CPU依存のネイティブライブラリは使っていません。
Rokid用SDK、Google Play開発者サービス、追加ランタイムは不要です。

## 操作

| 操作 | 動作 |
| --- | --- |
| テンプルを1回タップ / Enter | HOLDと再開。HOLD中はマイクも停止 |
| 前後スワイプ / 左右キー | SPECTRUM → WATERFALL → WAVEFORM の切り替え |
| 戻る操作 / Back（対応ファームウェアではダブルタップ） | メニューを開く、またはメニューから戻る |
| メニュー中のスワイプ | 項目選択 |
| メニュー中のタップ | 選択項目の実行・変更 |
| 長押しタップ、Enter長押し | ピークと履歴を消去 |
| メニューのEXIT | 終了 |

通常のAndroidタッチ画面でも、タップ・スワイプ・ダブルタップが使えます。
画面上部のタブやメニュー項目は直接タップできます。右下のBACK MENUもタップ可能です。
カメラボタンがAndroidのCAMERAキーとしてアプリに届く場合もピークを消去します。
Rokid固有のカメラ用ブロードキャストは横取りしません。機種・ファームウェアによるキー割り当て差は実機未確認です。

## 3つの画面

- **SPECTRUM**: 対数周波数軸の48バンド。バーは各帯域内の最大FFT振幅、細い線はピーク保持値。
- **WATERFALL**: 横が周波数、縦が時間。上が最新。緑が明るいほど強い成分です。
- **WAVEFORM**: 12ms区間の入力波形。見やすさのため正方向のゼロクロスを探して表示します。

共通表示は最大成分の周波数、AC RMS dBFS、PCMピークdBFSです。
DOMINANTは最も強い周波数であり、楽器の基音や音程を保証する表示ではありません。
CLIPは入力サンプルがデジタルの上限近くに達したときに表示されます。

黒背景・緑の単色で描き、480×400の表示領域を実画面の中央に配置します。
480×640の画面では上下120pxが空きます。他の解像度では縦横比を維持して拡大・縮小します。

## 設定

| 項目 | 内容 |
| --- | --- |
| VIEW | 表示モード |
| SCALE | AUTO、上端0 / -20 / -40 dBFS。スペクトルの縦幅は80dB |
| INPUT | AUTO → RAW → VOICE → MIC → DEMO → CAM |
| RESTART MICROPHONE | マイクを開き直す |
| CLEAR PEAKS / HISTORY | 保持ピークとウォーターフォール履歴を消去 |
| HELP / INPUT DETAILS | 操作と実際の入力設定を表示 |

AUTOは表示範囲の自動調整です。入力音を増幅する機能ではなく、数値のdBFSは変わりません。
入力AUTOでは、MIC、VOICE_RECOGNITION、対応を報告する端末でのUNPROCESSED、CAMCORDER、DEFAULTの順に試します。
各入力で48,000 / 44,100 / 32,000 / 16,000Hzを順番に試します。
初期化失敗、継続するポリシー無音化、PCMが完全なゼロしか返らない入力では、1巡目は次候補へ進みます。
最初に音を受け取れていても、途中で競合が発生した場合に復帰処理へ進むよう修正しました。
全候補を試した後は再試行で開けた入力を維持して状態の回復を待ちます。タップで再探索もできます。
PRIVATEはAndroidの正式な入力共有ポリシーです。権限拒否や端末のマイクOFFを回避するものではありません。
このアプリの計測中、音声アシスタント等の同時収録が停止する場合があります。HOLDまたはアプリ終了で入力を解放します。
利用できる場合はAGC・ノイズ抑制・エコー抑制の無効化を要求しますが、端末内の全処理が解除される保証はありません。

**DEMOは合成信号です。実測ではありません。** このモードはマイクを使わず、表示の切り分け確認に使えます。
画面右上のDEMOと下部のDEMO / GENERATEDで常に区別できます。実測に戻すときはINPUTをAUTOにしてください。

## 測定上の意味

- 入力は内蔵マイク優先、モノラルPCM16。ほかのアプリの再生音を内部から取り込む方式ではありません。
- FFTは8,192点、周期Hann窓、2,048サンプルずつ更新（75%オーバーラップ）。
- 48kHz時は約5.86Hz/bin、約171msの解析窓、解析更新は約23.4回/秒。UI描画の目標は約30回/秒です。
- 周波数軸は20Hz〜20kHz。ただし入力レートが低い場合はナイキスト周波数までに自動制限します。
- ピーク周波数は近傍3点の対数スペクトルから補間します。FFTの分解能を超えて近接した音を分離できるという意味ではありません。
- 48バンドはFFTビンの最大値であり、1/3オクターブ帯域の積分音圧ではありません。
- FFTバーはHann窓の利得を補正した片側振幅です。ビンの中心から外れた純音には最大約1.42dBのスカロッピング損失があります。
- RMSはDCを除いた時間波形から計算します。ピークはDCも含む生のPCMサンプルの最大絶対値です。
- 正弦波がフルスケールに近い場合、ピークは約0dBFS、RMSは約−3.01dBFSになります。
- **dBFSはデジタル信号の大きさです。校正済みの騒音計が表示するdB SPLやdBAではありません。**
- マイクの周波数特性・内蔵フィルタ・ファームウェア処理は補正していません。

## 音が表示されないとき

1. PERMISSIONの場合はタップしてマイクを許可します。繰り返し拒否した場合はアプリ情報画面を開きます。
2. PCから許可する場合は `adb shell pm grant dev.xenoah.rokidspectrum android.permission.RECORD_AUDIO`。
3. MIC BUSYはAndroidがこのアプリの入力を無音化している状態です。1.0.1では自動切替を試すため、INPUTをAUTOにして数秒待ってください。
4. 続く場合は、通話・録画・音声アシスタントを閉じ、RESTART MICROPHONEを選びます。画面録画を行っている場合は、そのマイク収録も停止して比較してください。競合元を特定したという意味ではありません。
5. INPUTのCAMも試せます。端末によって前処理や周波数特性が異なるため、音響測定の校正を保証するモードではありません。
6. MIC MUTEDは端末側のミュートを検出した状態です。端末のマイク設定を確認してください。アプリは設定を変更しません。
7. NO SIGNALはデジタル入力が完全なゼロの状態です。マイクのプライバシー設定と入力方式を確認してください。
8. 直らない場合は、エラー画面下部の入力方式・レート・PRIVATE/DEFAULTも写した画像と、可能なら以下のログで原因を絞れます。

追加調査に使えるコマンド:

```sh
adb shell dumpsys package dev.xenoah.rokidspectrum
adb shell dumpsys audio
adb shell dumpsys media.audio_policy
adb logcat -d -s AndroidRuntime:E RokidSpectrum:W
```

## 検証範囲

このAPKはコンパイル、DEX生成、APK梱包、署名・配置検証まで完了しています。
数値・表示状態処理の28テスト、マイク制御の21テスト、14画面状態の共有描画コードの実行を確認しました。
マイク制御テストはAndroid APIのテスト用代替実装による障害注入です。
プレビューPNGはアプリと同じ描画コードをJava2Dで描いた合成入力の確認画像です。
ユーザー提供の写真で1.0.0のRokid実機での画面起動とMIC BUSY表示を確認しました。
**1.0.1のAndroidエミュレータ起動・Rokid実機でのマイク取得は未検証です。**
実機の周波数応答、表示遅延、消費電力についても実測値はありません。
詳細な出力は `verification/` に入っています。

## ソースからのビルド

ソースはJava、Android標準APIのみです。Gradle構成も付属します（AGP 8.9.2 / Gradle 8.11.1 / JDK 17 / compileSdk 35）。
Android Studioでフォルダを開くか、対応するGradleで `gradle :app:assembleDebug` を実行してください。
この環境で実際に配布APKを生成したのは、外部依存解決が不要な以下のSDK直接ビルドです。

```sh
export ANDROID_JAR=/path/to/android-sdk/platforms/android-35/android.jar
export ANDROID_BUILD_TOOLS=/path/to/android-sdk/build-tools/35.0.0
python3 tools/build_apk.py
python3 tools/test_core.py
python3 tools/test_audio_lifecycle.py
```

`ANDROID_JAR` は35以上を指定できます。配布APKの直接ビルドは、配置済みのプラットフォームJAR（リソースのメタデータはAPI 36）で行いました。
アプリのminSdkは26、targetSdkは32です。API 29の録音状態取得はバージョンを確認してから呼び出します。
直接ビルドにはJDK 17、Python 3、aapt2、D8、zipalign、apksigner、keytoolが必要です。
数値と描画のテストにはJDKのjava.desktopも必要です。
Gradle経由のビルドはこの環境では実行していません。

配布APKは開発用のRSA署名です。秘密鍵はソースZIPに含めていません。
同じアプリを上書き更新するには、同じ署名鍵を保持し、versionCodeを増やしてください。
直接ビルド時は `SPECTRUM_KEYSTORE` と `SPECTRUM_STOREPASS` で既存の鍵を指定できます。
指定せず鍵が存在しない場合は、ローカルに新しい開発鍵を作成します。その新しい鍵では配布済みAPKを上書きできません。

## 参照した公式仕様

- [Android AudioRecord](https://developer.android.com/reference/android/media/AudioRecord)
- [Androidの音声入力共有と優先順位](https://developer.android.com/media/platform/sharing-audio-input)
- [AudioRecord.Builder.setPrivacySensitive](https://developer.android.com/reference/android/media/AudioRecord.Builder#setPrivacySensitive(boolean))
- [Androidのマイク権限・UNPROCESSED / VOICE_RECOGNITION](https://developer.android.com/media/platform/mediarecorder)
- [Rokid Sprite Enterpriseのボタンとタッチパッド](https://x-docs.rokid.com/docs/en/%E4%BB%A3%E7%A0%81%E7%A4%BA%E4%BE%8B/50-system/01-%E6%8C%89%E9%94%AE%E4%B8%8E%E4%BE%A7%E8%BE%B9%E8%A7%A6%E6%8E%A7.html)
- [AGP 8.9の対応バージョン](https://developer.android.com/build/releases/agp-8-9-0-release-notes)

Rokidのリンク先はEnterprise向け仕様です。一般向けGlasses上でも全操作が同一であるとは断定していません。
