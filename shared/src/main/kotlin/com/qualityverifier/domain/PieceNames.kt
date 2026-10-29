package com.qualityverifier.domain

/**
 * The one bound on a piece's name, the same on the phone as in the server's V22 CHECK.
 * Counted in code points, as Postgres counts characters, so a name of accented or
 * supplementary characters is measured the same way at both ends.
 */
object PieceNames {
    const val MAX_LENGTH = 80

    fun fits(name: String): Boolean = name.codePointCount(0, name.length) <= MAX_LENGTH
}
