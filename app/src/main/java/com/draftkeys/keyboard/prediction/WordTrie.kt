package com.draftkeys.keyboard.prediction

class TrieNode(val char: Char) {
    val children = mutableMapOf<Char, TrieNode>()
    var isWord: Boolean = false
    var probability: Double = 0.0 // Base language model probability
}

class WordTrie {
    val root = TrieNode('\u0000')

    /**
     * Inserts a word into the Trie along with its frequency/probability.
     */
    fun insert(word: String, prob: Double) {
        var current = root
        for (char in word) {
            val c = char.lowercaseChar()
            if (!current.children.containsKey(c)) {
                current.children[c] = TrieNode(c)
            }
            current = current.children[c]!!
        }
        current.isWord = true
        current.probability = prob
    }

    /**
     * BFS-collect up to [limit] words reachable from [prefix], sorted by probability descending.
     *
     * Complexity: O(k + results) where k = prefix length.
     * This replaces the O(N) linear filter across the full word list.
     *
     * Uses a best-first BFS: children are visited in probability order so the
     * most likely completions surface without scanning the entire sub-tree.
     */
    fun getCompletions(prefix: String, limit: Int): List<Pair<String, Double>> {
        // Walk to the prefix node
        var node = root
        for (ch in prefix.lowercase()) {
            node = node.children[ch] ?: return emptyList()
        }

        val result = mutableListOf<Pair<String, Double>>()
        // Queue holds (node, wordSoFar) — BFS
        val queue = ArrayDeque<Pair<TrieNode, String>>()
        queue.addLast(node to prefix)

        while (queue.isNotEmpty() && result.size < limit * 4) {
            val (cur, word) = queue.removeFirst()
            if (cur.isWord) result.add(word to cur.probability)
            // Visit children sorted by probability descending (best-first)
            cur.children.entries
                .sortedByDescending { it.value.probability }
                .forEach { (ch, child) -> queue.addLast(child to (word + ch)) }
        }

        return result.sortedByDescending { it.second }.take(limit)
    }
}
