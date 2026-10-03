# Sadece telefonla M&G Camera APK derleme

Bu proje bilgisayar gerektirmeden GitHub Actions üzerinde APK derleyecek şekilde hazırlanmıştır.

## En kolay yöntem

1. Telefonda GitHub uygulamasını veya github.com sitesini aç.
2. Yeni, boş bir repository oluştur: `MG-Camera`.
3. Repository'ye bu projenin dosyalarını yükle veya ChatGPT'deki GitHub bağlantısını kullanarak projeyi repository'ye aktart.
4. Repository içinde `Actions` sekmesine gir.
5. `Build M&G Camera APK` workflow'unu aç.
6. `Run workflow` > `Run workflow` seç.
7. Build tamamlanınca aynı workflow çalışmasının altındaki `Artifacts` bölümünde `M-G-Camera-APK` görünür.
8. Artifact ZIP'ini indir, içindeki `M-G-Camera-v0.1.0-debug.apk` dosyasını çıkar ve telefona kur.

## İlk build otomatik de başlayabilir

Dosyalar `main` veya `master` branch'ine yüklendiğinde workflow otomatik çalışır. Manuel olarak `Run workflow` kullanmak zorunda değilsin.

## Android kurulum uyarısı

Debug APK kişisel test için imzalı ve kurulabilir durumdadır. Android, GitHub'dan indirilen APK için `Bilinmeyen uygulamaları yükle` izni isteyebilir. Bu izni yalnızca kendi oluşturduğun APK için ver.

## Build hata verirse

GitHub Actions çalışmasını açıp kırmızı olan adıma gir. Log'un ekran görüntüsünü veya metnini ChatGPT'ye gönder. Hatanın bulunduğu Gradle/Kotlin/Android dosyası düzeltilir ve tekrar build alınır.
