> **非公式・非提携**：本プロジェクトは個人による非公式の開発です。Rokid社およびその関連会社とは提携しておらず、承認・支援・スポンサー提供を受けていません。
> **Unofficial and unaffiliated**: This is an independent, unofficial project. It is not affiliated with, endorsed, supported, or sponsored by Rokid or its affiliates.

**公開時の補足:** 1.0.3の実機でのマイク取得とアシスタント併用は未検証です。全ての非排他入力を端末が拒否する場合、APKだけで同時取得を強制することはできません。初回の確認はグラスの録画・音声付き画面共有を止め、INPUT: AUTOで行ってください。

[日英README・スクリーンショット](README.md)。以下はこの版の配布時点の操作・実装説明です。

# Rokid Spectrum 1.0.3

Rokid Glasses上で動く、マイク入力のオーディオスペクトルアナライザです。
インストール後の動作にスマートフォン、Bluetooth、インターネットは不要です。
音声を保存・送信する機能はありません。解析画面を閉じるかHOLDにするとマイクを解放します。

## 1.0.3：WAITINGから進まない問題への対応

1.0.2の実機写真・動画では、入力が `MIC / 48000 Hz / NONPRIVATE` のままWAITINGになり、解析が始まっていませんでした。
コードを確認したところ、開けた入力がAndroidに無音化されると、その入力のまま期限なく待機する処理になっていました。
1.0.3では、共有可能な設定を維持して複数の入力経路・レートを有限回試します。

- 最初の入力が無音化された場合、開始前は約0.8秒で次候補へ進みます。測定中の一時的な割り込みは最大約4秒、その入力での回復を待ってから次候補へ進みます。
- 48 kHzで初期化に成功しても音が渡らない場合、MICとVOICEでは16 kHzも明示的に試します。
- 内蔵マイクの最初のデバイスを固定選択する処理を外し、Androidに入力経路を選ばせます。
- Android 11/API 30以上では `setPrivacySensitive(false)` を設定し、非排他になっていることを確認してから収録を開始します。非排他要求が反映されない入力は使いません。
- API 30以上でのみ、COMM/CAMの入力経路も非排他で試します。古いAndroidではこの2経路を選びません。カメラ映像の取得・通話モードの変更は行いません。
- 全候補で取得できなければマイクを解放し、**MIC BLOCKED / NO SIGNAL / MIC ERROR** と検査件数を表示します。永久待機・無制限の開き直し・合成波形への自動切替はしません。
- **WAITINGやMIC BLOCKEDでのタップはRETRY**です。旧版のように、再試行したつもりでHOLDになることを防ぎます。
- アシスタント等が前面に出て解析画面のフォーカスを失ったら、マイクを解放。解析画面に戻ると自動再開します。Activityが一時停止しない重ね表示にも対応します。
- ユーザーが選んだ **HOLD** は維持します。アシスタントを閉じても勝手に解除しません。
- AGC・ノイズ抑制・エコー抑制は変更せず、共有する音声処理をOS側に任せます。
- アシスタントキー、ヘッドセットキー、再生/一時停止キーをこのアプリで消費しません。通常のEnter・タップによる解析操作は維持します。
- 旧版のINPUT: CAM設定はAUTOへ移行します。診断表示のNONPRIVATEは非排他の入力設定を示します。

**同じ署名・パッケージ名なので上書きできます。まずグラス側の録画と音声付き画面共有を停止し、INPUT: AUTOで試してください。**

添付の約34.6秒の動画には16 kHz・モノラルの音声トラックがあり、音声サンプルも確認できました。
録画機能もマイクを使うため競合の候補になりますが、それが今回の唯一の原因だとは断定できません。
写真や `isClientSilenced()` だけでは、アシスタント・録画など競合元のアプリを特定できません。

Android標準では、アシスタントがバックグラウンドにいて通常アプリの入力が非排他の場合、同時に音を受け取れる条件があります。
アシスタントの会話画面が前面にある間は、解析側に音が渡らないことがあります。画面のフォーカスを失えば収録を停止し、戻った時に新しいFFT窓から再開します。
フォーカスを失わない割り込みでも、一時的な無音化からは同じ入力で回復します。長く続けば別候補を検査します。
全候補の検査が終了した後は、録画や会話が終わったところでタップして再試行してください。
この共有条件はAndroidの特権アシスタントに対する仕様です。Rokid内蔵アシスタントの実装・権限・ファームウェアによっては動作が異なります。
**端末がすべての非排他入力を拒否する場合、このAPKだけで同時取得を強制することはできません。1.0.3のRokid実機でのマイク取得・併用は未確認です。**
根拠：[Android公式の音声入力共有](https://developer.android.com/media/platform/sharing-audio-input)。

## インストール

`RokidSpectrum-1.0.3.apk` を、これまでRokidにAPKを入れていた方法でインストールしてください。
アプリ一覧では **Rokid Spectrum** と表示されます。初回はマイクを許可してください。

ADBを使う場合（インストール時だけPCが必要）:

```sh
adb install -r -g RokidSpectrum-1.0.3.apk
adb shell am start -n dev.xenoah.rokidspectrum/dev.xenoah.spectrum.MainActivity
```

複数端末が接続されている場合は `adb -s <Rokidのシリアル番号> ...` で対象を選んでください。
Android 8.0/API 26以上を対象とした、単一の署名済みAPKです。CPU依存のネイティブライブラリは使っていません。
Rokid用SDK、Google Play開発者サービス、追加ランタイムは不要です。

## 操作

| 操作 | 動作 |
| --- | --- |
| テンプルを1回タップ / Enter | LIVE/DEMO中はHOLDと再開。WAITINGや入力エラー中はRETRY |
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
入力AUTOは次の順に検査します。端末/APIが対応しない項目は省きます。

| 順番 | 入力経路 | 優先レート | 条件 |
| --- | --- | --- | --- |
| 1 | MIC | 48 kHz | 全対応端末 |
| 2 | VOICE_RECOGNITION | 48 kHz | 全対応端末 |
| 3 | UNPROCESSED（RAW） | 48 kHz | RAW対応を報告する端末 |
| 4 | VOICE_PERFORMANCE（PERF） | 48 kHz | API 29以上 |
| 5 | MIC | 16 kHz | 初期化成功後の無音化も切り分け |
| 6 | VOICE_RECOGNITION | 16 kHz | 同上 |
| 7 | VOICE_COMMUNICATION（COMM） | 16 kHz | API 30以上、非排他を確認できる場合 |
| 8 | CAMCORDER（CAM） | 48 kHz | API 30以上、非排他を確認できる場合 |
| 9 | DEFAULT | 48 kHz | 全対応端末 |

48 kHz自体を初期化できない場合は44.1 / 32 / 16 kHzへフォールバックします。
手動のRAW / VOICE / MICでも48 kHzと16 kHzの候補を検査します。
最初からポリシーで無音化される場合、全9候補の検査は通常約8〜10秒です。ゼロ信号や入力停止の場合はそれより長くなることがあります。
検査後のTESTEDは検査件数、BLOCKEDはOSによる無音化、ZEROはゼロ信号、ERRは初期化・読み取り失敗の件数です。
BACKメニューのRESTART MICROPHONE、またはWAITING/エラー画面でのタップで再検査します。
NONPRIVATEは共有を許可する設定であり、同時収録の成功を示す表示ではありません。権限拒否や端末のマイクOFFを回避しません。
前処理はOS側に任せます。アシスタント起動によって経路や前処理が変わると、測定値が変わる可能性があります。

**DEMOは合成信号です。実測ではありません。** このモードはマイクを使わず、表示の切り分け確認に使えます。
画面右上のDEMOと下部のDEMO / GENERATEDで常に区別できます。実測に戻すときはINPUTをAUTOにしてください。

## 測定上の意味

- 入力はAndroidが選んだマイク経路のモノラルPCM16。ほかのアプリの再生音を内部から取り込む方式ではありません。
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
3. **グラスで動画撮影・音声付き画面収録を行わずに**解析を開始します。確認用の録画が入力を占有している可能性もあるためです。まず肉眼でLIVEになるか確認してください。
4. INPUTをAUTOにするとSCANNINGで複数候補を検査します。MIC BLOCKEDになったら、OSによる入力の無音化が候補検査でも解消しなかった状態です。
5. アシスタントとの会話を終えた状態でタップしてRETRYします。全候補が引き続き拒否される場合、通常のAPKで共有を強制することはできません。
6. MIC MUTEDは端末側のミュートを検出した状態です。端末のマイク設定を確認してください。アプリは設定を変更しません。
7. NO SIGNALはゼロ信号、MIC ERRORは入力を開けない・読み取り失敗の状態です。表示された検査件数で区別できます。
8. 直らない場合は、1.0.3の画面下部のTESTED / BLOCKED / ZERO / ERRと、録画を止めても同じかを教えてください。必要なら以下のログで競合元を調べます。

追加調査に使えるコマンド:

```sh
adb shell dumpsys package dev.xenoah.rokidspectrum
adb shell dumpsys audio
adb shell dumpsys media.audio_policy
adb logcat -d -s AndroidRuntime:E RokidSpectrum:W
```

## 検証範囲

このAPKはコンパイル、DEX生成、APK梱包、署名・配置検証まで完了しています。
数値・表示状態処理の29テスト、マイク制御の32テスト、Activity制御の14テスト、17画面状態の共有描画コードの実行を確認しました。
マイク制御とActivity制御のテストは、実際のアプリコードをAndroid APIのテスト用代替実装で実行した障害注入・イベント順序の検証です。
最初のMICだけが拒否される場合、48 kHzだけが無音化される場合、全入力が拒否される場合、非排他要求を無視する端末を再現しました。
フォーカス移動、権限ダイアログ、HOLD維持、待機画面のタップによる再試行、同時に2つの録音を開かないことも確認しています。
プレビューPNGはアプリと同じ描画コードをJava2Dで描いた合成入力の確認画像です。
ユーザー提供の写真・動画で、1.0.0のMIC BUSY、1.0.2のMIC 48 kHzでのWAITING、メニュー操作が応答することを確認しました。
**1.0.3のAndroidエミュレータ起動・Rokid実機でのマイク取得とアシスタント併用は未検証です。**
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
