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
}
