# M&G Camera 1.0 Full

Android için CameraX tabanlı, cihaz uyumluluk katmanı bulunan tam sürüm kamera uygulaması.

## Çalışan ana modlar
- PHOTO: hızlı, dengeli fotoğraf çekimi.
- NIGHT: düşük ışık için gölge açma, ton sıkıştırma ve karanlık bölge denoise.
- PORTRAIT: merkez konuyu koruyan, arka planı yumuşatan portre efekti.
- AI: sahne tipini parlaklık/renk istatistiklerinden algılayıp ton ve renk ayarı uygular.
- SUPER ZOOM: dijital zoom sonrası detay güçlendirme ve yüksek JPEG kalite profili.
- VIDEO: cihaz destekliyorsa UHD, aksi halde FHD/HD geri dönüşü; ses izni varsa mikrofon; pause/resume.
- PRO: otomatik görüntü işleme kapalı; EV, odak ve zoom doğrudan kullanıcı kontrolünde.

## Ortak özellikler
- Tap-to-focus ve görsel odak halkası.
- Pinch zoom + 1x/2x/3x/5x/10x hızlı zoom.
- Flash Auto/Off/On; video modunda torch.
- EV pozlama telafisi ve reset.
- Smart HDR ton sıkıştırma aç/kapat.
- AI Off/Natural/AI/AI Max.
- 3/5/10 saniye zamanlayıcı.
- Kompozisyon ızgarası.
- Ön/arka kamera geçişi.
- Video duraklat/devam ettir.
- Fotoğraflar `Pictures/MGCamera`, videolar `Movies/MGCamera` altında.
- İstenirse işlenmiş fotoğrafla birlikte orijinal JPEG de saklanır.
- Cihaz kamera profil tarayıcısı; Camera2 donanım seviyesi, RAW/manual sensor, OIS, max zoom vb. raporlar.
- OEM kamera HAL'ı iddialı akışı reddederse güvenli CameraX uyumluluk modu.

## Teknik sınırlar
M&G Camera cihazın gerçek kamera sensörü/lens/ISP kabiliyetlerinin üzerinde fiziksel detay üretemez. Night/HDR/Portrait/Super Zoom yazılım tarafında gerçek çalışan işlemler içerir; ancak Samsung/Google gibi üreticilerin kapalı kaynak çok-kareli ISP ve özel neural modellerinin birebir kopyası değildir. Uygulama sonuç uydurmamaya ve orijinali korumaya öncelik verir.
