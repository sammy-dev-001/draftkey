package com.draftkeys.keyboard.gesture

import com.draftkeys.keyboard.ui.KeyModel
import com.draftkeys.keyboard.ui.KeyboardLayout
import kotlin.math.exp

/**
 * SpatialModel calculates the probability that a given touch coordinate (x, y)
 * was intended for a specific key, using a 2D Gaussian distribution centered
 * on each key.
 *
 * This replaces strict point-in-rectangle hit testing for predictive text,
 * allowing the engine to gracefully handle "fat-finger" typos.
 */
class SpatialModel(private val layout: KeyboardLayout) {

    /**
     * Calculates the probability of each letter key being the intended target
     * for a touch at (x, y). Returns a map of character to probability [0.0, 1.0].
     *
     * We only calculate probabilities for alphabetical keys, as modifiers
     * (shift, backspace, space) are still resolved via strict bounds checks.
     */
    fun getProbabilities(x: Float, y: Float): Map<Char, Double> {
        val probabilities = mutableMapOf<Char, Double>()
        var sum = 0.0

        for (key in layout.keys) {
            // Only care about lowercase alphabetical keys
            if (key.code >= 'a'.code && key.code <= 'z'.code) {
                val p = calculateGaussianProbability(x, y, key)
                if (p > 0.001) { // Prune extremely unlikely keys
                    probabilities[key.code.toChar()] = p
                    sum += p
                }
            }
        }

        // Normalize probabilities so they sum to 1.0
        if (sum > 0.0) {
            for ((char, p) in probabilities.entries) {
                probabilities[char] = p / sum
            }
        } else {
            // Fallback if tap is extremely far from any key
            val nearest = layout.findNearestKey(x, y)
            if (nearest != null && nearest.code >= 'a'.code && nearest.code <= 'z'.code) {
                probabilities[nearest.code.toChar()] = 1.0
            }
        }

        return probabilities
    }

    private fun calculateGaussianProbability(x: Float, y: Float, key: KeyModel): Double {
        val cx = key.bounds.centerX()
        val cy = key.bounds.centerY()
        
        // Variance depends on key size. A standard deviation of ~70% of the key radius
        // gives a good spread that covers adjacent keys.
        val sigmaX = key.bounds.width() * 0.7f
        val sigmaY = key.bounds.height() * 0.7f

        val dx = x - cx
        val dy = y - cy

        val exponent = -((dx * dx) / (2 * sigmaX * sigmaX) + (dy * dy) / (2 * sigmaY * sigmaY))
        return exp(exponent.toDouble())
    }
}
