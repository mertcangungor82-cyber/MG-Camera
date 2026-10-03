# M&G Camera — Camon 20 Pro 4G Computational Camera

M&G Camera, Tecno Camon 20 Pro 4G öncelikli tasarlanmış bağımsız Android kamera projesidir. Amaç Samsung/Pixel sınıfı hesaplamalı fotoğrafçılığın temel prensiplerini açık, test edilebilir ve cihaz-özel bir işleme hattında kurmaktır.

## v0.1'de gerçekten çalışanlar

- CameraX 1.6.2 tabanlı canlı kamera önizlemesi
- Ön / arka kamera geçişi
- Dokunarak AF + AE ölçümü
- Pinch-to-zoom ve 1× / 2× / 3× / 5× / 10× hızlı zoom düğmeleri
- Flaş: Auto / Off / On
- Fotoğraf çekimi ve MediaStore kaydı
- Video kaydı; cihaz destek durumuna göre UHD → FHD → HD fallback
- Mikrofon izni varsa sesli, yoksa sessiz video
- PHOTO / AI / NIGHT / SUPER ZOOM / VIDEO / PRO arayüz modları
- AI OFF / NATURAL / AI / AI MAX seviyeleri
- Çekim sonrası gerçek piksel tabanlı geliştirme:
  - Histogram tabanlı dinamik siyah/beyaz nokta
  - Yumuşak S-curve tone mapping
  - Shadow lift
  - Highlight compression
  - Adaptif vibrance / saturation
  - Karanlık alanlarda komşu piksel tabanlı noise smoothing
  - Karanlığa göre azaltılan unsharp/detail pass
- Orijinal fotoğrafı ayrı klasörde koruma
- Device Profiler:
  - Camera2 hardware level
  - gerçek sensör pixel array / active array
  - max JPEG / YUV çözünürlüğü
  - RAW desteği
  - manual sensor desteği
  - burst capture
  - OIS
  - video stabilization modları
  - max digital zoom
  - focal length / aperture
  - FPS aralıkları
  - logical multi-camera

## Klasörler

- `Pictures/MGCamera/Original` — sensörden kaydedilen orijinal JPEG
- `Pictures/MGCamera` — işlenmiş AI JPEG
- `Movies/MGCamera` — videolar

## Android Studio ile açma

1. Android Studio Rabbit 1 / 2026.2.1 veya yeni stabil sürüm kullan.
2. Bu klasörü `Open` ile proje olarak aç.
3. JDK 17 seç.
4. Android SDK 36 yüklü olsun.
5. Gradle sync yap.
6. Tecno Camon 20 Pro 4G'yi USB debugging ile bağla.
7. `app` konfigürasyonunu gerçek cihazda çalıştır.
8. İlk açılışta Kamera + Mikrofon izinlerini ver.
9. Sağ üst `DEVICE` düğmesine basıp profiler raporunu kopyala. Bu rapor, cihaz-özel Super Zoom / Night Fusion kalibrasyonunun girdisi olacak.

> Not: Bu teslimde çalışma ortamında Android SDK ve Gradle wrapper binary'si bulunmadığı için burada APK derlenemedi. Kaynak proje Android Studio sync/build için hazırlanmıştır.

## Neden orijinal fotoğrafı koruyoruz?

Computational photography'de agresif işlem bazen renk veya mikrokontrastı bozabilir. M&G Camera hiçbir zaman sensör çekimini yok etmez. İşlenmiş sürüm ayrı dosyadır; ileride uygulama içi `ORIGINAL ⇄ AI` karşılaştırması bu iki dosyayı eşleştirecek.

## 64 MP konusu

v0.1 Kotlin piksel motoru bellek güvenliği için AI türevini yaklaşık 8.5 MP çalışma alanında işler; orijinal tam çözünürlük dosyası korunur. Nihai sürümde 64 MP işleme C++/NDK + tile streaming ile yapılacak; tam kareyi RAM'e dört kopya yükleme yaklaşımı kullanılmayacak.

## Sonraki yüksek etkili paket

1. Camera2 YUV ring-buffer / Zero Shutter Lag
2. 6–12 kare sub-pixel alignment
3. Gyro-assisted alignment
4. Motion mask + ghost suppression
5. Multi-frame temporal denoise
6. Gerçek multi-frame Super Resolution
7. Text-safe SR (OCR yalnız ROI bulur, harf üretmez)
8. Semantic scene masks: yüz/cilt/saç/gökyüzü/yaprak/yazı/bina
9. Native tile pipeline ile 64 MP
10. Video gyro EIS + temporal denoise + post-record enhance


## Sadece telefonla GitHub üzerinde APK derleme

Projeye `.github/workflows/build-apk.yml` eklendi. Repository `main` veya `master` branch'ine yüklendiğinde GitHub Actions otomatik olarak install edilebilir debug APK üretir. Telefonda `Actions > Build M&G Camera APK` sayfasından manuel `Run workflow` da kullanılabilir. Build sonunda `M-G-Camera-APK` artifact'ını indir. Ayrıntılı adımlar `PHONE_GITHUB_BUILD_TR.md` dosyasında.
