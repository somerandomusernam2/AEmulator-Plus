# AEmulator Plus: new/changed README strings, per language. Merged over texts.py by gen.py.
# Placeholders: {build} {audit} {notice} (relative links), {sunset} {upstream} (repo URLs).
P = {}

P["en"] = dict(
    build_text="The tested app release build uses JDK 21 and Android SDK 36. See [build/source instructions]({build}), including native toolchain details and signing. **Inherited engine prebuilts do not yet have verified complete source/build provenance**; see [the audit]({audit}).",
    fork_line="This is a modified fork of [drel4/AEmulator-Sunset]({sunset}), which is a modified fork of [uxazu/AEmulator]({upstream}).",
    l_fork="My fork", l_upstream="Upstream", l_original="Original",
    license_more="See [dated modification notices]({notice}) and the [outstanding source/licensing audit]({audit}). The GPL label is not a certification that every bundled prebuilt has complete matching source.",
)
P["ru"] = dict(
    build_text="Проверенная сборка релизного приложения использует JDK 21 и Android SDK 36. См. [инструкцию по сборке и исходникам]({build}) — там же детали нативного тулчейна и подписи. **У унаследованных готовых бинарников движка пока нет подтверждённого полного происхождения исходников и сборки**; см. [аудит]({audit}).",
    fork_line="Это модифицированный форк [drel4/AEmulator-Sunset]({sunset}), который, в свою очередь, является модифицированным форком [uxazu/AEmulator]({upstream}).",
    l_fork="Мой форк", l_upstream="Апстрим", l_original="Оригинал",
    license_more="См. [датированные уведомления об изменениях]({notice}) и [незакрытый аудит исходников и лицензий]({audit}). Метка GPL не гарантирует, что у каждого встроенного готового бинарника есть полные соответствующие исходники.",
)
P["uk"] = dict(
    build_text="Перевірена збірка релізного застосунку використовує JDK 21 та Android SDK 36. Див. [інструкцію зі збірки та вихідного коду]({build}), зокрема подробиці про нативний тулчейн і підпис. **Для успадкованих готових бінарників рушія досі немає підтвердженого повного походження вихідного коду та збірки**; див. [аудит]({audit}).",
    fork_line="Це модифікований форк [drel4/AEmulator-Sunset]({sunset}), який, своєю чергою, є модифікованим форком [uxazu/AEmulator]({upstream}).",
    l_fork="Мій форк", l_upstream="Апстрим", l_original="Оригінал",
    license_more="Див. [датовані повідомлення про зміни]({notice}) та [відкритий аудит вихідного коду й ліцензій]({audit}). Позначка GPL не гарантує, що для кожного вбудованого готового бінарника є повний відповідний вихідний код.",
)
P["de"] = dict(
    build_text="Der getestete Release-Build der App verwendet JDK 21 und Android SDK 36. Siehe die [Build-/Quellcode-Anleitung]({build}) mit Details zur nativen Toolchain und zum Signieren. **Für die geerbten Engine-Binärdateien ist die vollständige Herkunft von Quellcode und Build noch nicht verifiziert**; siehe [das Audit]({audit}).",
    fork_line="Dies ist ein modifizierter Fork von [drel4/AEmulator-Sunset]({sunset}), das wiederum ein modifizierter Fork von [uxazu/AEmulator]({upstream}) ist.",
    l_fork="Mein Fork", l_upstream="Upstream", l_original="Original",
    license_more="Siehe die [datierten Änderungshinweise]({notice}) und das [offene Quellcode-/Lizenz-Audit]({audit}). Das GPL-Label ist keine Bestätigung, dass für jede mitgelieferte Binärdatei der vollständige passende Quellcode vorliegt.",
)
P["fr"] = dict(
    build_text="Le build de release testé de l’application utilise JDK 21 et Android SDK 36. Voir les [instructions de compilation et de sources]({build}), avec les détails de la chaîne d’outils native et de la signature. **Les binaires du moteur hérités n’ont pas encore de provenance complète vérifiée pour les sources et la compilation** ; voir [l’audit]({audit}).",
    fork_line="Ceci est un fork modifié de [drel4/AEmulator-Sunset]({sunset}), lui-même un fork modifié de [uxazu/AEmulator]({upstream}).",
    l_fork="Mon fork", l_upstream="Amont", l_original="Original",
    license_more="Voir les [avis de modification datés]({notice}) et [l’audit des sources et des licences, encore en cours]({audit}). L’étiquette GPL ne certifie pas que chaque binaire fourni dispose de l’intégralité des sources correspondantes.",
)
P["es"] = dict(
    build_text="La compilación de lanzamiento probada de la app usa JDK 21 y Android SDK 36. Consulta las [instrucciones de compilación y código fuente]({build}), con detalles de la cadena de herramientas nativa y la firma. **Los binarios del motor heredados aún no tienen una procedencia completa verificada del código fuente y la compilación**; consulta [la auditoría]({audit}).",
    fork_line="Este es un fork modificado de [drel4/AEmulator-Sunset]({sunset}), que a su vez es un fork modificado de [uxazu/AEmulator]({upstream}).",
    l_fork="Mi fork", l_upstream="Upstream", l_original="Original",
    license_more="Consulta los [avisos de modificación fechados]({notice}) y la [auditoría pendiente de código fuente y licencias]({audit}). La etiqueta GPL no certifica que cada binario incluido tenga el código fuente completo correspondiente.",
)
P["pt-BR"] = dict(
    build_text="A build de lançamento testada do app usa JDK 21 e Android SDK 36. Veja as [instruções de build e código-fonte]({build}), com detalhes da toolchain nativa e da assinatura. **Os binários do motor herdados ainda não têm origem completa verificada do código-fonte e da build**; veja [a auditoria]({audit}).",
    fork_line="Este é um fork modificado de [drel4/AEmulator-Sunset]({sunset}), que por sua vez é um fork modificado de [uxazu/AEmulator]({upstream}).",
    l_fork="Meu fork", l_upstream="Upstream", l_original="Original",
    license_more="Veja os [avisos de modificação datados]({notice}) e a [auditoria pendente de código-fonte e licenças]({audit}). O selo GPL não certifica que cada binário incluído tenha o código-fonte correspondente completo.",
)
P["it"] = dict(
    build_text="La build di rilascio testata dell’app usa JDK 21 e Android SDK 36. Consulta le [istruzioni di build e sorgenti]({build}), con i dettagli sulla toolchain nativa e sulla firma. **I binari del motore ereditati non hanno ancora una provenienza completa verificata di sorgenti e build**; vedi [l’audit]({audit}).",
    fork_line="Questo è un fork modificato di [drel4/AEmulator-Sunset]({sunset}), che a sua volta è un fork modificato di [uxazu/AEmulator]({upstream}).",
    l_fork="Il mio fork", l_upstream="Upstream", l_original="Originale",
    license_more="Vedi gli [avvisi di modifica datati]({notice}) e l’[audit in sospeso su sorgenti e licenze]({audit}). L’etichetta GPL non certifica che ogni binario incluso abbia i sorgenti corrispondenti completi.",
)
P["pl"] = dict(
    build_text="Przetestowana wersja release aplikacji używa JDK 21 i Android SDK 36. Zobacz [instrukcję budowania i kodu źródłowego]({build}) wraz ze szczegółami natywnego toolchainu i podpisywania. **Odziedziczone gotowe pliki binarne silnika nie mają jeszcze zweryfikowanego, pełnego pochodzenia kodu źródłowego i buildu**; zobacz [audyt]({audit}).",
    fork_line="To zmodyfikowany fork [drel4/AEmulator-Sunset]({sunset}), który sam jest zmodyfikowanym forkiem [uxazu/AEmulator]({upstream}).",
    l_fork="Mój fork", l_upstream="Upstream", l_original="Oryginał",
    license_more="Zobacz [datowane informacje o modyfikacjach]({notice}) oraz [otwarty audyt kodu źródłowego i licencji]({audit}). Oznaczenie GPL nie jest zapewnieniem, że każdy dołączony plik binarny ma kompletny, pasujący kod źródłowy.",
)
P["tr"] = dict(
    build_text="Uygulamanın test edilen sürüm derlemesi JDK 21 ve Android SDK 36 kullanır. Yerel araç zinciri ve imzalama ayrıntıları dahil [derleme/kaynak kodu yönergelerine]({build}) bakın. **Devralınan motor ikili dosyalarının kaynak kodu ve derleme kökeni henüz tam olarak doğrulanmamıştır**; bkz. [denetim]({audit}).",
    fork_line="Bu, kendisi de [uxazu/AEmulator]({upstream}) deposunun değiştirilmiş bir çatalı olan [drel4/AEmulator-Sunset]({sunset}) deposunun değiştirilmiş bir çatalıdır.",
    l_fork="Çatalım", l_upstream="Üst proje", l_original="Orijinal",
    license_more="[Tarihli değişiklik bildirimlerine]({notice}) ve [açık kalan kaynak/lisans denetimine]({audit}) bakın. GPL etiketi, pakete dahil her ikili dosyanın eksiksiz ve eşleşen kaynak koduna sahip olduğunun belgesi değildir.",
)
P["ar"] = dict(
    build_text="يستخدم إصدار التطبيق المُختبَر JDK 21 وAndroid SDK 36. راجع [تعليمات البناء والمصدر]({build}) لمعرفة تفاصيل سلسلة الأدوات الأصلية والتوقيع. **الملفات الثنائية الجاهزة للمحرك الموروثة لا تملك بعدُ أصلاً مُتحقَّقاً منه للشيفرة المصدرية وطريقة البناء**؛ راجع [التدقيق]({audit}).",
    fork_line="هذا فرع معدَّل من [drel4/AEmulator-Sunset]({sunset})، وهو بدوره فرع معدَّل من [uxazu/AEmulator]({upstream}).",
    l_fork="فرعي", l_upstream="المستودع الأم", l_original="الأصل",
    license_more="راجع [إشعارات التعديل المؤرخة]({notice}) و[تدقيق المصدر والتراخيص المعلَّق]({audit}). وسم GPL ليس شهادة بأن لكل ملف ثنائي مضمَّن شيفرته المصدرية الكاملة المطابقة.",
)
P["fa"] = dict(
    build_text="ساخت انتشار آزموده‌شدهٔ برنامه از JDK 21 و Android SDK 36 استفاده می‌کند. [راهنمای ساخت و کد منبع]({build}) را ببینید که جزئیات زنجیرهٔ ابزار بومی و امضا را دارد. **برای باینری‌های آمادهٔ موتورِ به‌ارث‌رسیده هنوز منشأ کامل کد منبع و ساخت راستی‌آزمایی نشده است**؛ [ممیزی]({audit}) را ببینید.",
    fork_line="این یک فورک تغییریافته از [drel4/AEmulator-Sunset]({sunset}) است که خود فورک تغییریافته‌ای از [uxazu/AEmulator]({upstream}) است.",
    l_fork="فورک من", l_upstream="مخزن بالادستی", l_original="اصلی",
    license_more="[اطلاعیه‌های تاریخ‌دار تغییرات]({notice}) و [ممیزی ناتمام کد منبع و مجوزها]({audit}) را ببینید. برچسب GPL گواه آن نیست که هر باینری همراه، کد منبع کامل و منطبق دارد.",
)
P["hi"] = dict(
    build_text="ऐप का परीक्षित रिलीज़ बिल्ड JDK 21 और Android SDK 36 का उपयोग करता है। नेटिव टूलचेन और साइनिंग के विवरण के साथ [बिल्ड/सोर्स निर्देश]({build}) देखें। **विरासत में मिले इंजन प्रीबिल्ट बाइनरी के सोर्स और बिल्ड की पूरी उत्पत्ति अभी सत्यापित नहीं है**; [ऑडिट]({audit}) देखें।",
    fork_line="यह [drel4/AEmulator-Sunset]({sunset}) का संशोधित फ़ोर्क है, जो स्वयं [uxazu/AEmulator]({upstream}) का संशोधित फ़ोर्क है।",
    l_fork="मेरा फ़ोर्क", l_upstream="अपस्ट्रीम", l_original="मूल",
    license_more="[दिनांकित संशोधन सूचनाएँ]({notice}) और [लंबित सोर्स/लाइसेंस ऑडिट]({audit}) देखें। GPL लेबल इस बात का प्रमाणपत्र नहीं है कि हर शामिल प्रीबिल्ट बाइनरी का पूरा मेल खाता सोर्स मौजूद है।",
)
P["id"] = dict(
    build_text="Build rilis aplikasi yang diuji memakai JDK 21 dan Android SDK 36. Lihat [petunjuk build/sumber]({build}), termasuk detail toolchain native dan penandatanganan. **Binari mesin bawaan warisan belum memiliki asal-usul kode sumber/build yang terverifikasi lengkap**; lihat [audit]({audit}).",
    fork_line="Ini adalah fork yang dimodifikasi dari [drel4/AEmulator-Sunset]({sunset}), yang sendiri merupakan fork yang dimodifikasi dari [uxazu/AEmulator]({upstream}).",
    l_fork="Fork saya", l_upstream="Upstream", l_original="Asli",
    license_more="Lihat [pemberitahuan modifikasi bertanggal]({notice}) dan [audit sumber/lisensi yang belum selesai]({audit}). Label GPL bukan sertifikasi bahwa setiap binari bawaan memiliki kode sumber yang cocok dan lengkap.",
)
P["vi"] = dict(
    build_text="Bản dựng phát hành đã kiểm thử của ứng dụng dùng JDK 21 và Android SDK 36. Xem [hướng dẫn dựng/mã nguồn]({build}), gồm chi tiết về toolchain native và ký ứng dụng. **Các tệp nhị phân dựng sẵn của engine được kế thừa vẫn chưa có nguồn gốc mã nguồn/bản dựng đầy đủ được xác minh**; xem [bản kiểm toán]({audit}).",
    fork_line="Đây là bản fork đã chỉnh sửa của [drel4/AEmulator-Sunset]({sunset}), vốn cũng là bản fork đã chỉnh sửa của [uxazu/AEmulator]({upstream}).",
    l_fork="Fork của tôi", l_upstream="Upstream", l_original="Bản gốc",
    license_more="Xem [thông báo sửa đổi có ghi ngày]({notice}) và [bản kiểm toán mã nguồn/giấy phép còn tồn đọng]({audit}). Nhãn GPL không phải là xác nhận rằng mọi tệp nhị phân dựng sẵn đều có mã nguồn khớp đầy đủ.",
)
P["zh-CN"] = dict(
    build_text="经测试的应用发布版本使用 JDK 21 和 Android SDK 36。请参阅[构建/源码说明]({build})，其中包含原生工具链和签名的详细信息。**继承而来的引擎预编译二进制文件尚未验证其完整的源码与构建来源**；参见[审计文档]({audit})。",
    fork_line="这是 [drel4/AEmulator-Sunset]({sunset}) 的修改版分支，而后者又是 [uxazu/AEmulator]({upstream}) 的修改版分支。",
    l_fork="我的分支", l_upstream="上游", l_original="原始项目",
    license_more="请参阅[带日期的修改声明]({notice})和[尚未完成的源码/许可审计]({audit})。GPL 标签并不保证每个随附的预编译二进制文件都有完整且匹配的源码。",
)
P["ja"] = dict(
    build_text="動作確認済みのアプリのリリースビルドは JDK 21 と Android SDK 36 を使用します。ネイティブツールチェーンや署名の詳細は[ビルド／ソース手順]({build})をご覧ください。**継承したエンジンのビルド済みバイナリについては、ソースコードとビルドの出自がまだ完全には検証されていません**。[監査ドキュメント]({audit})を参照してください。",
    fork_line="これは [drel4/AEmulator-Sunset]({sunset}) の改変フォークであり、そのリポジトリ自体も [uxazu/AEmulator]({upstream}) の改変フォークです。",
    l_fork="私のフォーク", l_upstream="アップストリーム", l_original="オリジナル",
    license_more="[日付入りの改変通知]({notice})と[未完了のソース／ライセンス監査]({audit})をご覧ください。GPL の表記は、同梱の各ビルド済みバイナリに対応する完全なソースがあることを保証するものではありません。",
)
P["ko"] = dict(
    build_text="테스트된 앱 릴리스 빌드는 JDK 21과 Android SDK 36을 사용합니다. 네이티브 툴체인과 서명에 관한 자세한 내용은 [빌드/소스 안내]({build})를 참고하세요. **상속받은 엔진 프리빌트 바이너리는 소스와 빌드 출처가 아직 완전히 검증되지 않았습니다**. [감사 문서]({audit})를 참고하세요.",
    fork_line="이 프로젝트는 [drel4/AEmulator-Sunset]({sunset})의 수정된 포크이며, 해당 프로젝트 역시 [uxazu/AEmulator]({upstream})의 수정된 포크입니다.",
    l_fork="내 포크", l_upstream="업스트림", l_original="원본",
    license_more="[날짜가 표기된 수정 고지]({notice})와 [아직 해결되지 않은 소스/라이선스 감사]({audit})를 참고하세요. GPL 표기는 포함된 모든 프리빌트 바이너리에 일치하는 완전한 소스가 있다는 인증이 아닙니다.",
)
