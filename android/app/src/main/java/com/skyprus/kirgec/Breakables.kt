package com.skyprus.kirgec

enum class Material { GLASS, CERAMIC, ELECTRONIC }

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
)

val MANUAL_TARGET = Breakable("Hedef", 2, 150, Material.GLASS)

enum class Ammo(
    val radiusDp: Float,
    val duration: Float,
    val power: Float,
    val damage: Int,
    val hitPadDp: Float,
) {
    BALL(34f, 0.55f, 0.8f, 1, 22f),
    STONE(22f, 0.38f, 1.25f, 2, 8f),
}
