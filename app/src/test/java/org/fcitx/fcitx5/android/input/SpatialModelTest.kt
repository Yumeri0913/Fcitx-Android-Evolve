/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.input.keyboard.SpatialModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sqrt

class SpatialModelTest {

    // 420 dpi phone, 108px x 160px keys (6.53mm x 9.68mm), staggered QWERTY
    private val dpi = 420f
    private val w = 108f
    private val h = 160f

    private val keys = buildList {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
            .forEachIndexed { row, (letters, offset) ->
                letters.forEachIndexed { i, c ->
                    val left = (offset + i) * w
                    add(SpatialModel.Key(c, left, row * h, left + w, (row + 1) * h))
                }
            }
    }
    private val model = SpatialModel(keys, dpi, dpi)
    private fun key(c: Char) = keys.first { it.char == c }

    @Test
    fun scoreIsGoogleGaussian() {
        val k = key('g')
        val wMm = w * 25.4f / dpi
        val hMm = h * 25.4f / dpi
        val varX = 0.0075f * (1.5f * wMm) * (1.5f * wMm) + 1.68f
        val varY = 0.0108f * (1.5f * hMm) * (1.5f * hMm) + 1.33f
        val peak = ln(1.0 / (2 * PI * sqrt((varX * varY).toDouble()))).toFloat()
        assertEquals(peak, model.score(k, k.centerX, k.centerY), 1e-4f)
        val dxMm = 2f
        val x = k.centerX + dxMm * dpi / 25.4f
        assertEquals(peak - 0.5f * dxMm * dxMm / varX, model.score(k, x, k.centerY), 1e-3f)
    }

    @Test
    fun touchAtCenterRanksTouchedKeyFarAhead() {
        val k = key('g')
        val alt = model.alternatives('g', k.centerX, k.centerY)!!
        assertEquals('g', alt.keys[0])
        // at the key center, neighbors are more than 1000x less likely
        assertTrue(alt.keys.length == 1 || alt.scores[0] - alt.scores[1] > ln(1000f))
    }

    @Test
    fun touchOnBorderMakesNeighborAsLikely() {
        val g = key('g')
        val alt = model.alternatives('g', g.right - 1f, g.centerY)!!
        assertEquals('g', alt.keys[0])
        assertEquals('h', alt.keys[1])
        assertEquals(alt.scores[0], alt.scores[1], 0.2f)
        assertTrue(alt.scores.toList().zipWithNext().drop(1).all { (a, b) -> a >= b })
    }

    @Test
    fun verticalNeighborsAreIncluded() {
        // bottom edge of 'u' is next to 'h' and 'j'
        val u = key('u')
        val alt = model.alternatives('u', u.centerX, u.bottom - 1f)!!
        assertTrue(alt.keys.toString(), 'j' in alt.keys || 'h' in alt.keys)
        // far keys are never considered
        assertFalse('m' in alt.keys)
        assertFalse('p' in alt.keys)
    }

    @Test
    fun unknownKeyGivesNothing() {
        assertNull(model.alternatives('1', 0f, 0f))
    }
}
