> **非公式・非提携**：本プロジェクトは個人による非公式の開発です。Rokid社およびその関連会社とは提携しておらず、承認・支援・スポンサー提供を受けていません。
> **Unofficial and unaffiliated**: This is an independent, unofficial project. It is not affiliated with, endorsed, supported, or sponsored by Rokid or its affiliates.

**公開時の補足:** 実機の写真・動画ではMIC / 48000 Hz / NONPRIVATEのままWAITINGが継続し、解析が始まりませんでした。この版には待機時間の上限がなく、入力が戻らない場合に進めない問題があります。実音解析とアシスタント併用の成功は確認できていません。

[日英README・スクリーンショット](README.md)。以下はこの版の配布時点の操作・実装説明です。

# Rokid Spectrum 1.0.2

Rokid Glasses上で動く、マイク入力のオーディオスペクトルアナライザです。
インストール後の動作にスマートフォン、Bluetooth、インターネットは不要です。
音声を保存・送信する機能はありません。解析画面を閉じるかHOLDにするとマイクを解放します。

## 1.0.2：アシスタントとの併用

1.0.1の排他的なマイク取得は、アシスタントの同時利用を妨げる設定でした。
1.0.2では共有を許可する入力へ変更し、アシスタントに入力が必要な間は待機して、終了後に解析へ戻ります。

- Android 11/API 30以上では `AudioRecord.Builder.setPrivacySensitive(false)` を明示。古いAndroidでも排他扱いのCAMCORDERやVOICE_COMMUNICATIONを使いません。
- アシスタント等が前面に出て解析画面のフォーカスを失ったら、マイクを解放。解析画面に戻ると自動再開します。Activityが一時停止しない重ね表示にも対応します。
- Androidが解析アプリの入力を無音化した場合は **WAITING** と表示し、その入力で復帰を待ちます。別の入力を繰り返し開いて優先権を取りに行きません。
- ユーザーが選んだ **HOLD** は維持します。アシスタントを閉じても勝手に解除しません。
- AGC・ノイズ抑制・エコー抑制の変更をやめ、共有する音声処理をOS側に任せます。
- アシスタントキー、ヘッドセットキー、再生/一時停止キーをこのアプリで消費しません。通常のEnter・タップによる解析操作は維持します。
- 旧版のINPUT: CAM設定はAUTOへ移行します。診断表示のNONPRIVATEは非排他の入力設定を示します。

**元のアプリと同じ署名・パッケージ名です。そのまま上書きできます。INPUT: AUTOで、通常どおりアシスタントを呼び出してください。**

Android標準では、アシスタントがバックグラウンドにいて通常アプリの入力が非排他の場合、同時に音を受け取れる条件があります。
アシスタントの会話画面が前面にある間は、解析側に音が渡らないことがあります。その間は解析を待機し、入力が戻ったら新しいFFT窓から再開します。
この共有条件はAndroidの特権アシスタントに対する仕様です。Rokid内蔵アシスタントの実装・権限・ファームウェアによっては動作が異なります。
**会話中も途切れず同時解析できる保証はありません。1.0.2のRokid実機での併用は未確認です。**
根拠：[Android公式の音声入力共有](https://developer.android.com/media/platform/sharing-audio-input)。

## インストール

`RokidSpectrum-1.0.2.apk` を、これまでRokidにAPKを入れていた方法でインストールしてください。
アプリ一覧では **Rokid Spectrum** と表示されます。初回はマイクを許可してください。

ADBを使う場合（インストール時だけPCが必要）:

```sh
adb install -r -g RokidSpectrum-1.0.2.apk
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
| INPUT | AUTO → RAW → VOICE → MIC → DEMO |
| RESTART MICROPHONE | マイクを開き直す |
| CLEAR PEAKS / HISTORY | 保持ピークとウォーターフォール履歴を消去 |
| HELP / INPUT DETAILS | 操作と実際の入力設定を表示 |

AUTOは表示範囲の自動調整です。入力音を増幅する機能ではなく、数値のdBFSは変わりません。
入力AUTOでは、MIC、VOICE_RECOGNITION、対応を報告する端末でのUNPROCESSED、DEFAULTの順に試します。
各入力で48,000 / 44,100 / 32,000 / 16,000Hzを順番に試します。
初期化失敗や、Androidから無音化通知がなくPCMが完全なゼロしか返らない入力では、1巡目は次候補へ進みます。
候補を1巡しても音が得られない場合は、再試行で開けた入力を維持して状態の回復を待ちます。
Androidが入力を無音化したと通知した場合は、その場でWAITINGになり、別候補へ切り替えません。
マイクを開き直す場合はBACKメニューのRESTART MICROPHONEを使えます。WAITING画面のタップはHOLDです。
NONPRIVATEは共有を許可する設定であり、同時収録の成功を示す表示ではありません。権限拒否や端末のマイクOFFを回避しません。
前処理はOS側に任せます。アシスタント起動によって経路や前処理が変わると、測定値が変わる可能性があります。

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
3. WAITINGはアシスタント等の別ウィンドウが前面にあるか、Androidがこのアプリの入力を無音化している状態です。会話が終わり解析画面へ戻ると、自動再開します。この版は、アシスタントの常時待ち受けを有効にしたまま使う設計です。
4. WAITINGが続く場合は、まず解析画面が前面にありHOLDになっていないか確認します。INPUT: AUTOまたはRESTART MICROPHONEを試せます。音声入力の共有が許可されるかは端末側の制御によります。
5. MIC MUTEDは端末側のミュートを検出した状態です。端末のマイク設定を確認してください。アプリは設定を変更しません。
6. NO SIGNALはデジタル入力が完全なゼロの状態です。マイクのプライバシー設定を確認し、INPUT: MICまたはVOICEも試せます。
7. 直らない場合は、HELPのバージョンと入力方式・レート・NONPRIVATEも写した画像、アシスタントを呼び出せるか、会話後に解析へ戻るかを教えてください。可能なら以下のログでも原因を絞れます。

追加調査に使えるコマンド:

```sh
adb shell dumpsys package dev.xenoah.rokidspectrum
adb shell dumpsys audio
adb shell dumpsys media.audio_policy
adb logcat -d -s AndroidRuntime:E RokidSpectrum:W
```

## 検証範囲

このAPKはコンパイル、DEX生成、APK梱包、署名・配置検証まで完了しています。
数値・表示状態処理の29テスト、マイク制御の22テスト、Activity制御の12テスト、16画面状態の共有描画コードの実行を確認しました。
マイク制御とActivity制御のテストは、実際のアプリコードをAndroid APIのテスト用代替実装で実行した障害注入・イベント順序の検証です。
アシスタントによる入力優先、会話画面のフォーカス移動、権限ダイアログ、HOLD維持、旧CAM設定の移行、同時に2つの録音を開かないことを確認しています。
プレビューPNGはアプリと同じ描画コードをJava2Dで描いた合成入力の確認画像です。
ユーザー提供の写真で1.0.0のRokid実機での画面起動とMIC BUSY表示を確認しました。
**1.0.2のAndroidエミュレータ起動・Rokid実機でのマイク取得とアシスタント併用は未検証です。**
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
python3 tools/test_activity_lifecycle.py
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
