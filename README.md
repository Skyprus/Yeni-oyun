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
- Kırılan eşya 6 saniye "yok" görünür, sonra yeniden hedef olur.

## Nasıl çalışır
| Parça | Teknoloji |
|---|---|
| Kamera | `getUserMedia` (arka kamera) |
| Nesne tanıma | TensorFlow.js + COCO-SSD (`lite_mobilenet_v2`), tamamen telefonda çalışır |
| Kırılma efekti | Nesnenin o anki kamera görüntüsü kesilir, çarpma noktasından radyal parçalara bölünür, fizikle saçılır. Nesnenin yeri zemin rengiyle örtülür ve altına enkaz çizilir. |
| Ses | WebAudio ile sentezlenen cam/elektronik kırılma sesleri, titreşim |

Dosyalar: `index.html`, `style.css`, `js/main.js` (oyun döngüsü, takip, atış),
`js/shatter.js` (parçalanma), `js/audio.js` (sesler).

## Çalıştırma
Kamera izni yalnızca **HTTPS** (veya `localhost`) üzerinde çalışır.

- **GitHub Pages**: Repo ayarlarında *Pages → Deploy from branch* seç. Telefondan `https://<kullanıcı>.github.io/Yeni-oyun/` adresini aç.
- **Yerel**: `python3 -m http.server 8000`. Telefondan erişmek için HTTPS tüneli gerekir (ör. `npx localtunnel --port 8000` ya da `ngrok`).

## Sonraki adımlar (fikirler)
- Gerçek 3D AR (WebXR hit-test) ile topun duvara/zemine çarpması
- Daha fazla nesne türü için özel eğitilmiş model (avize, ayna, cam, tabak)
- Seviyeler, süre sınırı, kombo puanı, skor tablosu
