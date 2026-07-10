package com.draftkeys.keyboard.prediction

import com.draftkeys.keyboard.gesture.WordDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PredictionEngine — word completion, autocorrect, and next-word prediction.
 *
 * Constructor:
 *  [dictionary]        — the loaded WordDictionary (Trie + inverted index).
 *  [personalWordCache] — B2: in-memory mirror of personal_words (no per-keystroke SQLite).
 *  [bigramDao]         — user-learned bigram transitions (queried only on next-word predict).
 *  [staticBigrams]     — 3.1MB Norvig corpus bigrams (queried only on next-word predict).
 *  [textExpansionDao]  — D1: user-defined shortcut → expansion pairs.
 */
class PredictionEngine(
    private val dictionary: WordDictionary,
    private val personalWordCache: PersonalWordCache,
    private val bigramDao: BigramDao,
    private val staticBigrams: StaticBigrams,
    private val textExpansionDao: TextExpansionDao
) {
    // Convenience alias — keeps internal call sites readable
    private val personalWordDao get() = personalWordCache

    // ── Cache ─────────────────────────────────────────────────────────────────
    private val autocorrectCache = object : LinkedHashMap<String, String?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String?>) = size > 500
    }

    // ── Common typos (fast path before expensive edit-distance search) ────────
    private val commonTypos = mapOf(
        "teh"  to "the",    "adn"  to "and",    "taht"  to "that",   "ahve" to "have",
        "hte"  to "the",    "nad"  to "and",    "yuo"   to "you",    "abotu" to "about",
        "fo"   to "of",     "thsi" to "this",     "tje" to "the",
        "dont" to "don't",  "cant" to "can't",  "wont"  to "won't",
        "im"   to "I'm",    "ive"  to "I've",
        "thats" to "that's", "whats" to "what's", "hes" to "he's", "shes" to "she's",
        "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
        "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't", "doesnt" to "doesn't",
        "didnt" to "didn't", "couldnt" to "couldn't", "wouldnt" to "wouldn't",
        "shouldnt" to "shouldn't", "youll" to "you'll", "theyll" to "they'll", "itll" to "it'll",
        "its" to "it's",
        "ot" to "to", "tto" to "too", "wjat" to "what", "whay" to "what",
        "bcuz" to "because", "cuz" to "because",
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
    )

    // ── Emoji Suggestions ─────────────────────────────────────────────────────
    private val emojiMap = mapOf(
        "pizza" to "🍕", "food" to "🍔", "coffee" to "☕", "beer" to "🍺", "wine" to "🍷",
        "dog" to "🐶", "cat" to "🐱", "love" to "❤️", "fire" to "🔥", "cool" to "😎",
        "laugh" to "😂", "lol" to "😂", "lmao" to "🤣", "sad" to "😢", "cry" to "😭",
        "poop" to "💩", "heart" to "❤️", "smile" to "😊", "happy" to "😊", "angry" to "😡",
        "thumbs" to "👍", "ok" to "👌", "yes" to "👍", "no" to "👎", "check" to "✅",
        "cross" to "❌", "star" to "⭐", "sun" to "☀️", "moon" to "🌙", "world" to "🌍",
        "music" to "🎵", "party" to "🎉", "gift" to "🎁", "cake" to "🎂", "car" to "🚗",
        "house" to "🏠", "money" to "💰", "cash" to "💵", "sleep" to "😴", "tired" to "🥱",
        "sick" to "🤒", "wow" to "😮", "omg" to "😱", "please" to "🙏", "pray" to "🙏",
        "hot" to "🔥", "cold" to "🥶", "gym" to "💪", "strong" to "💪", "brain" to "🧠",
        "eyes" to "👀", "look" to "👀", "kiss" to "😘", "kisses" to "💋", "hug" to "🤗", "ghost" to "👻",
        "alien" to "👽", "robot" to "🤖", "clown" to "🤡", "monkey" to "🐒", "bear" to "🐻",
        "bird" to "🐦", "fish" to "🐟", "tree" to "🌳", "flower" to "🌸", "water" to "💧",
        "rain" to "🌧️", "snow" to "❄️", "storm" to "⚡", "book" to "📖", "study" to "📚",
        "work" to "💼", "phone" to "📱", "computer" to "💻", "game" to "🎮", "tv" to "📺",
        "camera" to "📷", "movie" to "🍿", "sports" to "⚽", "basketball" to "🏀", "football" to "🏈",
        "run" to "🏃", "walk" to "🚶", "stop" to "🛑", "warning" to "⚠️", "idea" to "💡",
        "time" to "⏰", "clock" to "⏱️", "calendar" to "📅", "mail" to "📧", "letter" to "✉️",
        "package" to "📦", "key" to "🔑", "lock" to "🔒", "unlock" to "🔓", "search" to "🔍",
        "good" to "👍", "bad" to "👎", "great" to "🌟", "perfect" to "💯", "100" to "💯",
        "confused" to "😕", "morning" to "🌅", "mad" to "😡", "glad" to "😊"
    )

    // ── Abbreviation expansion ────────────────────────────────────────────────
    // D4: Offer full phrases when user types a known abbreviation
    private val abbreviations = mapOf(
        "omw"  to "On my way!",
        "brb"  to "Be right back",
        "gtg"  to "Got to go",
        "ttyl" to "Talk to you later",
        "imo"  to "In my opinion",
        "imho" to "In my humble opinion",
        "fyi"  to "For your information",
        "asap" to "As soon as possible",
        "nvm"  to "Never mind",
        "lmk"  to "Let me know",
        "idk"  to "I don't know",
        "irl"  to "In real life",
        "afk"  to "Away from keyboard",
        "gg"   to "Good game",
        "gl"   to "Good luck",
        "np"   to "No problem",
        "ty"   to "Thank you",
        "yw"   to "You're welcome",
        "hbd"  to "Happy birthday",
        "smh"  to "Shaking my head",
        "tbh"  to "To be honest",
        "ngl"  to "Not gonna lie",
        "icymi" to "In case you missed it",
        "tldr" to "Too long, didn't read",
        "ootd" to "Outfit of the day",
        "wfh"  to "Working from home"
    )

    // ── Contraction / apostrophe completion ──────────────────────────────
    // C3: Proactively suggest the apostrophe form when a contraction base is typed
    private val contractionBoosts = mapOf(
        "don"    to listOf("don't"),
        "can"    to listOf("can't"),
        "won"    to listOf("won't"),
        "isn"    to listOf("isn't"),
        "aren"   to listOf("aren't"),
        "wasn"   to listOf("wasn't"),
        "weren"  to listOf("weren't"),
        "haven"  to listOf("haven't"),
        "hasn"   to listOf("hasn't"),
        "hadn"   to listOf("hadn't"),
        "didn"   to listOf("didn't"),
        "doesn"  to listOf("doesn't"),
        "wouldn" to listOf("wouldn't"),
        "couldn" to listOf("couldn't"),
        "shouldn" to listOf("shouldn't"),
        "it"     to listOf("it's"),
        "i"      to listOf("I'm", "I'll", "I've"),
        "you"    to listOf("you're", "you'll", "you've"),
        "they"   to listOf("they're", "they'll", "they've"),
        "we"     to listOf("we're", "we'll", "we've"),
        "he"     to listOf("he's"),
        "she"    to listOf("she's"),
        "that"   to listOf("that's"),
        "what"   to listOf("what's"),
        "there"  to listOf("there's"),
        "here"   to listOf("here's"),
        "who"    to listOf("who's"),
        "how"    to listOf("how's"),
        "let"    to listOf("let's")
    )

    // ── QWERTY keyboard adjacency map ─────────────────────────────────────────
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

    suspend fun getSuggestions(
        prefix: String,
        maxResults: Int = 5
    ): List<String> = withContext(Dispatchers.Default) {
        if (prefix.isEmpty()) return@withContext emptyList()
        val pfx = prefix.lowercase()

        // 1. User-defined text expansion shortcuts — D1: highest priority
        //    (e.g. user has "addr" → "123 Main Street" — always show it first)
        val userExpansion = textExpansionDao.find(pfx)
        if (userExpansion != null) {
            // Return the expansion as top slot, fill remaining slots with Trie matches
            val rest = dictionary.trie.getCompletions(pfx, maxResults - 1).map { it.first }
            return@withContext (listOf(userExpansion.expansion) + rest).distinct().take(maxResults)
        }

        // 2. Personal words (in-memory cache — B2: no SQLite per keystroke)
        val personal = personalWordCache.getAll()
            .filter { it.word.startsWith(pfx) }
            .sortedByDescending { it.frequency }
            .map { it.word }

        // 2. Abbreviation expansion — D4: exact match offers full phrase (e.g. "omw" → "On my way!")
        val abbreviationMatch = abbreviations[pfx]?.let { listOf(it) } ?: emptyList()

        // 3. Contraction boost — C3: proactively suggest apostrophe form (e.g. "don" → "don't")
        val contractionMatch = contractionBoosts[pfx] ?: emptyList()

        // 4. Common typo fast path (hardcoded map — avoids expensive Trie search)
        val exactTypo = commonTypos[pfx]
        val typoMatch = if (exactTypo != null) listOf(preserveCase(prefix, exactTypo)) else emptyList()

        // 5. Trie BFS prefix search — B1: O(k) instead of O(N) linear scan
        val trieMatches = dictionary.trie.getCompletions(pfx, maxResults * 5)
            .map { it.first }
            .filter { it !in personal }

        // 6. Emoji prediction for the exact word
        val emojiMatch = emojiMap[pfx]?.let { listOf(it) } ?: emptyList()

        // 7. Live Fuzzy Search (only when Trie finds nothing and no fast-path typo match)
        var fuzzyMatch: List<String> = emptyList()
        if (pfx.length >= 3 && trieMatches.isEmpty() && exactTypo == null) {
            val candidate = autocorrect(prefix)
            if (candidate != null && candidate.lowercase() != pfx) {
                fuzzyMatch = listOf(candidate)
            }
        }

        (abbreviationMatch + contractionMatch + typoMatch + fuzzyMatch + personal + trieMatches + emojiMatch)
            .distinct().take(maxResults)
    }

    // ── Autocorrect ───────────────────────────────────────────────────────────

    suspend fun autocorrect(word: String, bigramContext: String? = null): String? = withContext(Dispatchers.Default) {
        val lower = word.lowercase()

        synchronized(autocorrectCache) {
            if (autocorrectCache.containsKey(lower)) {
                return@withContext autocorrectCache[lower]?.let { preserveCase(word, it) }
            }
        }

        // 1. Common typo fast path
        commonTypos[lower]?.let { return@withContext it }

        // 2. Already valid
        if (isKnownWord(lower)) return@withContext null

        // 3. Trie-based Damerau-Levenshtein search
        // More forgiving thresholds to fix fat-finger typos
        val threshold = when {
            lower.length <= 2 -> 0.9 // For 2 letters, allow max 1 adjacent typo (0.5)
            lower.length <= 4 -> 1.5 // Allow 1 full mistake (1.0) or 3 adjacent typos
            lower.length <= 6 -> 2.1 // Allow 2 full mistakes
            else -> 2.6
        }

        // C2: Fetch expected words to boost their confidence
        val expectedNextWords = if (bigramContext != null) {
            nextWordSuggestions(bigramContext)
        } else emptyList()

        val candidate = trieSearch(lower, threshold, expectedNextWords)

        if (candidate != null) {
            val corrected = preserveCase(word, candidate)
            synchronized(autocorrectCache) { autocorrectCache[lower] = corrected }
            return@withContext corrected
        }

        // 4. Missing space auto-split
        for (i in 1 until lower.length) {
            val left = lower.substring(0, i)
            val right = lower.substring(i)
            
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

    private fun trieSearch(target: String, maxCost: Double, expectedWords: List<String>): String? {
        val targetLen = target.length
        val currentRow = DoubleArray(targetLen + 1) { it.toDouble() }
        
        var bestMatch: String? = null
        var bestCombinedScore = Double.MAX_VALUE

        fun search(
            node: TrieNode,
            charAdded: Char,
            wordSoFar: String,
            prevRow: DoubleArray,
            prevPrevRow: DoubleArray?,
            prevChar: Char?
        ) {
            val nextRow = DoubleArray(targetLen + 1)
            nextRow[0] = prevRow[0] + 1.0
            
            var minCostInRow = nextRow[0]

            for (i in 1..targetLen) {
                val targetChar = target[i - 1]
                val subCost = if (targetChar == charAdded) {
                    0.0
                } else if (ADJACENT[targetChar]?.contains(charAdded) == true) {
                    0.5
                } else {
                    1.0
                }

                var cost = minOf(
                    prevRow[i] + 1.0,           // Deletion
                    nextRow[i - 1] + 1.0,       // Insertion
                    prevRow[i - 1] + subCost    // Substitution
                )

                // Transposition
                if (i > 1 && prevPrevRow != null && prevChar != null) {
                    if (targetChar == prevChar && target[i - 2] == charAdded) {
                        cost = minOf(cost, prevPrevRow[i - 2] + 0.5)
                    }
                }
                
                nextRow[i] = cost
                if (cost < minCostInRow) minCostInRow = cost
            }

            if (minCostInRow > maxCost) {
                return // Prune this branch
            }

            if (node.isWord) {
                val finalCost = nextRow[targetLen]
                if (finalCost <= maxCost) {
                    val lmProb = maxOf(node.probability, 0.000001)
                    // C2: If the bigram model predicted this word, treat it as having 1 fewer typo
                    val bigramBonus = if (wordSoFar in expectedWords) 1.0 else 0.0
                    
                    // Lower is better. Combine edit distance with language model probability.
                    val combinedScore = finalCost - bigramBonus - kotlin.math.ln(lmProb) * 0.15
                    if (combinedScore < bestCombinedScore) {
                        bestCombinedScore = combinedScore
                        bestMatch = wordSoFar
                    }
                }
            }

            for ((childChar, childNode) in node.children) {
                search(childNode, childChar, wordSoFar + childChar, nextRow, prevRow, charAdded)
            }
        }

        for ((char, child) in dictionary.trie.root.children) {
            search(child, char, char.toString(), currentRow, null, null)
        }

        return bestMatch
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
        
        synchronized(autocorrectCache) {
            autocorrectCache.remove(cleanWord.lowercase())
        }
        
        personalWordDao.learn(cleanWord)
    }

    suspend fun isKnownWord(word: String): Boolean = withContext(Dispatchers.Default) {
        val lower = word.lowercase()
        // Fast Trie search first (O(k))
        var current = dictionary.trie.root
        for (char in lower) {
            current = current.children[char]
                // B2: cache.contains() is O(1) HashSet — no SQLite roundtrip
                ?: return@withContext personalWordCache.contains(lower)
        }
        if (current.isWord) return@withContext true
        // B2: use in-memory cache for personal words check
        return@withContext personalWordCache.contains(lower)
    }

    suspend fun learnWord(word: String) = withContext(Dispatchers.IO) {
        val clean = word.trim().lowercase()
        if (clean.length < 2) return@withContext
        personalWordDao.learn(clean)
    }

    suspend fun learnBigram(word1: String?, word2: String?) = withContext(Dispatchers.IO) {
        if (word1.isNullOrBlank() || word2.isNullOrBlank()) return@withContext
        val clean1 = word1.trim().lowercase()
        val clean2 = word2.trim().lowercase()
        bigramDao.learn(clean1, clean2)
    }

    // ── Helper functions ──────────────────────────────────────────────────────

    // ── Next-word prediction ──────────────────────────────────────────────────

    suspend fun nextWordSuggestions(previousWord: String): List<String> =
        withContext(Dispatchers.IO) {
            val lower = previousWord.lowercase()

            // 1. User-learned bigrams — highest priority (personalised transitions)
            val personalBigrams = bigramDao.getPredictions(lower, limit = 3)
            if (personalBigrams.isNotEmpty()) return@withContext personalBigrams

            // 2. Static Norvig corpus bigrams (3.1MB DB — covers hundreds of thousands of pairs)
            val staticResults = staticBigrams.getPredictions(lower, limit = 3)
            if (staticResults.isNotEmpty()) return@withContext staticResults

            // 3. Hardcoded fallback map (~170 common pairs) — last resort
            BIGRAMS[lower]?.let { return@withContext it }

            // 4. Generic fallback: most-used personal words regardless of context
            personalWordDao.getAll().take(3).map { it.word }
        }

    // ── Dynamic Hitbox Prediction ─────────────────────────────────────────────

    suspend fun predictNextChars(prefix: String): Set<Char> = withContext(Dispatchers.Default) {
        if (prefix.isEmpty()) return@withContext emptySet()
        val pfx = prefix.lowercase()
        val chars = mutableSetOf<Char>()
        
        var current = dictionary.trie.root
        for (char in pfx) {
            val child = current.children[char]
            if (child == null) return@withContext emptySet()
            current = child
        }
        
        // Add all direct children characters
        chars.addAll(current.children.keys)
        chars
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
            // Contractions
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
            // Nigerian Pidgin / Teen phrases
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
