/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.KeyAlternatives
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Touch spatial model, the same one Google Pinyin 4.x uses for its "intelligent correction":
 * every key is a 2D Gaussian centered on its rectangle, with a variance that grows with key size.
 * For one touch, the touched key and the nearby keys are scored by the log density at the touch
 * point. The scores go to the pinyin decoder, which uses them to price keyboard corrections.
 *
 * One change from Google: the vertical variance grows at least with the key aspect ratio
 * (`varY >= varX * (h / w)²`). Google's formula fits keys about as tall as wide; for our keys,
 * about 1.4 times as tall, it made a touch some way into the row above or below cost much more
 * than the same miss into a left or right neighbor, so vertical typos were rarely corrected.
 *
 * All coordinates are in pixels of the same view; [xdpi]/[ydpi] convert them to millimeters.
 */
class SpatialModel(
    private val keys: List<Key>,
    private val xdpi: Float,
    private val ydpi: Float
) {

    data class Key(
        val char: Char,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width get() = right - left
        val height get() = bottom - top
        val centerX get() = (left + right) / 2f
        val centerY get() = (top + bottom) / 2f

        /** Distance (px) from a point to this rectangle, 0 if inside */
        fun distanceTo(x: Float, y: Float): Float {
            val dx = max(max(left - x, 0f), x - right)
            val dy = max(max(top - y, 0f), y - bottom)
            return sqrt(dx * dx + dy * dy)
        }
    }

    /**
     * Neighborhood radius (px): the smaller of the most common key width and height.
     */
    private val radius: Float = run {
        fun mostCommon(values: List<Float>): Float =
            values.groupingBy { it.toInt() }.eachCount().maxByOrNull { it.value }?.key?.toFloat()
                ?: 0f
        min(mostCommon(keys.map { it.width }), mostCommon(keys.map { it.height }))
    }

    private fun pxToMmX(px: Float) = px * MM_PER_INCH / xdpi
    private fun pxToMmY(px: Float) = px * MM_PER_INCH / ydpi

    /** Natural log of the key's Gaussian density at (x, y) */
    fun score(key: Key, x: Float, y: Float): Float {
        val w = pxToMmX(key.width)
        val h = pxToMmY(key.height)
        val varX = 0.0075f * (1.5f * w) * (1.5f * w) + 1.68f
        val varY = max(0.0108f * (1.5f * h) * (1.5f * h) + 1.33f, varX * (h / w) * (h / w))
        val dx = pxToMmX(x - key.centerX)
        val dy = pxToMmY(y - key.centerY)
        val norm = ln(1.0 / (2.0 * PI * sqrt((varX * varY).toDouble()))).toFloat()
        return norm - 0.5f * (dx * dx / varX + dy * dy / varY)
    }

    /**
     * Keys the touch at (x, y) on [touched] may have meant: [touched] first, then neighbors whose
     * rectangle is within [radius] of the touch point and whose center is within 1.2 × [radius]
     * of the touched key, sorted by score descending.
     */
    fun alternatives(touched: Char, x: Float, y: Float): KeyAlternatives? {
        val touchedKey = keys.find { it.char == touched } ?: return null
        val neighbors = keys.asSequence()
            .filter { it.char != touched }
            .filter { touchedKey.distanceTo(it.centerX, it.centerY) <= NEIGHBOR_FACTOR * radius }
            .filter { it.distanceTo(x, y) <= radius }
            .map { it.char to score(it, x, y) }
            .sortedByDescending { it.second }
            .toList()
        val all = listOf(touched to score(touchedKey, x, y)) + neighbors
        return KeyAlternatives(
            all.map { it.first }.joinToString(""),
            all.map { it.second }.toFloatArray()
        )
    }

    companion object {
        private const val MM_PER_INCH = 25.4f
        private const val NEIGHBOR_FACTOR = 1.2f
    }
}
