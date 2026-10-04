<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Plus logo"/>

# AEmulator Plus

**在现代手机上运行经典 Android 固件——HTC Sense、TouchWiz、MIUI、AOSP。无需 root，无需电脑。**

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · **🇨🇳 简体中文** · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Plus 直接从固件文件启动真实的 Android 2.3–7.x 系统：Recovery 卡刷包、Odin 包或 Google 出厂镜像。旧的 ARM 代码由改进的 QEMU 转译，内核 binder 被模拟，图形使用手机 GPU 绘制，声音经由 Android 音频系统播放——一切都在一个普通应用里完成。

## ✨ 功能

- 导入几乎所有格式：CWM/TWRP 卡刷 ZIP、三星 Odin `.tar.md5`、Google 出厂 `.tgz`、`system.img`、OTA `system.new.dat.br`
- 厂商系统原样运行：HTC Sense、三星 TouchWiz、MIUI、AOSP
- 经 GL 桥的硬件图形、声音、触控与多点触控，带现代 TLS 代理的网络
- 共享存储卡文件夹，方便放 APK、音乐和照片
- Material 3 Expressive 界面，支持 18 种语言
- 免费开源（GPL-3.0）

## 🚀 快速开始

1. 从 [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) 下载并安装 APK。
2. 打开 AEmulator Plus →**添加固件**并选择文件。导入需要几分钟。
3. 点击**启动**。首次启动较慢：系统正在优化应用。
4. ⋮ 菜单可调音量、电源键和日志；⚙️ 打开设置与语言。

## 📋 要求

- 64 位 ARM 手机（arm64-v8a）上的 Android 8.0+
- 每个固件约需 1–3 GB 空间
- 推荐较新的骁龙 / 天玑 / Tensor 芯片

## ⚙️ 工作原理

每个客户机进程运行在改进的用户态 QEMU 中。binder 守护进程取代内核驱动，GL 桥把 OpenGL ES 调用转发给 GPU，小型客户机库（音频 HAL、audio policy 包装、LD_PRELOAD 垫片）让厂商代码适配模拟器。导入器读取固件，在 boot 镜像中找到 init 脚本，并生成系统服务的启动计划。

## 🛠️ 从源码构建

经测试的应用发布版本使用 JDK 21 和 Android SDK 36。请参阅[构建/源码说明](../build-source.md)，其中包含原生工具链和签名的详细信息。**继承而来的引擎预编译二进制文件尚未验证其完整的源码与构建来源**；参见[审计文档](../license-audit.md)。

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## 🙏 致谢

AEmulator Plus 源自[原作者](https://t.me/istratiit_ech)的 HTC Desire HD 与 HTC One M7 模拟器——没有他的引擎就没有本项目。

这是 [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset) 的修改版分支，而后者又是 [uxazu/AEmulator](https://github.com/uxazu/AEmulator) 的修改版分支。

## 🔗 链接

- 我的分支: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- 上游: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- 原始项目: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- 作者: [somerandomusername2](https://github.com/somerandomusernam2)
- 原作者: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 许可证

GPL-3.0。Android、商标及固件归其所有者所有。

请参阅[带日期的修改声明](../../NOTICE.md)和[尚未完成的源码/许可审计](../license-audit.md)。GPL 标签并不保证每个随附的预编译二进制文件都有完整且匹配的源码。
