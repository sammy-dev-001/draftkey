package com.draftkeys.keyboard.gesture

import android.graphics.PointF
import com.draftkeys.keyboard.prediction.PersonalWordEntity
import com.draftkeys.keyboard.prediction.TrieNode
import com.draftkeys.keyboard.prediction.WordTrie
import com.draftkeys.keyboard.ui.KeyboardLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Probabilistic GestureDecoder (Beam Search / Viterbi approach).
 *
 * This completely replaces the old SHARK2 shape-matching decoder.
 * Instead of rigid first/last key constraints, this evaluates the swipe
 * probabilistically against the dictionary Trie using Spatial Models.
 */
class GestureDecoder(private val dictionary: WordDictionary) {

    companion object {
        private const val RESAMPLE_N = 60
        private const val BEAM_WIDTH = 500
    }

    private data class BeamNode(
        val trieNode: TrieNode,
        val wordSoFar: String,
        val score: Double // Distance-based score (lower is better)
    )

    suspend fun decode(
        points: List<GesturePoint>,
        layout: KeyboardLayout,
        personalWords: List<PersonalWordEntity> = emptyList()
    ): List<String> = withContext(Dispatchers.Default) {

        if (points.size < 3) return@withContext emptyList()

        val gesturePointFs = points.map { PointF(it.x, it.y) }
        val resampled = resamplePath(gesturePointFs, RESAMPLE_N)

        // Precompute key centers
        val keyCenters = mutableMapOf<Char, PointF>()
        for (key in layout.keys) {
            if (key.code in 'a'.code..'z'.code) {
                keyCenters[key.code.toChar()] = PointF(key.bounds.centerX(), key.bounds.centerY())
            }
        }
        
        val keySizeAvg = layout.keys.firstOrNull { it.code == 'a'.code }?.bounds?.width() ?: 100f

        // Initialize beam with the root node
        var beam = listOf(BeamNode(dictionary.trie.root, "", 0.0))

        for (pt in resampled) {
            val newBeamMap = mutableMapOf<TrieNode, BeamNode>()

            for (node in beam) {
                // 1. Stay on current node (if not root)
                if (node.trieNode != dictionary.trie.root) {
                    val center = keyCenters[node.trieNode.char]
                    val d2 = if (center != null) distanceSq(pt, center) else 10000.0
                    // Normalize distance by key size squared to make it layout-independent
                    val normalizedD2 = d2 / (keySizeAvg * keySizeAvg)
                    
                    val newScore = node.score + normalizedD2 * 0.4 // Stay penalty is cheaper
                    
                    val existing = newBeamMap[node.trieNode]
                    if (existing == null || newScore < existing.score) {
                        newBeamMap[node.trieNode] = BeamNode(node.trieNode, node.wordSoFar, newScore)
                    }
                }

                // 2. Advance to children
                for ((char, childNode) in node.trieNode.children) {
                    val center = keyCenters[char]
                    val d2 = if (center != null) distanceSq(pt, center) else 10000.0
                    val normalizedD2 = d2 / (keySizeAvg * keySizeAvg)
                    
                    val newScore = node.score + normalizedD2
                    
                    val existing = newBeamMap[childNode]
                    if (existing == null || newScore < existing.score) {
                        newBeamMap[childNode] = BeamNode(childNode, node.wordSoFar + char, newScore)
                    }
                }
            }

            // Prune beam
            beam = newBeamMap.values
                .sortedBy { it.score }
                .take(BEAM_WIDTH)
        }

        // Extract valid words from the final beam
        val personalFreq = personalWords.associate { it.word to it.frequency }
        val lastPoint = resampled.last()

        val results = beam
            .filter { it.trieNode.isWord }
            .map { node ->
                val lmProb = node.trieNode.probability // Static language model prob
                val pBoost = personalFreq[node.wordSoFar]?.let { 1.0 + kotlin.math.ln(it + 1.0) } ?: 1.0
                
                // Add a penalty if the final gesture point is far from the word's last letter
                val lastCenter = keyCenters[node.wordSoFar.last()]
                val endPenalty = if (lastCenter != null) distanceSq(lastPoint, lastCenter) / (keySizeAvg * keySizeAvg) else 10.0
                
                // Final score: combine path distance (normalized by length), end penalty, and language model
                // Lower is better.
                // LM prob is in [0, 1], so -ln(prob) is positive and acts as a distance penalty
                val lmPenalty = -kotlin.math.ln(max(lmProb, 0.000001)) - kotlin.math.ln(pBoost)
                
                val finalScore = (node.score / RESAMPLE_N) + (endPenalty * 2.0) + (lmPenalty * 0.1)
                node.wordSoFar to finalScore
            }
            .sortedBy { it.second } // Sort ascending (lower score is better)
            .distinctBy { it.first }
            .take(3)
            .map { it.first }

        results
    }

    private fun distanceSq(p1: PointF, p2: PointF): Double {
        val dx = (p1.x - p2.x).toDouble()
        val dy = (p1.y - p2.y).toDouble()
        return dx * dx + dy * dy
    }

    private fun resamplePath(path: List<PointF>, n: Int): List<PointF> {
        if (path.size < 2) return List(n) { path.firstOrNull() ?: PointF() }

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
}
