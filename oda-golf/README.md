# Oda Golf ⛳

Odanda mini golf: telefonun kamerasını odaya çevir, ekrana dokun. Görüntü donar, odadaki
eşyalar (sandalye, şişe, kanepe, TV…) gerçek şekilleriyle **engele** dönüşür. Topu eşyalardan ve
duvarlardan **sektirerek** ya da koyduğun rampalarla **yönlendirerek** deliğe sok.

## Nasıl oynanır
- **Vuruş:** parmağını topun gideceği yönün tersine çek, bırak. Ne kadar çekersen o kadar güçlü.
  Nişan alırken top yolunun ilk sekmeye kadarki kısmı görünür.
- **Eşyalar:** yumuşak olanlar (kanepe, yatak, çanta) topu yavaşlatır, sert olanlar (TV, şişe, buzdolabı)
  güçlü sektirir. Renkleri: yeşil yumuşak, sarı orta, mavi sert.
- **📐 Rampa:** eğik tahta koy (ilk 3 delikte 2, sonra 1 hak). Boş yere dokun → koy, sürükle → taşı,
  ucunu çevir → döndür, üstüne dokun → kaldır.
- **➕ Eşya:** tanınmayan bir eşyaya dokun → engel olsun (ya da kalksın); etrafına kutu da çizebilirsin.
- **⭐ Yıldız:** her delikte yoldan biraz uzakta bir yıldız var; topla.
- **📸 Oda:** başka bir oda/açı. **🔄 Delik:** başka delik (vuruş yaptıysan +1 ceza).
- **Parkur:** 9 delik, gittikçe zorlaşır. Sonunda toplam skor, yıldızlar ve en iyi skor.

## Kurulum
Her push'ta GitHub Actions APK'yı derler ve **Releases** sayfasına koyar. Telefondan reponun
`releases/latest` sayfasına gir, `OdaGolf.apk`'yı indirip aç. İlk seferde "bilinmeyen kaynaklardan
yükleme" izni istenir.

## Teknik
Kotlin, CameraX, MediaPipe (EfficientDet-Lite2 ile eşya bulma, Interactive Segmenter ile silüet).
Hepsi telefonda çalışır; görüntü dışarı gönderilmez. Kod: `app/src/main/java/com/skyprus/odagolf/`
(`HoleView.kt` oyun, `MainActivity.kt` kamera).
