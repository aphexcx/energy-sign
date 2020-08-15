package cx.aphex.energysign.beatlinkdata

data class BeatLinkTrack(
    val id: Int,
    val title: String,
    val artist: String,
    val isEmpty: Boolean = false,
    val isId: Boolean = false
)
