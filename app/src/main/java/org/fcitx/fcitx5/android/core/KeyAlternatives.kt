/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

/**
 * Keys a touch may have meant, from the touch spatial model.
 * [keys][0] is the key actually typed; [scores] are natural log likelihoods, one per key.
 */
class KeyAlternatives(val keys: String, val scores: FloatArray) {
    init {
        require(keys.length == scores.size)
    }

    fun lowercase() = KeyAlternatives(keys.lowercase(), scores)

    override fun toString() =
        keys.indices.joinToString(prefix = "KeyAlternatives(", postfix = ")") {
            "${keys[it]}=${"%.2f".format(scores[it])}"
        }
}
