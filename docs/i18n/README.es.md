<div align="center">

<img src="../../docs/assets/logo.png" width="128" alt="AEmulator Plus logo"/>

# AEmulator Plus

**Firmware clásico de Android — HTC Sense, TouchWiz, MIUI, AOSP — en un teléfono moderno. Sin root, sin PC.**

[🇬🇧 English](../../README.md) · [🇷🇺 Русский](README.ru.md) · [🇺🇦 Українська](README.uk.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md) · **🇪🇸 Español** · [🇧🇷 Português](README.pt-BR.md) · [🇮🇹 Italiano](README.it.md) · [🇵🇱 Polski](README.pl.md) · [🇹🇷 Türkçe](README.tr.md) · [🇸🇦 العربية](README.ar.md) · [🇮🇷 فارسی](README.fa.md) · [🇮🇳 हिन्दी](README.hi.md) · [🇮🇩 Indonesia](README.id.md) · [🇻🇳 Tiếng Việt](README.vi.md) · [🇨🇳 简体中文](README.zh-CN.md) · [🇯🇵 日本語](README.ja.md) · [🇰🇷 한국어](README.ko.md)

</div>

---

AEmulator Plus arranca un sistema Android 2.3–7.x real directamente desde un archivo de firmware: ZIP de recovery, archivo de Odin o imagen de fábrica de Google. El código ARM antiguo se traduce con un QEMU modificado, el binder del kernel se emula, los gráficos usan la GPU del teléfono y el sonido pasa por la pila de audio de Android — todo dentro de una app normal.

## ✨ Características

- Importa casi cualquier formato: ZIP CWM/TWRP, Odin `.tar.md5` de Samsung, imagen de fábrica `.tgz` de Google, `system.img`, OTA `system.new.dat.br`
- Las capas de los fabricantes funcionan tal cual: HTC Sense, Samsung TouchWiz, MIUI, AOSP
- Gráficos por hardware con el puente GL, sonido, táctil y multitáctil, red con proxy TLS moderno
- Carpeta compartida de tarjeta de memoria para APK, música y fotos
- Interfaz Material 3 Expressive en 18 idiomas
- Gratis y de código abierto (GPL-3.0)

## 🚀 Inicio rápido

1. Descarga el APK desde [Releases](https://github.com/somerandomusernam2/AEmulator-Plus/releases) e instálalo.
2. Abre AEmulator Plus → **Añadir firmware** y elige el archivo. La importación tarda unos minutos.
3. Pulsa **Iniciar**. El primer arranque es más lento: el sistema optimiza las apps.
4. Menú ⋮ para volumen, botón de encendido y registro; ⚙️ abre ajustes e idioma.

## 📋 Requisitos

- Android 8.0+ en un teléfono ARM de 64 bits (arm64-v8a)
- Unos 1–3 GB libres por firmware
- Se recomienda un Snapdragon / Dimensity / Tensor reciente

## ⚙️ Cómo funciona

Cada proceso invitado se ejecuta con un QEMU de modo usuario modificado. Un demonio binder sustituye al controlador del kernel, un puente GL envía las llamadas OpenGL ES a la GPU y pequeñas bibliotecas invitadas (HAL de audio, envoltorio de audio policy, shim LD_PRELOAD) adaptan el código del fabricante al emulador. El importador lee el firmware, encuentra los scripts init en la imagen boot y crea el plan de arranque de los servicios.

## 🛠️ Compilar desde el código

La compilación de lanzamiento probada de la app usa JDK 21 y Android SDK 36. Consulta las [instrucciones de compilación y código fuente](../build-source.md), con detalles de la cadena de herramientas nativa y la firma. **Los binarios del motor heredados aún no tienen una procedencia completa verificada del código fuente y la compilación**; consulta [la auditoría](../license-audit.md).

```bash
git clone https://github.com/somerandomusernam2/AEmulator-Plus.git
cd AEmulator-Plus
./gradlew copyReleaseApks
```

## 🙏 Créditos

AEmulator Plus nació de los emuladores de HTC Desire HD y HTC One M7 del [autor original](https://t.me/istratiit_ech); sin su motor este proyecto no existiría.

Este es un fork modificado de [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset), que a su vez es un fork modificado de [uxazu/AEmulator](https://github.com/uxazu/AEmulator).

## 🔗 Enlaces

- Mi fork: [somerandomusernam2/AEmulator-Plus](https://github.com/somerandomusernam2/AEmulator-Plus)
- Upstream: [drel4/AEmulator-Sunset](https://github.com/drel4/AEmulator-Sunset)
- Original: [uxazu/AEmulator](https://github.com/uxazu/AEmulator)
- Autor: [somerandomusername2](https://github.com/somerandomusernam2)
- Autor original: [t.me/istratiit_ech](https://t.me/istratiit_ech)

## 📄 Licencia

GPL-3.0. Android, las marcas y el firmware pertenecen a sus dueños.

Consulta los [avisos de modificación fechados](../../NOTICE.md) y la [auditoría pendiente de código fuente y licencias](../license-audit.md). La etiqueta GPL no certifica que cada binario incluido tenga el código fuente completo correspondiente.
