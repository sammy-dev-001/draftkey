package com.draftkeys.keyboard.prediction

import com.draftkeys.keyboard.gesture.WordDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PredictionEngine — word completion, autocorrect, and next-word prediction.
 *
 * Improvements over v1:
 *
 * **Autocorrect** now uses keyboard-adjacency-weighted Damerau–Levenshtein distance:
 *  - Adjacent-key substitutions cost 0.5 instead of 1.0 (e.g. m→n, i→o, q→w).
 *  - Transpositions cost 0.5 (e.g. teh→the, adn→and).
 *  - Search depth increased to distance ≤ 1.5 (catches 2-edit common typos cheaply).
 *  This dramatically improves correction of fat-finger errors and near-miss swipes.
 *
 * **Next-word bigrams** expanded from ~40 to ~200 common English pairs.
 *
 * **Prefix completion** unchanged — already fast and correct.
 */
class PredictionEngine(
    private val dictionary: WordDictionary,
    private val personalWordDao: PersonalWordDao
) {

    // ── Cache ─────────────────────────────────────────────────────────────────
    private val autocorrectCache = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String?>) = size > 50
    }

    // ── Common typos (fast path before expensive edit-distance search) ────────
    private val commonTypos = mapOf(
        "teh"  to "the",    "adn"  to "and",    "taht"  to "that",   "ahve" to "have",
        "hte"  to "the",    "nad"  to "and",    "yuo"   to "you",    "abotu" to "about",
        "fo"   to "of",     "thsi" to "this",
        "dont" to "don't",  "cant" to "can't",  "wont"  to "won't",
        "im"   to "I'm",    "ive"  to "I've",
        "thats" to "that's", "whats" to "what's", "hes" to "he's", "shes" to "she's",
        "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
        "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't", "doesnt" to "doesn't",
        "didnt" to "didn't", "couldnt" to "couldn't", "wouldnt" to "wouldn't",
        "shouldnt" to "shouldn't", "youll" to "you'll", "theyll" to "they'll", "itll" to "it'll",
        "its" to "it's",
        "ot" to "to", "tto" to "too",
        "bcuz" to "because", "cuz" to "because",
        // Common spelling mistakes (safe — these words don't exist on their own)
        "recieve"    to "receive",     "seperate"   to "separate",
        "occured"    to "occurred",    "occuring"   to "occurring",
        "untill"     to "until",       "definately" to "definitely",
        "accomodate" to "accommodate", "neccessary" to "necessary",
        "occassion"  to "occasion",    "persue"     to "pursue",
        "suprise"    to "surprise",    "wierd"      to "weird",
        "beleive"    to "believe",     "belive"     to "believe",
        "freind"     to "friend",      "calender"   to "calendar",
        "comming"    to "coming",      "goverment"  to "government",
        "millenium"  to "millennium",
        "tommorow"   to "tomorrow",    "tommorrow"  to "tomorrow",
        "tomarrow"   to "tomorrow",    "tomorow"    to "tomorrow",
        "grammer"    to "grammar",     "independant" to "independent",
        "lisence"    to "license",     "noticable"  to "noticeable",
        "priviledge" to "privilege",   "restaraunt" to "restaurant",
        "rythm"      to "rhythm",      "writting"   to "writing",
        "acually"    to "actually",    "becuase"    to "because",
        "probaly"    to "probably",    "wich"       to "which",
        "woud"       to "would",       "coudl"      to "could",
        "frome"      to "from",
        "hwo"        to "how",
        "jsut"       to "just",        "knwo"       to "know",
        "mroe"       to "more",        "msut"       to "must",
        "thier"      to "their",
        "youre"      to "you're",      "theyre"     to "they're",
        "weve"       to "we've"
        // NOTE: 'form', 'were', 'id', 'r', 'u', 'alright' deliberately
        // NOT included — they are all valid standalone English words/identifiers
        // and autocorrecting them causes far more harm than good.
    )

    // ── QWERTY keyboard adjacency map ─────────────────────────────────────────
    // Used to assign cheaper substitution cost for adjacent-key typos.
    private val ADJACENT: Map<Char, Set<Char>> = mapOf(
        'q' to setOf('w', 'a', 's'),
        'w' to setOf('q', 'e', 'a', 's', 'd'),
        'e' to setOf('w', 'r', 's', 'd', 'f'),
        'r' to setOf('e', 't', 'd', 'f', 'g'),
        't' to setOf('r', 'y', 'f', 'g', 'h'),
        'y' to setOf('t', 'u', 'g', 'h', 'j'),
        'u' to setOf('y', 'i', 'h', 'j', 'k'),
        'i' to setOf('u', 'o', 'j', 'k', 'l'),
        'o' to setOf('i', 'p', 'k', 'l'),
        'p' to setOf('o', 'l'),
        'a' to setOf('q', 'w', 's', 'z'),
        's' to setOf('a', 'w', 'e', 'd', 'z', 'x'),
        'd' to setOf('s', 'e', 'r', 'f', 'x', 'c'),
        'f' to setOf('d', 'r', 't', 'g', 'c', 'v'),
        'g' to setOf('f', 't', 'y', 'h', 'v', 'b'),
        'h' to setOf('g', 'y', 'u', 'j', 'b', 'n'),
        'j' to setOf('h', 'u', 'i', 'k', 'n', 'm'),
        'k' to setOf('j', 'i', 'o', 'l', 'm'),
        'l' to setOf('k', 'o', 'p'),
        'z' to setOf('a', 's', 'x'),
        'x' to setOf('z', 's', 'd', 'c'),
        'c' to setOf('x', 'd', 'f', 'v'),
        'v' to setOf('c', 'f', 'g', 'b'),
        'b' to setOf('v', 'g', 'h', 'n'),
        'n' to setOf('b', 'h', 'j', 'm'),
        'm' to setOf('n', 'j', 'k')
    )

    // ── Prefix completion ─────────────────────────────────────────────────────

    /**
     * Returns up to [maxResults] word suggestions starting with [prefix].
     * Personal words sorted to the top; remaining from the frequency-ordered dictionary.
     */
    suspend fun getSuggestions(
        prefix: String,
        maxResults: Int = 3
    ): List<String> = withContext(Dispatchers.Default) {
        if (prefix.isEmpty()) return@withContext emptyList()
        val pfx = prefix.lowercase()

        val personal = personalWordDao.getAll()
            .filter { it.word.startsWith(pfx) }
            .sortedByDescending { it.frequency }
            .map { it.word }

        val dictMatches = dictionary.words
            .asSequence()
            .filter { it.startsWith(pfx) && it !in personal }
            .take(maxResults * 5)
            .toList()

        val exactTypo = commonTypos[pfx]
        val typoMatch = if (exactTypo != null) listOf(preserveCase(prefix, exactTypo)) else emptyList()

        (typoMatch + personal + dictMatches).distinct().take(maxResults)
    }

    // ── Autocorrect ───────────────────────────────────────────────────────────

    /**
     * Returns the corrected version of [word] or null if no correction needed.
     *
     * Check order:
     *  1. Hardcoded common-typo map (instant).
     *  2. Word is valid in dictionary or personal words → no correction.
     *  3. Adjacency-weighted Damerau–Levenshtein ≤ 1.5 → suggest best match.
     */
    suspend fun autocorrect(word: String): String? = withContext(Dispatchers.Default) {
        val lower = word.lowercase()

        synchronized(autocorrectCache) {
            if (autocorrectCache.containsKey(lower)) {
                return@withContext autocorrectCache[lower]?.let { preserveCase(word, it) }
            }
        }

        // 1. Common typo fast path
        commonTypos[lower]?.let { return@withContext it }

        // 2. Already valid
        if (dictionary.alphabeticalWords.binarySearch(lower) >= 0) return@withContext null
        if (personalWordDao.getFrequency(lower) > 0) return@withContext null

        // 2.5 Adjacency-weighted Damerau–Levenshtein search
        // Use a timeout to ensure we never block typing, and cap candidate count.
        val candidate = kotlinx.coroutines.withTimeoutOrNull(80L) {
            val threshold = if (lower.length <= 4) 0.9 else 1.5
            dictionary.words
                .asSequence()
                .filter { kotlin.math.abs(it.length - lower.length) <= 2 }
                .take(2500) // Much larger safety bound, safe now due to ThreadLocal DP cache
                .map { it to damerauLev(lower, it) }
                .filter { it.second <= threshold }
                .minByOrNull { it.second }
                ?.first
        }

        if (candidate != null) {
            val corrected = preserveCase(word, candidate)
            synchronized(autocorrectCache) { autocorrectCache[lower] = corrected }
            return@withContext corrected
        }

        // 3. Missing space auto-split (e.g. "Theextra" -> "The extra")
        // Only run if we didn't find a strong edit-distance autocorrect.
        for (i in 1 until lower.length) {
            val left = lower.substring(0, i)
            val right = lower.substring(i)
            
            // For auto-split, require words to be at least length 3 to prevent
            // splitting valid typos into obscure 2-letter abbreviations (e.g. "samw" -> "sa mw").
            // Specific 1/2-letter words are hardcoded as allowed exceptions.
            val validLeft = left.length > 2 || left in listOf("a", "i", "to", "do", "of", "in", "on", "is", "it", "he", "we", "my", "me", "be", "as", "at", "by", "go", "or", "so", "up", "us", "am", "an", "if", "no")
            val validRight = right.length > 2 || right in listOf("a", "i", "to", "do", "of", "in", "on", "is", "it", "he", "we", "my", "me", "be", "as", "at", "by", "go", "or", "so", "up", "us", "am", "an", "if", "no")
            
            if (validLeft && validRight && isKnownWord(left) && isKnownWord(right)) {
                val correctedLeft = preserveCase(word.substring(0, i), left)
                val correctedRight = preserveCase(word.substring(i), right)
                val splitCandidate = "$correctedLeft $correctedRight"
                
                synchronized(autocorrectCache) { autocorrectCache[lower] = splitCandidate }
                return@withContext splitCandidate
            }
        }

        synchronized(autocorrectCache) {
            autocorrectCache[lower] = null
        }

        return@withContext null
    }

    private fun preserveCase(original: String, correction: String): String {
        if (original.isEmpty()) return correction
        val isAllUpper = original.all { !it.isLetter() || it.isUpperCase() }
        if (isAllUpper) return correction.uppercase()
        val isFirstUpper = original.first().isUpperCase()
        if (isFirstUpper) return correction.replaceFirstChar { it.uppercase() }
        return correction
    }

    // ── Learning new words ────────────────────────────────────────────────────

    suspend fun learn(word: String) = withContext(Dispatchers.IO) {
        if (word.isBlank() || commonTypos.containsKey(word.lowercase())) return@withContext
        val cleanWord = word.trim('"', ' ', '.', ',', '!', '?', '\'', ':', ';', '(', ')', '[', ']')
        if (cleanWord.isEmpty()) return@withContext
        personalWordDao.learn(cleanWord)
    }

    suspend fun isKnownWord(word: String): Boolean = withContext(Dispatchers.Default) {
        val lower = word.lowercase()
        dictionary.alphabeticalWords.binarySearch(lower) >= 0 || personalWordDao.getFrequency(lower) > 0
    }

    // ── Next-word prediction ──────────────────────────────────────────────────

    suspend fun nextWordSuggestions(previousWord: String): List<String> =
        withContext(Dispatchers.Default) {
            BIGRAMS[previousWord.lowercase()]
                ?.let { return@withContext it }

            val personal = personalWordDao.getAll().take(3).map { it.word }
            if (personal.isNotEmpty()) return@withContext personal

            emptyList()
        }

    // ── Dynamic Hitbox Prediction ─────────────────────────────────────────────

    suspend fun predictNextChars(prefix: String): Set<Char> = withContext(Dispatchers.Default) {
        if (prefix.isEmpty()) return@withContext emptySet()
        val pfx = prefix.lowercase()
        val chars = mutableSetOf<Char>()
        
        val words = dictionary.alphabeticalWords
        var idx = words.binarySearch(pfx)
        if (idx < 0) idx = -idx - 1 // insertion point
        
        while (idx < words.size && chars.size < 26) {
            val w = words[idx]
            if (!w.startsWith(pfx)) break
            if (w.length > pfx.length) {
                chars.add(w[pfx.length])
            }
            idx++
        }
            
        chars
    }

    // ── Adjacency-weighted Damerau–Levenshtein ────────────────────────────────
    
    // Thread-local array to avoid GC allocation during rapid typing
    private val dpCache = object : ThreadLocal<DoubleArray>() {
        override fun initialValue() = DoubleArray(400) // Support up to 19x19 words
    }

    /**
     * Computes a weighted edit distance between [a] and [b]:
     *  - Adjacent-key substitution: 0.5
     *  - Transposition of adjacent chars: 0.5
     *  - Insertion / deletion: 1.0
     *  - Non-adjacent substitution: 1.0
     *
     * Uses the restricted Damerau distance (optimal string alignment, OSA).
     */
    private fun damerauLev(a: String, b: String): Double {
        val m = a.length
        val n = b.length
        if ((m + 1) * (n + 1) > 400) return Double.MAX_VALUE // Safety bound

        val dp = dpCache.get()!!
        fun idx(i: Int, j: Int) = i * (n + 1) + j
        
        for (i in 0..m) dp[idx(i, 0)] = i.toDouble()
        for (j in 0..n) dp[idx(0, j)] = j.toDouble()

        for (i in 1..m) {
            for (j in 1..n) {
                val ai = a[i - 1]
                val bj = b[j - 1]

                val subCost = if (ai == bj) {
                    0.0
                } else if (ADJACENT[ai]?.contains(bj) == true) {
                    0.5   // adjacent key — cheap
                } else {
                    1.0
                }

                var minCost = minOf(
                    dp[idx(i - 1, j)] + 1.0,           // deletion
                    dp[idx(i, j - 1)] + 1.0,           // insertion
                    dp[idx(i - 1, j - 1)] + subCost    // substitution
                )

                // Transposition (Damerau extension)
                if (i > 1 && j > 1 && ai == b[j - 2] && a[i - 2] == bj) {
                    minCost = minOf(minCost, dp[idx(i - 2, j - 2)] + 0.5)
                }
                dp[idx(i, j)] = minCost
            }
        }
        return dp[idx(m, n)]
    }

    // ── Bigram table (200 common English word pairs) ──────────────────────────

    companion object {
        private val BIGRAMS: Map<String, List<String>> = mapOf(
            // Pronouns
            "i"        to listOf("am", "think", "want"),
            "you"      to listOf("are", "can", "will"),
            "he"       to listOf("is", "was", "said"),
            "she"      to listOf("is", "was", "said"),
            "we"       to listOf("are", "can", "need"),
            "they"     to listOf("are", "were", "have"),
            "it"       to listOf("is", "was", "will"),
            // Common starters
            "the"      to listOf("best", "most", "same"),
            "a"        to listOf("good", "few", "lot"),
            "this"     to listOf("is", "means", "was"),
            "that"     to listOf("is", "was", "the"),
            "but"      to listOf("the", "I", "we"),
            "and"      to listOf("the", "I", "we"),
            "in"       to listOf("the", "this", "a"),
            "on"       to listOf("the", "my", "a"),
            "for"      to listOf("the", "me", "you"),
            "to"       to listOf("the", "be", "do"),
            "of"       to listOf("the", "a", "course"),
            "at"       to listOf("the", "all", "least"),
            "from"     to listOf("the", "my", "a"),
            "with"     to listOf("the", "a", "me"),
            "about"    to listOf("the", "it", "this"),
            "not"      to listOf("sure", "going", "the"),
            // Greetings / social (no duplicate keys)
            "good"     to listOf("morning", "night", "luck"),
            "thank"    to listOf("you", "god", "goodness"),
            "thanks"   to listOf("for", "so", "a"),
            "happy"    to listOf("to", "birthday", "new"),
            "sorry"    to listOf("for", "about", "I"),
            "please"   to listOf("let", "make", "note"),
            "hey"      to listOf("there", "how", "I"),
            "hi"       to listOf("there", "how", "I"),
            "hello"    to listOf("there", "how", "world"),
            // Verbs
            "let"      to listOf("me", "us", "know"),
            "make"     to listOf("sure", "it", "the"),
            "how"      to listOf("are", "do", "much"),
            "what"     to listOf("do", "is", "are"),
            "when"     to listOf("you", "do", "will"),
            "where"    to listOf("are", "is", "do"),
            "why"      to listOf("do", "are", "is"),
            "do"       to listOf("you", "not", "we"),
            "can"      to listOf("you", "I", "we"),
            "will"     to listOf("you", "be", "the"),
            "would"    to listOf("you", "be", "like"),
            "could"    to listOf("you", "be", "we"),
            "should"   to listOf("be", "I", "we"),
            "have"     to listOf("you", "a", "been"),
            "had"      to listOf("to", "a", "the"),
            "has"      to listOf("been", "a", "the"),
            "been"     to listOf("a", "the", "working"),
            "going"    to listOf("to", "on", "well"),
            "getting"  to listOf("a", "the", "ready"),
            "looking"  to listOf("for", "at", "good"),
            "trying"   to listOf("to", "my", "hard"),
            "need"     to listOf("to", "a", "your"),
            "want"     to listOf("to", "a", "you"),
            "think"    to listOf("about", "so", "that"),
            "know"     to listOf("what", "how", "that"),
            "see"      to listOf("you", "the", "what"),
            "get"      to listOf("the", "a", "back"),
            "take"     to listOf("a", "the", "care"),
            "give"     to listOf("me", "you", "a"),
            "come"     to listOf("on", "back", "here"),
            "go"       to listOf("to", "back", "on"),
            "talk"     to listOf("to", "about", "later"),
            "work"     to listOf("on", "out", "from"),
            "love"     to listOf("you", "it", "the"),
            "like"     to listOf("the", "a", "this"),
            "said"     to listOf("the", "I", "he"),
            "told"     to listOf("me", "you", "him"),
            "called"   to listOf("the", "me", "it"),
            "asked"    to listOf("me", "him", "her"),
            "replied"  to listOf("the", "I", "he"),
            // Time / sequence
            "first"    to listOf("time", "of", "thing"),
            "last"     to listOf("time", "night", "year"),
            "next"     to listOf("time", "week", "year"),
            "every"    to listOf("day", "time", "one"),
            "just"     to listOf("a", "want", "need"),
            "still"    to listOf("have", "going", "need"),
            "already"  to listOf("have", "did", "know"),
            "always"   to listOf("have", "wanted", "been"),
            "never"    to listOf("mind", "been", "knew"),
            "now"      to listOf("I", "the", "its"),
            "then"     to listOf("the", "I", "we"),
            "again"    to listOf("I", "the", "later"),
            "later"    to listOf("today", "this", "tonight"),
            "today"    to listOf("I", "we", "is"),
            "tonight"  to listOf("I", "we", "at"),
            "tomorrow" to listOf("morning", "I", "at"),
            "yesterday" to listOf("I", "we", "was"),
            // Common continuations
            "really"   to listOf("want", "need", "good"),
            "very"     to listOf("good", "much", "well"),
            "much"     to listOf("better", "more", "as"),
            "more"     to listOf("than", "of", "time"),
            "better"   to listOf("than", "to", "off"),
            "great"    to listOf("job", "idea", "time"),
            "nice"     to listOf("to", "day", "job"),
            "sure"     to listOf("I", "about", "thing"),
            "well"     to listOf("done", "worth", "I"),
            "okay"     to listOf("I", "so", "with"),
            "ok"       to listOf("I", "so", "with"),
            "yeah"     to listOf("I", "that", "right"),
            "yes"      to listOf("I", "that", "please"),
            "no"       to listOf("one", "way", "problem"),
            // Contractions (without apostrophe — as typed)
            "dont"     to listOf("know", "want", "think"),
            "cant"     to listOf("wait", "believe", "do"),
            "wont"     to listOf("be", "let", "do"),
            "didnt"    to listOf("know", "want", "think"),
            "doesnt"   to listOf("mean", "make", "work"),
            "isnt"     to listOf("it", "that", "the"),
            "arent"    to listOf("you", "they", "we"),
            "wasnt"    to listOf("sure", "able", "there"),
            "havent"   to listOf("been", "had", "done"),
            "wouldnt"  to listOf("be", "know", "want"),
            "couldnt"  to listOf("be", "find", "do"),
            "shouldnt" to listOf("be", "do", "have"),
            // Pronouns / question starters
            "who"      to listOf("is", "are", "do"),
            "which"    to listOf("is", "one", "are"),
            // Interjections
            "oh"       to listOf("my", "no", "well"),
            "wow"      to listOf("that", "I", "so"),
            "hmm"      to listOf("I", "that", "okay"),
            // Quantity
            "one"      to listOf("of", "more", "thing"),
            "two"      to listOf("of", "things", "days"),
            "few"      to listOf("days", "minutes", "things"),
            "some"     to listOf("of", "time", "people"),
            "many"     to listOf("of", "people", "things"),
            "all"      to listOf("the", "of", "right"),
            "both"     to listOf("of", "are", "have"),
            // Conjunctions / discourse
            "because"  to listOf("I", "of", "the"),
            "although" to listOf("I", "it", "the"),
            "however"  to listOf("I", "the", "it"),
            "therefore" to listOf("I", "the", "it"),
            "maybe"    to listOf("I", "we", "later"),
            "probably" to listOf("not", "the", "going"),
            "actually" to listOf("I", "the", "it"),
            "mr"       to listOf("president", "smith", "jones"),
            "mrs"      to listOf("smith", "jones", "brown"),
            "dr"       to listOf("smith", "jones", "who"),
            // ── Nigerian Pidgin / Nigerian teen phrases ──────────────────────
            "abeg"       to listOf("no", "let", "comot"),
            "wahala"     to listOf("dey", "no", "be"),
            "omo"        to listOf("see", "this", "the"),
            "sapa"       to listOf("don", "catch", "is"),
            "japa"       to listOf("to", "abroad", "now"),
            "naija"      to listOf("no", "people", "is"),
            "lowkey"     to listOf("i", "this", "was"),
            "highkey"    to listOf("this", "i", "the"),
            "bestie"     to listOf("check", "vibes", "this"),
            "situationship" to listOf("is", "was", "goals"),
            "bussin"     to listOf("fr", "no", "tho"),
            "rizz"       to listOf("is", "no", "on"),
            "lmao"       to listOf("why", "this", "bro"),
            "tbh"        to listOf("i", "this", "the"),
            "ngl"        to listOf("this", "i", "the"),
            "wyd"        to listOf("today", "rn", "later"),
            "hmu"        to listOf("when", "if", "later"),
            "smh"        to listOf("why", "this", "bro")
        )
    }
}
