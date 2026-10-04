<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Plus logo"/>

# AEmulator Plus

**पुराने Android फ़र्मवेयर — HTC Sense, TouchWiz, MIUI, AOSP — आधुनिक फ़ोन पर। बिना root, बिना PC।**

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · **🇮🇳 हिन्दी** · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Plus सीधे फ़र्मवेयर फ़ाइल से असली Android 2.3–7.x सिस्टम बूट करता है: रिकवरी ZIP, Odin आर्काइव या Google फ़ैक्टरी इमेज। पुराना ARM कोड संशोधित QEMU से चलता है, कर्नेल का binder एमुलेट होता है, ग्राफ़िक्स फ़ोन के GPU से और आवाज़ Android के ऑडियो सिस्टम से चलती है — सब कुछ एक सामान्य ऐप के भीतर।

## ✨ विशेषताएँ

- लगभग हर फ़ॉर्मेट आयात करें: CWM/TWRP ZIP, Samsung Odin `.tar.md5`, Google फ़ैक्टरी `.tgz`, `system.img`, OTA `system.new.dat.br`
- कंपनियों के स्किन जैसे हैं वैसे चलते हैं: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- GL ब्रिज से हार्डवेयर ग्राफ़िक्स, आवाज़, टच और मल्टीटच, आधुनिक TLS प्रॉक्सी के साथ नेटवर्क
- APK, संगीत और फ़ोटो के लिए साझा मेमोरी कार्ड फ़ोल्डर
- 18 भाषाओं में Material 3 Expressive इंटरफ़ेस
- मुफ़्त और ओपन सोर्स (GPL-3.0)

## 🚀 जल्दी शुरुआत

1. [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) से APK डाउनलोड करके इंस्टॉल करें।
2. AEmulator Plus खोलें → **फ़र्मवेयर जोड़ें** और फ़ाइल चुनें। आयात में कुछ मिनट लगते हैं।
3. **चलाएँ** दबाएँ। पहला बूट धीमा होता है: सिस्टम ऐप्स ऑप्टिमाइज़ करता है।
4. ⋮ मेनू में वॉल्यूम, पावर बटन और लॉग; ⚙️ से सेटिंग्स और भाषा।

## 📋 आवश्यकताएँ

- 64-बिट ARM फ़ोन (arm64-v8a) पर Android 8.0+
- प्रति फ़र्मवेयर लगभग 1–3 GB खाली जगह
- नया Snapdragon / Dimensity / Tensor सुझाया जाता है

## ⚙️ यह कैसे काम करता है

हर गेस्ट प्रोसेस संशोधित यूज़र-मोड QEMU में चलता है। एक binder डेमन कर्नेल ड्राइवर की जगह लेता है, GL ब्रिज OpenGL ES कॉल GPU तक भेजता है, और छोटी गेस्ट लाइब्रेरी (ऑडियो HAL, audio policy रैपर, LD_PRELOAD शिम) निर्माताओं के कोड को एमुलेटर के अनुकूल बनाती हैं। इम्पोर्टर फ़र्मवेयर पढ़ता है, boot इमेज में init स्क्रिप्ट ढूँढता है और सेवाओं की शुरुआत की योजना बनाता है।

## 🛠️ सोर्स से बनाएँ

ऐप का परीक्षित रिलीज़ बिल्ड JDK 21 और Android SDK 36 का उपयोग करता है। नेटिव टूलचेन और साइनिंग के विवरण के साथ [बिल्ड/सोर्स निर्देश](../build-source.md) देखें। **विरासत में मिले इंजन प्रीबिल्ट बाइनरी के सोर्स और बिल्ड की पूरी उत्पत्ति अभी सत्यापित नहीं है**; [ऑडिट](../license-audit.md) देखें।

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## 🙏 आभार

AEmulator Plus [मूल लेखक](https://t.me/istratiit_ech) के HTC Desire HD और HTC One M7 एमुलेटर से विकसित हुआ — उनके इंजन के बिना यह प्रोजेक्ट नहीं होता।

यह [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset) का संशोधित फ़ोर्क है, जो स्वयं [uxazu/AEmulator](https://github.com/uxazu/AEmulator) का संशोधित फ़ोर्क है।

## 🔗 लिंक

- मेरा फ़ोर्क: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- अपस्ट्रीम: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- मूल: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- लेखक: [somerandomusername2](https://github.com/somerandomusernam2)
- मूल लेखक: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 लाइसेंस

GPL-3.0। Android, ट्रेडमार्क और फ़र्मवेयर उनके स्वामियों के हैं।

[दिनांकित संशोधन सूचनाएँ](../../NOTICE.md) और [लंबित सोर्स/लाइसेंस ऑडिट](../license-audit.md) देखें। GPL लेबल इस बात का प्रमाणपत्र नहीं है कि हर शामिल प्रीबिल्ट बाइनरी का पूरा मेल खाता सोर्स मौजूद है।
