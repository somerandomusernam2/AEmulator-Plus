<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Plus logo"/>

# AEmulator Plus

**클래식 Android 펌웨어 — HTC Sense, TouchWiz, MIUI, AOSP — 를 최신 휴대폰에서. 루팅도 PC도 필요 없습니다.**

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · **🇰🇷 한국어**

</div>

---

AEmulator Plus는 펌웨어 파일에서 진짜 Android 2.3–7.x 시스템을 바로 부팅합니다: 리커버리 ZIP, Odin 아카이브, Google 팩토리 이미지. 오래된 ARM 코드는 수정된 QEMU가 변환하고, 커널 binder를 에뮬레이션하며, 그래픽은 휴대폰 GPU로, 소리는 Android 오디오로 처리합니다. 모두 평범한 앱 안에서 동작합니다.

## ✨ 기능

- 거의 모든 형식 가져오기: CWM/TWRP ZIP, 삼성 Odin `.tar.md5`, Google 팩토리 `.tgz`, `system.img`, OTA `system.new.dat.br`
- 제조사 UI가 그대로 동작: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- GL 브리지 하드웨어 그래픽, 소리, 터치와 멀티터치, 최신 TLS 프록시 네트워크
- APK·음악·사진을 위한 공유 메모리 카드 폴더
- 18개 언어의 Material 3 Expressive 인터페이스
- 무료 오픈 소스(GPL-3.0)

## 🚀 빠른 시작

1. [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases)에서 APK를 받아 설치합니다.
2. AEmulator Plus → **펌웨어 추가**에서 파일을 고릅니다. 가져오기는 몇 분 걸립니다.
3. **시작**을 누릅니다. 첫 부팅은 앱 최적화로 더 오래 걸립니다.
4. ⋮ 메뉴에서 볼륨·전원 버튼·로그, ⚙️에서 설정과 언어.

## 📋 요구 사항

- 64비트 ARM 휴대폰(arm64-v8a)의 Android 8.0+
- 펌웨어당 약 1–3GB 여유 공간
- 최신 Snapdragon / Dimensity / Tensor 권장

## ⚙️ 작동 방식

각 게스트 프로세스는 수정된 사용자 모드 QEMU에서 실행됩니다. binder 데몬이 커널 드라이버를 대신하고, GL 브리지가 OpenGL ES 호출을 GPU로 전달하며, 작은 게스트 라이브러리(오디오 HAL, audio policy 래퍼, LD_PRELOAD 심)가 제조사 코드를 에뮬레이터에 맞춥니다. 가져오기 도구는 펌웨어를 읽고 boot 이미지의 init 스크립트로 서비스 시작 계획을 만듭니다.

## 🛠️ 소스에서 빌드

테스트된 앱 릴리스 빌드는 JDK 21과 Android SDK 36을 사용합니다. 네이티브 툴체인과 서명에 관한 자세한 내용은 [빌드/소스 안내](../build-source.md)를 참고하세요. **상속받은 엔진 프리빌트 바이너리는 소스와 빌드 출처가 아직 완전히 검증되지 않았습니다**. [감사 문서](../license-audit.md)를 참고하세요.

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## 🙏 감사의 말

AEmulator Plus는 [원작자](https://t.me/istratiit_ech)의 HTC Desire HD·HTC One M7 에뮬레이터에서 시작되었습니다. 그의 엔진이 없었다면 이 프로젝트도 없었습니다.

이 프로젝트는 [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)의 수정된 포크이며, 해당 프로젝트 역시 [uxazu/AEmulator](https://github.com/uxazu/AEmulator)의 수정된 포크입니다.

## 🔗 링크

- 내 포크: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- 업스트림: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- 원본: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- 제작자: [somerandomusername2](https://github.com/somerandomusernam2)
- 원작자: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 라이선스

GPL-3.0. Android, 상표, 펌웨어는 각 소유자에게 있습니다.

[날짜가 표기된 수정 고지](../../NOTICE.md)와 [아직 해결되지 않은 소스/라이선스 감사](../license-audit.md)를 참고하세요. GPL 표기는 포함된 모든 프리빌트 바이너리에 일치하는 완전한 소스가 있다는 인증이 아닙니다.
