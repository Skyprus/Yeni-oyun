// Nesne tanıma ayrı bir iş parçacığında çalışır; böylece model hesap yaparken
// oyun animasyonu ve kamera görüntüsü takılmaz.
importScripts(
  'https://cdn.jsdelivr.net/npm/@tensorflow/tfjs@4.22.0/dist/tf.min.js',
  'https://cdn.jsdelivr.net/npm/@tensorflow-models/coco-ssd@2.2.3/dist/coco-ssd.min.js',
);

let model = null;

async function init() {
  try {
    // WebGL için OffscreenCanvas gerekir; yoksa CPU'ya düş (yavaş ama arayüzü kilitlemez)
    if (!(typeof OffscreenCanvas !== 'undefined' && (await tf.setBackend('webgl')))) {
      await tf.setBackend('cpu');
    }
    await tf.ready();
    model = await cocoSsd.load({ base: 'lite_mobilenet_v2' });
    // Isınma: ilk tahmin shader derlemesi yüzünden yavaştır
    await model.detect(new ImageData(64, 64));
    postMessage({ type: 'ready', backend: tf.getBackend() });
  } catch (e) {
    postMessage({ type: 'error', message: String(e && e.message || e) });
  }
}

onmessage = async (e) => {
  const msg = e.data;
  if (msg.type === 'init') return init();
  if (msg.type === 'detect') {
    const t0 = performance.now();
    let preds = [];
    try {
      preds = await model.detect(msg.bitmap, 20, 0.4);
    } catch (_) { /* tek kare hatası önemsiz */ }
    msg.bitmap.close();
    postMessage({ type: 'result', preds, ms: performance.now() - t0 });
  }
};
