# Kır Geç AR

Telefon kamerasıyla bulunduğun mekâna (ev, kafe, mağaza…) bak; oyun görüntüdeki
**kırılabilir eşyaları** (şişe, kadeh, bardak, vazo, televizyon, laptop…) otomatik
bulur. Elindeki **top** ya da **taşı** fırlat, eşya ekranda paramparça olsun.
Gerçekte hiçbir şey kırılmaz. Her şey ekranda olur.

## Nasıl oynanır
- **Yukarı kaydır**: parmağını bıraktığın yöne fırlatır. Hızlı kaydırırsan biraz daha uzağa gider.
- **Dokun**: dokunduğun noktaya atar.
- **Top**: büyük, isabet alanı geniş, 1 hasar. **Taş**: küçük, hızlı, 2 hasar.
- Televizyon, laptop gibi sağlam eşyalar birkaç vuruşta kırılır. Önce çatlar, sonra dağılır.
- Avize gibi modelin tanımadığı bir şey varsa **Hedef çiz** ile parmağınla etrafına kutu çiz.
- Görüntü bulanıksa **📷** ile diğer arka kameraya geç (bazı telefonlar önce geniş açı lensi açar).
- Kırılan eşya 6 saniye "yok" görünür, sonra yeniden hedef olur.

## Nasıl çalışır
| Parça | Teknoloji |
|---|---|
| Kamera | `getUserMedia` (arka kamera, 1080p, sürekli odak). 📷 düğmesi arka lensler arasında geçiş yapar |
| Nesne tanıma | TensorFlow.js + COCO-SSD (`lite_mobilenet_v2`), tamamen telefonda ve ayrı bir iş parçacığında (Web Worker) çalışır, böylece animasyon takılmaz |
| Kırılma efekti | Nesnenin o anki kamera görüntüsü kesilir, çarpma noktasından radyal parçalara bölünür, fizikle saçılır. Nesnenin yeri zemin rengiyle örtülür ve altına enkaz çizilir. |
| Ses | WebAudio ile sentezlenen cam/elektronik kırılma sesleri, titreşim |

Dosyalar: `index.html`, `style.css`, `js/main.js` (oyun döngüsü, takip, atış),
`js/detector-worker.js` (nesne tanıma), `js/shatter.js` (parçalanma), `js/audio.js` (sesler).

## Çalıştırma
Kamera izni yalnızca **HTTPS** (veya `localhost`) üzerinde çalışır.

- **GitHub Pages**: Repo ayarlarında *Pages → Deploy from branch* seç. Telefondan `https://<kullanıcı>.github.io/Yeni-oyun/` adresini aç.
- **Yerel**: `python3 -m http.server 8000`. Telefondan erişmek için HTTPS tüneli gerekir (ör. `npx localtunnel --port 8000` ya da `ngrok`).

## Android uygulaması (native)
`android/` klasöründe Kotlin ile yazılmış native sürüm var. Web sürümüne göre farkları:
- Nesne tanıma **MediaPipe + EfficientDet-Lite0** ile telefonun GPU'sunda çalışır. TensorFlow.js'ten belirgin şekilde hızlıdır.
- Kamera **CameraX** ile ana arka lensi doğrudan kullanır. Tarayıcı kısıtlamaları yoktur.
- Sesler ve titreşim yerel olarak çalınır.
- **Her şey kırılabilir:** vurulan noktadaki nesne MediaPipe Interactive Segmenter ile tam silüetiyle ayrılır,
  yeri çevresinden doldurularak silinir.
- **Aletler:** ⚽ top (seçili nesneler arasında seker), 🪨 taş, 🏹 sapan (geri çek-bırak, nişan yolu görünür),
  🏏 sopa ve 🔧 İngiliz anahtarı (yakın dövüş).
- **🎯 Seç:** dokunduğun nesneleri hedef olarak işaretler (uzun basmak da seçer ve odaklar).
- **⬚ Çiz:** kırmak istediğin nesnenin etrafına parmakla kare/dikdörtgen çiz; birden fazla kutu çizilebilir.
- **🔍 Tara:** ekranı yüksek çözünürlükte parça parça tarar, kırılabilir eşyaları bulup seçer.

**Kurulum:** Her push'ta GitHub Actions APK'yı derler ve *Releases* sayfasına koyar. Telefondan
https://github.com/Skyprus/Yeni-oyun/releases/latest adresine gir, `KirGec.apk`'yı indirip aç.
İlk seferde "bilinmeyen kaynaklardan yükleme" izni istenir.

APK, repodaki test anahtarıyla (`android/app/debug.keystore`) imzalanır. Böylece yeni sürüm eskisinin
üzerine kurulabilir. Play Store'a yüklemek için ayrı, gizli bir anahtar gerekir.

Android Studio ile açmak için `android/` klasörünü aç.

## Sonraki adımlar (fikirler)
- Gerçek 3D AR (WebXR hit-test) ile topun duvara/zemine çarpması
- Daha fazla nesne türü için özel eğitilmiş model (avize, ayna, cam, tabak)
- Seviyeler, süre sınırı, kombo puanı, skor tablosu
