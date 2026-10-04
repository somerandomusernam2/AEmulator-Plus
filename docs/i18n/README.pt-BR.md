<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Plus logo"/>

# AEmulator Plus

**Firmwares clássicos do Android — HTC Sense, TouchWiz, MIUI, AOSP — em um celular moderno. Sem root, sem PC.**

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · [🇪🇸 Español](README.es.md) · **🇧🇷 Português** · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

O AEmulator Plus inicia um sistema Android 2.3–7.x real direto de um arquivo de firmware: ZIP de recovery, pacote do Odin ou imagem de fábrica do Google. O código ARM antigo é traduzido por um QEMU modificado, o binder do kernel é emulado, os gráficos usam a GPU do celular e o som passa pelo áudio do Android — tudo dentro de um app comum.

## ✨ Recursos

- Importa quase qualquer formato: ZIP CWM/TWRP, Odin `.tar.md5` da Samsung, imagem de fábrica `.tgz` do Google, `system.img`, OTA `system.new.dat.br`
- Interfaces das fabricantes funcionam como vieram: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Gráficos por hardware pela ponte GL, som, toque e multitoque, rede com proxy TLS moderno
- Pasta compartilhada de cartão de memória para APKs, músicas e fotos
- Interface Material 3 Expressive em 18 idiomas
- Gratuito e de código aberto (GPL-3.0)

## 🚀 Início rápido

1. Baixe o APK em [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) e instale.
2. Abra o AEmulator Plus → **Adicionar firmware** e escolha o arquivo. A importação leva alguns minutos.
3. Toque em **Iniciar**. A primeira inicialização é mais lenta: o sistema otimiza os apps.
4. Menu ⋮ para volume, botão liga/desliga e registro; ⚙️ abre configurações e idioma.

## 📋 Requisitos

- Android 8.0+ em celular ARM de 64 bits (arm64-v8a)
- Cerca de 1–3 GB livres por firmware
- Recomendado: Snapdragon / Dimensity / Tensor recente

## ⚙️ Como funciona

Cada processo convidado roda num QEMU de modo usuário modificado. Um daemon binder substitui o driver do kernel, uma ponte GL envia as chamadas OpenGL ES para a GPU e pequenas bibliotecas (HAL de áudio, wrapper de audio policy, shim LD_PRELOAD) adaptam o código das fabricantes ao emulador. O importador lê o firmware, acha os scripts init na imagem boot e monta o plano de início dos serviços.

## 🛠️ Compilar do código-fonte

A build de lançamento testada do app usa JDK 21 e Android SDK 36. Veja as [instruções de build e código-fonte](../build-source.md), com detalhes da toolchain nativa e da assinatura. **Os binários do motor herdados ainda não têm origem completa verificada do código-fonte e da build**; veja [a auditoria](../license-audit.md).

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## 🙏 Créditos

O AEmulator Plus nasceu dos emuladores de HTC Desire HD e HTC One M7 do [autor original](https://t.me/istratiit_ech) — sem o motor dele este projeto não existiria.

Este é um fork modificado de [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), que por sua vez é um fork modificado de [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## 🔗 Links

- Meu fork: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Upstream: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Original: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Autor: [somerandomusername2](https://github.com/somerandomusernam2)
- Autor original: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 Licença

GPL-3.0. Android, marcas e firmwares pertencem aos seus donos.

Veja os [avisos de modificação datados](../../NOTICE.md) e a [auditoria pendente de código-fonte e licenças](../license-audit.md). O selo GPL não certifica que cada binário incluído tenha o código-fonte correspondente completo.
