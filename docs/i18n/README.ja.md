<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Plus logo"/>

# AEmulator Plus

**懐かしの Android ファームウェア — HTC Sense、TouchWiz、MIUI、AOSP — を最新スマホで。root も PC も不要。**

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · **🇯🇵 日本語** · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Plus はファームウェアファイルから本物の Android 2.3〜7.x を直接起動します：リカバリー ZIP、Odin アーカイブ、Google ファクトリーイメージ。古い ARM コードは改良版 QEMU で変換し、カーネルの binder をエミュレートし、描画はスマホの GPU、音声は Android のオーディオを使います。すべて普通のアプリの中で動きます。

## ✨ 特長

- ほぼすべての形式をインポート：CWM/TWRP ZIP、Samsung Odin `.tar.md5`、Google ファクトリー `.tgz`、`system.img`、OTA `system.new.dat.br`
- メーカー独自 UI がそのまま動作：HTC Sense、Samsung TouchWiz、MIUI、AOSP
- GL ブリッジによるハードウェア描画、音声、タッチとマルチタッチ、最新 TLS プロキシ付きネットワーク
- APK・音楽・写真用の共有メモリーカードフォルダー
- 18 言語対応の Material 3 Expressive UI
- 無料・オープンソース（GPL-3.0）

## 🚀 クイックスタート

1. [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) から APK をダウンロードしてインストール。
2. AEmulator Plus を開き →**ファームウェアを追加**でファイルを選択。インポートには数分かかります。
3. **起動**をタップ。初回はアプリ最適化のため時間がかかります。
4. ⋮ メニューで音量・電源ボタン・ログ、⚙️ で設定と言語。

## 📋 動作要件

- 64 ビット ARM 端末（arm64-v8a）の Android 8.0+
- ファームウェア 1 つにつき約 1〜3 GB の空き
- 最新の Snapdragon / Dimensity / Tensor を推奨

## ⚙️ 仕組み

各ゲストプロセスは改良版ユーザーモード QEMU で動きます。binder デーモンがカーネルドライバーの代わりを務め、GL ブリッジが OpenGL ES 呼び出しを GPU に転送し、小さなゲストライブラリ（オーディオ HAL、audio policy ラッパー、LD_PRELOAD シム）がメーカーのコードをエミュレーターに合わせます。インポーターはファームウェアを読み、boot イメージの init スクリプトからサービスの起動計画を作ります。

## 🛠️ ソースからビルド

動作確認済みのアプリのリリースビルドは JDK 21 と Android SDK 36 を使用します。ネイティブツールチェーンや署名の詳細は[ビルド／ソース手順](../build-source.md)をご覧ください。**継承したエンジンのビルド済みバイナリについては、ソースコードとビルドの出自がまだ完全には検証されていません**。[監査ドキュメント](../license-audit.md)を参照してください。

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## 🙏 クレジット

AEmulator Plus は[オリジナル作者](https://t.me/istratiit_ech)の HTC Desire HD・HTC One M7 エミュレーターから生まれました。そのエンジンなしにこのプロジェクトはありません。

これは [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset) の改変フォークであり、そのリポジトリ自体も [uxazu/AEmulator](https://github.com/uxazu/AEmulator) の改変フォークです。

## 🔗 リンク

- 私のフォーク: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- アップストリーム: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- オリジナル: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- 作者: [somerandomusername2](https://github.com/somerandomusernam2)
- オリジナル作者: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 ライセンス

GPL-3.0。Android、商標、ファームウェアは各所有者に帰属します。

[日付入りの改変通知](../../NOTICE.md)と[未完了のソース／ライセンス監査](../license-audit.md)をご覧ください。GPL の表記は、同梱の各ビルド済みバイナリに対応する完全なソースがあることを保証するものではありません。
