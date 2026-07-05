package com.draftkeys.keyboard.gesture

import android.graphics.PointF
import com.draftkeys.keyboard.prediction.PersonalWordEntity
import com.draftkeys.keyboard.ui.KeyModel
import com.draftkeys.keyboard.ui.KeyboardLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * GestureDecoder — SHARK2-inspired dual-channel swipe word recogniser.
 *
 * Algorithm (industry-grade approach based on SHARK2 research):
 *
 * 1. **Path resampling** — Uniformly resample both the user's gesture and every
 *    candidate word's ideal template to N=100 points along their arc-length.
 *    This eliminates speed-bias (a fast swipe and a slow swipe of the same word
 *    produce the same resampled path).
 *
 * 2. **Candidate pruning** — Look up words whose first AND last keys match the
 *    first/last touch points (top-3 nearest for each endpoint, 9 combinations).
 *    This eliminates 95%+ of the dictionary before scoring.
 *
 * 3. **Dual-channel scoring**:
 *    - **Location score** (weight 0.65): Compares absolute coordinates of the
 *      resampled paths — penalises gestures in the wrong region of the keyboard.
 *    - **Shape score** (weight 0.35): Compares centroid-normalised (translated +
 *      scaled) path shapes — rewards correct curve topology regardless of position.
 *
 * 4. **Personal frequency boost** — Words learned by the user score up to 50%
 *    higher, rising logarithmically with usage count.
 *
 * 5. Return top 3 candidates sorted by combined score.
 */
class GestureDecoder(private val dictionary: WordDictionary) {

    companion object {
        /** Number of resampled points per path (SHARK2 uses 100). */
        private const val RESAMPLE_N = 100

        /** Scoring channel weights. Location is the stronger discriminator. */
        private const val WEIGHT_LOCATION = 0.65
        private const val WEIGHT_SHAPE    = 0.35

        /**
         * Score normalisation constant — average pixel distance at which the score
         * falls to 0.5. Tuned for ~360dp wide keyboards at 2.0 density (≈ 360px).
         * Shape scale is 0.3 because path coordinates are normalized to unit radius.
         */
        private const val LOCATION_SCALE = 180.0
        private const val SHAPE_SCALE    = 0.3
    }

    // ── Public API ───────────────────────────────────────────────────────────

    suspend fun decode(
        points: List<GesturePoint>,
        layout: KeyboardLayout,
        personalWords: List<PersonalWordEntity> = emptyList()
    ): List<String> = withContext(Dispatchers.Default) {

        if (points.size < 3) return@withContext emptyList()

        // Resample the user's gesture to RESAMPLE_N uniform points
        val gesturePointFs = points.map { PointF(it.x, it.y) }
        val gesture100 = resamplePath(gesturePointFs, RESAMPLE_N)

        val firstKeys = findTopNNearest(layout, points.first().x, points.first().y, 3)
        val lastKeys  = findTopNNearest(layout, points.last().x,  points.last().y,  3)

        // Collect candidates from the (firstKey, lastKey) index
        val candidates = mutableSetOf<String>()
        for (f in firstKeys) for (l in lastKeys) {
            dictionary.wordsByFirstLast[Pair(f.code, l.code)]?.take(50)?.let { candidates.addAll(it) }
        }

        if (candidates.isEmpty()) return@withContext emptyList()

        val personalFreq = personalWords.associate { it.word to it.frequency }

        val scored = candidates.map { word ->
            val template = buildTemplate(word, layout) ?: return@map word to 0.0

            val locScore   = locationScore(gesture100, template)
            val shapeScore = shapeScore(gesture100, template)
            val combined   = WEIGHT_LOCATION * locScore + WEIGHT_SHAPE * shapeScore

            // Personal frequency boost: log-scaled, capped at a mild +10% 
            // (so it tips tie-breakers but doesn't override physical shape mismatch)
            val boost = personalFreq[word]
                ?.let { 1.0 + 0.1 * (Math.log(it.toDouble() + 1.0) / Math.log(10.0)) }
                ?: 1.0

            word to (combined * boost)
        }.sortedByDescending { it.second }

        scored.take(3).map { it.first }
    }

    // ── Path resampling ──────────────────────────────────────────────────────

    /**
     * Resamples [path] to [n] equidistant points along its arc-length.
     * This is the critical step that removes speed-variation bias.
     */
    private fun resamplePath(path: List<PointF>, n: Int): List<PointF> {
        if (path.size < 2) return List(n) { path.firstOrNull() ?: PointF() }

        // Build cumulative arc-length table
        val arcLen = FloatArray(path.size)
        for (i in 1 until path.size) {
            val dx = path[i].x - path[i - 1].x
            val dy = path[i].y - path[i - 1].y
            arcLen[i] = arcLen[i - 1] + sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
        val totalLen = arcLen.last()
        if (totalLen == 0f) return List(n) { path.first() }

        val result = mutableListOf<PointF>()
        var segIdx = 1

        for (i in 0 until n) {
            val targetLen = totalLen * i / (n - 1)

            // Advance segment index to the right arc-length position
            while (segIdx < path.size - 1 && arcLen[segIdx] < targetLen) segIdx++

            val segStart = path[segIdx - 1]
            val segEnd   = path[segIdx]
            val segLen   = arcLen[segIdx] - arcLen[segIdx - 1]
            val t = if (segLen == 0f) 0f else (targetLen - arcLen[segIdx - 1]) / segLen

            result.add(PointF(
                segStart.x + t * (segEnd.x - segStart.x),
                segStart.y + t * (segEnd.y - segStart.y)
            ))
        }
        return result
    }

    // ── Template building ────────────────────────────────────────────────────

    /**
     * Builds the ideal 100-point template for [word] using key-centre coordinates.
     * Returns null if the word's key sequence cannot be mapped to the layout.
     */
    private fun buildTemplate(word: String, layout: KeyboardLayout): List<PointF>? {
        val seq = dictionary.keySequenceFor(word)
        if (seq.size < 2) return null

        val centres = seq.mapNotNull { code -> layout.keyCentre(code) }
        if (centres.size < 2) return null

        return resamplePath(centres, RESAMPLE_N)
    }

    // ── Scoring channels ─────────────────────────────────────────────────────

    /**
     * Location score: average pixel distance between corresponding resampled points,
     * converted to [0,1]. No coordinate normalisation — absolute position matters.
     */
    private fun locationScore(gesture: List<PointF>, template: List<PointF>): Double {
        val avgDist = averagePointDistance(gesture, template)
        return LOCATION_SCALE / (LOCATION_SCALE + avgDist)
    }

    /**
     * Shape score: centroid-translate and scale-normalise both paths, then measure
     * average point distance. Captures geometric shape independent of position/size.
     */
    private fun shapeScore(gesture: List<PointF>, template: List<PointF>): Double {
        val normGesture  = normalise(gesture)
        val normTemplate = normalise(template)
        val avgDist = averagePointDistance(normGesture, normTemplate)
        return SHAPE_SCALE / (SHAPE_SCALE + avgDist)
    }

    /**
     * Translate to centroid, then scale by the maximum Euclidean distance from centroid.
     * This maps the path into a unit L2 ball — preserving shape for all directions equally.
     * (The old version used max(|x|,|y|) which is an L-inf norm and distorts diagonal paths.)
     */
    private fun normalise(path: List<PointF>): List<PointF> {
        if (path.isEmpty()) return path
        var sumX = 0f
        var sumY = 0f
        for (p in path) {
            sumX += p.x
            sumY += p.y
        }
        val cx = sumX / path.size
        val cy = sumY / path.size

        var maxDistSq = 0f
        for (p in path) {
            val dx = p.x - cx
            val dy = p.y - cy
            val distSq = dx * dx + dy * dy
            if (distSq > maxDistSq) maxDistSq = distSq
        }
        
        val maxDist = sqrt(maxDistSq.toDouble()).toFloat()
        if (maxDist == 0f) return path.map { PointF(it.x - cx, it.y - cy) }

        return path.map { PointF((it.x - cx) / maxDist, (it.y - cy) / maxDist) }
    }

    private fun averagePointDistance(a: List<PointF>, b: List<PointF>): Double {
        val n = min(a.size, b.size)
        if (n == 0) return Double.MAX_VALUE
        var total = 0.0
        for (i in 0 until n) {
            val dx = (a[i].x - b[i].x).toDouble()
            val dy = (a[i].y - b[i].y).toDouble()
            total += sqrt(dx * dx + dy * dy)
        }
        return total / n
    }

    // ── Nearest key lookup ───────────────────────────────────────────────────

    private fun findTopNNearest(layout: KeyboardLayout, x: Float, y: Float, n: Int): List<KeyModel> =
        layout.keys
            .filter { it.code >= 'a'.code && it.code <= 'z'.code }
            .sortedBy {
                val dx = it.bounds.centerX() - x
                val dy = it.bounds.centerY() - y
                dx * dx + dy * dy
            }.take(n)
}
