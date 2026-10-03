# M&G Camera — Teknik Mimari

## 1. Capture Core

`CameraController.kt`

- CameraX Preview
- CameraX ImageCapture
- CameraX VideoCapture / Recorder
- Camera2 tabanlı donanım katmanı CameraX üzerinden
- Tap focus / AE metering
- Zoom / flash / lens switching

Fotoğraf ve video aynı anda bind edilmek yerine moda göre ayrı bind edilir. Bunun nedeni orta sınıf cihazlardaki ISP stream-combination limitlerinden kaçınmaktır.

## 2. Device Intelligence

`DeviceCapabilityScanner.kt`

Her cihazın Camera HAL'i farklıdır. Bir özellik UI'da gösterilmeden önce hardware raporundan doğrulanmalıdır. Özellikle Camon 20 Pro testinde aşağıdakiler kritik:

- 64 MP JPEG üçüncü parti uygulamaya açık mı?
- YUV maksimum boyutu nedir?
- RAW capability var mı?
- BURST_CAPTURE capability var mı?
- OIS gerçekten Camera2 üzerinden expose ediliyor mu?
- Hangi video stabilization modları açık?
- Maksimum dijital zoom kaç?
- 4K üçüncü parti uygulamaya açık mı?

## 3. Still Processing v0.1

`ProcessingPipeline.kt` → `PhotoEnhancer.kt`

İlk sürüm generative değildir. Girdi pikselinden doğrulanamayan hiçbir detay üretmez.

Aşamalar:

1. EXIF orientation normalize
2. Bellek güvenli çalışma boyutu
3. Luma histogram sampling
4. 0.8% / 99.2% yaklaşık siyah-beyaz sınırı
5. Soft S-curve
6. Shadow lift
7. Highlight compression
8. Luminance-preserving RGB scale
9. Chroma-aware vibrance
10. Shadow-adaptive denoise
11. Shadow-adaptive unsharp detail
12. JPEG 96 kalite kayıt

## 4. Multi-frame v0.2 tasarımı

Capture sırasında YUV_420_888 ring buffer tutulacak.

Önerilen burst:

- Gündüz: 6–8 kare
- İç mekân: 8–12 kare
- Gece: 12–20 kare
- Super Zoom: 8–16 kare

Pipeline:

`Frame scoring → gyro seed → pyramid alignment → optical flow refinement → motion segmentation → robust fusion → temporal denoise → SR reconstruction → tone map → semantic local processing`

### Frame scoring

Her kare için:

- global sharpness
- face sharpness
- motion magnitude
- clipped highlights
- shadow SNR

ölçülür ve referans kare seçilir.

### Motion / ghosting

Hareketli insan/araç/hayvan bölgeleri statik arka plan gibi birleştirilmez. Motion mask üzerinden referans kare ağırlığı artırılır.

## 5. Super Zoom

### 1×
Native/binned sensör çıktısı.

### 2×
Mümkünse yüksek çözünürlüklü sensör crop + multi-frame fusion.

### 3×
Crop + sub-pixel multi-frame SR.

### 5×+
Gerçek sensör verisine dayalı SR. Generative detail varsayılan olarak kapalı.

### Text Safe

OCR yalnızca text ROI tespiti / yön / perspektif için kullanılacak. OCR çıktısı görüntüye tekrar basılmayacak; böylece `8` karakteri `3` diye uydurma riski azaltılır.

## 6. Night Fusion

- Exposure bracket yalnız cihaz throughput'u uygunsa
- Gyro + image alignment
- Hot/dead pixel rejection
- Temporal chroma denoise
- Highlight-preserving merge
- Shadow SNR recovery
- Color cast correction
- Local tone map
- Fine detail pass

Tripod tespiti ivmeölçer + gyro varyansından yapılacak; sabitse capture window uzatılabilecek.

## 7. Video Engine

### Gerçek zamanlı katman

- OEM video stabilization varsa kullan
- Gyro path smoothing
- exposure / WB smoothing
- highlight guard
- spatial denoise
- hafif edge-aware sharpen
- bitrate/quality profile

### Post-record Enhance

Daha ağır işlemler offline:

- rolling shutter düzeltme
- stronger gyro stabilization
- temporal denoise
- local tone map
- optional 1080p→higher-quality upscale

## 8. Performans

Helio G99 sınıfı cihazda hedef:

- UI preview: akıcı
- gerçek zamanlı video: hafif pipeline
- ağır AI/SR: post-capture veya post-record
- 64 MP: C++/NDK tile pipeline
- ML inference: küçük modeller + GPU delegate / cihaz uygunluğuna göre CPU fallback

## 9. Kalite prensipleri

- Yüz kimliğini değiştirme yok
- Yazı uydurma yok
- Aşırı skin smoothing varsayılan yok
- Halo suppression
- Saturation clipping önleme
- Original her zaman korunur
- Feature support cihazdan sorgulanır; sahte 4K/RAW/optical zoom etiketi gösterilmez
