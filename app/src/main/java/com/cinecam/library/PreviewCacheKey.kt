package com.cinecam.library

/** Cache identity for a decoded preview. Every source and calibration input matters:
 *  CCRAW targets have no DNG URI, so omitting the container URI reused another clip's frames. */
internal object PreviewCacheKey {
    fun of(dngUri: String?, ccrawUri: String?, frameIndex: Int, maxDimension: Int,
           cfa: Int, matrixMode: Int, white: Double): String =
        "${dngUri ?: "-"}|${ccrawUri ?: "-"}|$frameIndex|$cfa|$matrixMode|${white.toRawBits()}|$maxDimension"
}
