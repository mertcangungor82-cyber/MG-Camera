# M&G Camera 2.0 — Quality Rebuild

Bu sürüm, önceki 1.0 Full sürümdeki iki temel sorunu düzeltmek için yeniden düzenlendi: normal PHOTO modunda gereksiz yazılım işleme uygulanması ve gerçek kamera çözünürlüklerinin kullanıcıya seçtirilememesi.

## Ana davranış
- **PHOTO:** varsayılan olarak AI/HDR filtresi uygulanmaz; telefonun CameraX üzerinden verdiği **native JPEG** doğrudan kaydedilir.
- **PRO:** native JPEG + EV + tap-to-focus + zoom.
- **MP seçimi:** üst çubuktaki MP düğmesi, arka/ön kamera için Camera2'nin gerçekten sunduğu JPEG boyutlarını listeler.
- OEM 48/64 MP çözünürlüğü üçüncü parti uygulamalara açılmıyorsa uygulama bunu uydurmaz veya sahte 64 MP üretmez.
- Büyük çözünürlük Preview + ImageCapture ile OEM HAL tarafından reddedilirse uygulama güvenli yüksek çözünürlüğe geri düşer.
- **AI / NIGHT / PORTRAIT / SUPER ZOOM:** isteğe bağlı yazılım modlarıdır; V2'de ton, doygunluk, sharpen ve noise reduction daha konservatif hale getirildi.
- İşlenmiş modlarda native kaynak varsayılan olarak korunur.
- Fotoğraflar `Pictures/MGCamera`, işleme kaynakları `Pictures/MGCamera/Original`, videolar `Movies/MGCamera` içine gider.

## Arayüz
- Daha sade kamera üst barı: flash, MP, HDR, timer, settings.
- AI düğmesi sadece AI modunda, EV düğmesi yalnız PRO modunda görünür.
- Alt bölümde sade mod şeridi, dairesel zoom düğmeleri, shutter, galeri ve kamera çevirme.
- Izgara ayarlardan açılıp kapatılır.

## Önemli
Telefonun stok kamera uygulaması, üreticinin kapalı ISP/algoritmalarına erişebilir. Üçüncü parti bir uygulama aynı donanımda her zaman aynı işleme yoluna erişemez. Bu sürüm normal PHOTO çekiminde kaliteyi bozacak yapay işleme uygulamak yerine native kamera JPEG çıktısını önceliklendirir.
