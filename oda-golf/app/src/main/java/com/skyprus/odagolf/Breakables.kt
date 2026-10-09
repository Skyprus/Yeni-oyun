package com.skyprus.odagolf

enum class Material { GLASS, CERAMIC, ELECTRONIC, VEHICLE }

data class Breakable(val label: String, val hp: Int, val points: Int, val material: Material)

/** Modelin (COCO etiketleri) tanıdığı nesnelerden kırılabilir sayılanlar. */
val BREAKABLES: Map<String, Breakable> = mapOf(
    "bottle" to Breakable("Şişe", 1, 100, Material.GLASS),
    "wine glass" to Breakable("Kadeh", 1, 150, Material.GLASS),
    "cup" to Breakable("Bardak", 1, 80, Material.CERAMIC),
    "vase" to Breakable("Vazo", 1, 200, Material.CERAMIC),
    "bowl" to Breakable("Kase", 1, 80, Material.CERAMIC),
    "clock" to Breakable("Saat", 2, 180, Material.GLASS),
    "potted plant" to Breakable("Saksı", 2, 120, Material.CERAMIC),
    "tv" to Breakable("Televizyon", 3, 400, Material.ELECTRONIC),
    "laptop" to Breakable("Laptop", 2, 300, Material.ELECTRONIC),
    "cell phone" to Breakable("Telefon", 1, 150, Material.ELECTRONIC),
    "mouse" to Breakable("Fare", 1, 60, Material.ELECTRONIC),
    "remote" to Breakable("Kumanda", 1, 60, Material.ELECTRONIC),
    "keyboard" to Breakable("Klavye", 2, 100, Material.ELECTRONIC),
    "microwave" to Breakable("Mikrodalga", 3, 300, Material.ELECTRONIC),
    "toaster" to Breakable("Tost Mak.", 2, 200, Material.ELECTRONIC),
    // Araçlar: sağlam; molotof ya da kaya ile tek seferde gider
    "car" to Breakable("Araba", 3, 500, Material.VEHICLE),
    "truck" to Breakable("Kamyon", 4, 700, Material.VEHICLE),
    "bus" to Breakable("Otobüs", 4, 800, Material.VEHICLE),
    "motorcycle" to Breakable("Motosiklet", 2, 300, Material.VEHICLE),
    "bicycle" to Breakable("Bisiklet", 1, 150, Material.VEHICLE),
)

/** Kırılamayan ama sık görülen nesnelerin Türkçe adları (tanı satırı için). */
val OTHER_LABELS: Map<String, String> = mapOf(
    "person" to "insan", "chair" to "sandalye", "couch" to "kanepe", "bed" to "yatak",
    "dining table" to "masa", "book" to "kitap", "refrigerator" to "buzdolabı", "sink" to "lavabo",
    "oven" to "fırın", "dog" to "köpek", "cat" to "kedi", "backpack" to "sırt çantası",
    "handbag" to "çanta", "umbrella" to "şemsiye", "teddy bear" to "oyuncak ayı", "scissors" to "makas",
    "knife" to "bıçak", "spoon" to "kaşık", "fork" to "çatal", "toilet" to "klozet", "car" to "araba",
    "bicycle" to "bisiklet", "bench" to "bank", "suitcase" to "bavul", "banana" to "muz", "apple" to "elma",
    "orange" to "portakal", "sports ball" to "top",
)

/** Tanınmayan ama dokunarak/vurarak kırılan herhangi bir nesne. */
val GENERIC_OBJECT = Breakable("Nesne", 1, 50, Material.CERAMIC)

/**
 * Kırma aletleri. Fırlatılanlar (top, taş, sapan taşı) hedefe uçar; top seçili nesneler arasında seker; yakın dövüş aletleri (sopa, İngiliz anahtarı)
 * dokunulan noktaya savrulur.
 */
enum class Weapon(
    val label: String,
    val thrown: Boolean,
    val radiusDp: Float,
    val duration: Float,
    val power: Float,
    val damage: Int,
    val hitPadDp: Float,
    /** Gökten düşer (dokunulan noktaya). */
    val drop: Boolean = false,
) {
    BALL("Top", true, 34f, 0.55f, 0.8f, 1, 22f),
    STONE("Taş", true, 22f, 0.38f, 1.25f, 2, 8f),
    SLING("Sapan", true, 13f, 0.42f, 1.35f, 2, 8f),
    BAT("Sopa", false, 0f, 0.26f, 1.5f, 2, 16f),
    WRENCH("İngiliz anahtarı", false, 0f, 0.22f, 1.1f, 3, 10f),
    MOLOTOV("Molotof", true, 22f, 0.75f, 1.8f, 99, 24f),
    BOULDER("Kaya", false, 0f, 0.85f, 2.2f, 99, 30f, drop = true),
}
