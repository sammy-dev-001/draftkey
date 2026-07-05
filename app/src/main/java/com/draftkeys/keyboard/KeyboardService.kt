package com.draftkeys.keyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.LayoutInflater
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Toast
import com.draftkeys.keyboard.clipboard.ClipboardHistoryManager
import com.draftkeys.keyboard.clipboard.ClipboardPanel
import com.draftkeys.keyboard.data.DraftDatabase
import com.draftkeys.keyboard.data.DraftEntity
import com.draftkeys.keyboard.data.DraftRepository
import com.draftkeys.keyboard.gesture.GestureDecoder
import com.draftkeys.keyboard.gesture.GesturePoint
import com.draftkeys.keyboard.gesture.WordDictionary
import com.draftkeys.keyboard.prediction.PredictionEngine
import com.draftkeys.keyboard.ui.DraftKeysView
import com.draftkeys.keyboard.ui.KeyboardActionListener
import com.draftkeys.keyboard.ui.KeyboardLayout
import com.draftkeys.keyboard.ui.SuggestionBarView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * KeyboardService — the Android IME service for DraftKeys.
 *
 * All keyboard rendering and touch handling is now delegated to [DraftKeysView] and
 * [SuggestionBarView].  This service wires them together and owns all business logic:
 * auto-save, draft restore, gesture decoding, text prediction, clipboard management,
 * haptic feedback, auto-capitalisation, and double-space punctuation.
 *
 * Phases implemented here:
 *  Phase 0 — Custom keyboard engine (replaces deprecated KeyboardView)
 *  Phase 2 — Gesture / swipe word decode
 *  Phase 3 — Text prediction + autocorrect
 *  Phase 4 — Clipboard history
 */
class KeyboardService : InputMethodService(), KeyboardActionListener {

    // ── Views ───────────────────────────────────────────────────────
    private lateinit var draftKeysView:   DraftKeysView
    private lateinit var emojiKeyboardView: com.draftkeys.keyboard.ui.EmojiKeyboardView
    private lateinit var suggestionBar:   SuggestionBarView
    private lateinit var clipboardPanel:  ClipboardPanel
    private lateinit var rootKeyboardView: View

    // ── Keyboard state ──────────────────────────────────────────────
    private var isShiftActive     = false
    private var isCapsLock        = false
    private var lastShiftTime     = 0L
    private var isSensitiveField  = false
    private var currentPackage    = "unknown"
    private var lastSpaceTime     = 0L

    // ── Current word buffer (for prediction) ────────────────────────
    private val currentWord       = StringBuilder()
    private var currentWordSuffixLength = 0
    
    // ── Pre-loaded emojis ───────────────────────────────────────────
    private var emojiList         = emptyList<String>()

    // ── Typed text buffer — fallback when ExtractedText is null ────
    // Some apps (WhatsApp, Signal, Chrome) block ExtractedText for privacy.
    // We track what the user types through the keyboard as a best-effort backup.
    private val typedBuffer       = StringBuilder()

    // ── Pending autocorrect ─────────────────────────────────────────
    /** The last word typed before a space — candidate for autocorrect. */
    private var lastCompletedWord = ""
    private var justGlided = false
    private var lastGlidedWordLength = 0
    private var justAutoSpaced = false

    // ── Draft deduplication ─────────────────────────────────────────
    private var lastSavedText     = ""

    // ── Autocorrect guard ─────────────────────────────────────────────
    /**
     * Set to true just before a deleteSurroundingText+commitText autocorrect pair so that
     * [onUpdateSelection] does not reprocess the synthetic cursor movement and jump the
     * cursor to the end of the field on apps that fire a selection event per edit.
     */
    private var suppressNextSelectionUpdate = false

    // ── Coroutines ──────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var saveJob: Job? = null
    private var predictionJob: Job? = null
    private var autocorrectJob: Job? = null
    private val swipeMutex = Mutex()

    // ── Data layer ──────────────────────────────────────────────────
    private lateinit var repository:        DraftRepository
    private lateinit var dictionary:        WordDictionary
    private lateinit var gestureDecoder:    GestureDecoder
    private lateinit var predictionEngine:  PredictionEngine
    private lateinit var clipboardManager:  ClipboardHistoryManager

    // ── System services ─────────────────────────────────────────────
    private lateinit var vibrator: Vibrator
    private lateinit var audioManager: android.media.AudioManager
    
    // ── Theming ─────────────────────────────────────────────────────
    private lateinit var themeManager: com.draftkeys.keyboard.ui.ThemeManager

    // ══════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()

        // ── Database + repositories ──────────────────────────────
        val db = DraftDatabase.getInstance(this)
        repository       = DraftRepository(db.draftDao())
        clipboardManager = ClipboardHistoryManager(this, db.clipDao(), serviceScope)
        // Initialise dictionary first so both PredictionEngine and GestureDecoder share it
        dictionary       = WordDictionary(this)
        predictionEngine = PredictionEngine(dictionary, db.personalWordDao())
        gestureDecoder   = GestureDecoder(dictionary)

        // ── Load dictionary & emojis on background thread ────────
        serviceScope.launch {
            val loadedEmojis = loadEmojisAsync()
            withContext(Dispatchers.Main) {
                emojiList = loadedEmojis
                if (this@KeyboardService::emojiKeyboardView.isInitialized) {
                    emojiKeyboardView.setEmojis(emojiList)
                }
            }
            dictionary.load()
            // Build the gesture index after a layout is available;
            // we'll do it on first gesture request to avoid blocking startup.
        }

        // ── System services ───────────────────────────────────────
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

        themeManager = com.draftkeys.keyboard.ui.ThemeManager(this)

        clipboardManager.register()
    }

    override fun onCreateInputView(): View {
        // Wrap context with the app theme so that RecyclerView (and other Material
        // components inside ClipboardPanel) can resolve ?attr/ theme attributes.
        // Without this, the keyboard crashes as soon as the clipboard panel is opened.
        val themedContext = ContextThemeWrapper(this, R.style.Theme_DraftKeys)
        rootKeyboardView = LayoutInflater.from(themedContext).inflate(R.layout.keyboard_view, null)
        val view = rootKeyboardView

        draftKeysView = view.findViewById(R.id.draft_keys_view)
        draftKeysView.listener = this
        
        emojiKeyboardView = view.findViewById(R.id.emoji_keyboard_view)
        emojiKeyboardView.listener = this
        emojiKeyboardView.setEmojis(listOf("🔙", "😂", "😭", "🥺", "🤣", "❤", "✨", "😍", "🙏", "🥰", "😊", "💀", "💯", "😎", "👍", "🔥", "😒", "🙄", "🤔", "😔", "😉", "👌", "🤷", "😌", "🙌", "🤦", "✌", "😁", "😘", "☹", "👀"))
        
        suggestionBar     = view.findViewById(R.id.suggestion_bar)
        clipboardPanel    = view.findViewById(R.id.clipboard_panel)

        suggestionBar.listener     = this
        
        if (emojiList.isNotEmpty()) {
            emojiKeyboardView.setEmojis(emojiList)
        }

        // ── Toolbar: Restore Draft ──────────────────────────────
        view.findViewById<View>(R.id.btn_restore).setOnClickListener {
            restoreLastDraft()
        }

        // ── Toolbar: Paste (formerly Clips) ────────────────────────
        view.findViewById<View>(R.id.btn_clipboard).setOnClickListener {
            onPasteLastClip()
        }

        // ── Toolbar: Select All ─────────────────────────────────
        view.findViewById<View>(R.id.btn_select_all).setOnClickListener {
            currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        }

        // ── Toolbar: Copy ───────────────────────────────────────
        view.findViewById<View>(R.id.btn_copy).setOnClickListener {
            currentInputConnection?.performContextMenuAction(android.R.id.copy)
        }

        // ── Toolbar: Cut ────────────────────────────────────────
        view.findViewById<View>(R.id.btn_cut).setOnClickListener {
            currentInputConnection?.performContextMenuAction(android.R.id.cut)
        }

        // ── Clipboard panel callbacks ───────────────────────────
        clipboardPanel.onPaste = { entry ->
            // Suppress the echo clipboard event our own paste will generate
            clipboardManager.setLastPasted(entry.text)
            currentInputConnection?.commitText(entry.text, entry.text.length)
            clipboardPanel.hide()
            toast(getString(R.string.clip_pasted))
            scheduleSave()
        }
        clipboardPanel.onPin = { entry ->
            serviceScope.launch { clipboardManager.togglePin(entry.id) }
            val msg = if (entry.isPinned) getString(R.string.clip_unpinned)
                      else getString(R.string.clip_pinned)
            toast(msg)
        }
        clipboardPanel.onDelete = { entry ->
            serviceScope.launch { clipboardManager.deleteClip(entry.id) }
        }

        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        info ?: return
        currentPackage   = info.packageName ?: "unknown"
        isSensitiveField = isSensitiveInputType(info.inputType)
        currentWord.clear()
        typedBuffer.clear()
        
        draftKeysView.visibility = View.VISIBLE
        emojiKeyboardView.visibility = View.GONE
        
        draftKeysView.applyTheme()
        val palette = themeManager.getTheme()
        suggestionBar.applyTheme(palette)
        emojiKeyboardView.applyTheme(palette)
        
        if (this::rootKeyboardView.isInitialized) {
            val plasmaBg = rootKeyboardView.findViewById<View>(R.id.plasma_bg)
            if (palette.isWild) {
                plasmaBg?.visibility = View.VISIBLE
                rootKeyboardView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            } else {
                plasmaBg?.visibility = View.GONE
                rootKeyboardView.setBackgroundColor(palette.bg)
            }
            val btnIds = listOf(R.id.btn_restore, R.id.btn_clipboard, R.id.btn_select_all, R.id.btn_copy, R.id.btn_cut)
            for (id in btnIds) {
                rootKeyboardView.findViewById<android.widget.Button>(id)?.let { btn ->
                    btn.setTextColor(palette.keyText)
                    btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (palette.isWild) android.graphics.Color.argb(100, 20, 10, 40) else palette.keyModifier
                    )
                }
            }
        }
        
        draftKeysView.switchToQwerty()
        checkAutoCapitalize()
        
        // Pass Enter action to the view
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val action = if (noEnterAction) EditorInfo.IME_ACTION_NONE else (info.imeOptions and EditorInfo.IME_MASK_ACTION)
        draftKeysView.setEnterAction(action)

        // Capture anything copied while the keyboard was dismissed
        if (!isSensitiveField) clipboardManager.captureCurrentClipboard()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        if (!isSensitiveField) serviceScope.launch { saveCurrentText() }
        if (this::clipboardPanel.isInitialized) {
            clipboardPanel.hide()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        if (isSensitiveField) return

        // Swallow the selection event caused by our own autocorrect delete+commit so we
        // don't re-derive the word buffer from a mid-correction cursor position.
        if (suppressNextSelectionUpdate) {
            suppressNextSelectionUpdate = false
            return
        }

        val ic = currentInputConnection ?: return

        // If there's a selection, clear suggestions
        if (newSelStart != newSelEnd) {
            currentWord.clear()
            currentWordSuffixLength = 0
            suggestionBar.clearSuggestions()
            return
        }

        // Extract the word at the cursor
        val before = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(50, 0)?.toString() ?: ""

        var startIdx = before.length
        while (startIdx > 0 && before[startIdx - 1].isLetter()) {
            startIdx--
        }
        val prefix = before.substring(startIdx)

        var endIdx = 0
        while (endIdx < after.length && after[endIdx].isLetter()) {
            endIdx++
        }
        val suffix = after.substring(0, endIdx)

        if (prefix.isNotEmpty() || suffix.isNotEmpty()) {
            currentWord.clear()
            currentWord.append(prefix)
            currentWordSuffixLength = suffix.length
            updateSuggestions()
        } else {
            currentWord.clear()
            currentWordSuffixLength = 0
            
            if (before.endsWith(" ")) {
                val trimmedBefore = before.trimEnd()
                var prevWordStart = trimmedBefore.length
                while (prevWordStart > 0 && trimmedBefore[prevWordStart - 1].isLetter()) {
                    prevWordStart--
                }
                val previousWord = trimmedBefore.substring(prevWordStart)
                if (previousWord.isNotEmpty()) {
                    updateNextWordSuggestions(previousWord)
                    return
                }
            }
            
            suggestionBar.clearSuggestions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager.unregister()
        serviceScope.cancel()
    }

    // ══════════════════════════════════════════════════════════════
    // KeyboardActionListener — Phase 0
    // ══════════════════════════════════════════════════════════════

    override fun onPress(primaryCode: Int) {
        if (this::draftKeysView.isInitialized) {
            draftKeysView.performHapticFeedback(
                android.view.HapticFeedbackConstants.KEYBOARD_PRESS,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
    }

    override fun onRelease(primaryCode: Int) {
        if (this::draftKeysView.isInitialized) {
            draftKeysView.performHapticFeedback(
                android.view.HapticFeedbackConstants.KEYBOARD_RELEASE,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
    }

    override fun onKey(primaryCode: Int, x: Float, y: Float) {
        val ic = currentInputConnection ?: return

        // If a key other than backspace is pressed, reset the glide deletion state
        if (primaryCode != KeyboardLayout.CODE_BACKSPACE) {
            justGlided = false
            lastGlidedWordLength = 0
        }

        when (primaryCode) {

            // ── BACKSPACE ──────────────────────────────────────
            KeyboardLayout.CODE_BACKSPACE -> {
                if (justGlided && lastGlidedWordLength > 0) {
                    // Delete the entire glided word plus its trailing space
                    ic.deleteSurroundingText(lastGlidedWordLength + 1, 0)
                    val charsToRemove = lastGlidedWordLength + 1
                    if (typedBuffer.length >= charsToRemove) {
                        typedBuffer.delete(typedBuffer.length - charsToRemove, typedBuffer.length)
                    }
                    justGlided = false
                    lastGlidedWordLength = 0
                    updateSuggestions()
                    scheduleSave()
                    checkAutoCapitalize()
                    justAutoSpaced = false
                    return
                }

                if (justAutoSpaced) {
                    justAutoSpaced = false
                }

                if (currentWord.isNotEmpty()) currentWord.deleteCharAt(currentWord.length - 1)
                if (typedBuffer.isNotEmpty()) typedBuffer.deleteCharAt(typedBuffer.length - 1)
                
                val selectedText = ic.getSelectedText(0)
                if (!selectedText.isNullOrEmpty()) {
                    ic.commitText("", 0)
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
                
                updateSuggestions()
                scheduleSave()
                checkAutoCapitalize()
            }

            // ── SHIFT ──────────────────────────────────────────
            KeyboardLayout.CODE_SHIFT -> {
                val now = System.currentTimeMillis()
                if (now - lastShiftTime < 400) {
                    isCapsLock = true
                    isShiftActive = true
                } else {
                    if (isCapsLock) {
                        isCapsLock = false
                        isShiftActive = false
                    } else {
                        isShiftActive = !isShiftActive
                    }
                }
                lastShiftTime = now
                draftKeysView.setShifted(isShiftActive)
            }

            // ── EMOJI MODE ─────────────────────────────────────
            -10 -> {
                draftKeysView.visibility = View.GONE
                emojiKeyboardView.visibility = View.VISIBLE
            }

            // ── MODE CHANGE (?123 ↔ ABC) ────────────────────────
            KeyboardLayout.CODE_MODE_CHANGE -> {
                if (draftKeysView.isSymbolMode()) {
                    draftKeysView.switchToQwerty()
                } else {
                    draftKeysView.switchToSymbols()
                }
            }

            // ── CURSOR LEFT ←  ─────────────────────────────────
            KeyboardLayout.CODE_CURSOR_LEFT  -> moveCursor(-1)

            // ── CURSOR RIGHT → ─────────────────────────────────
            KeyboardLayout.CODE_CURSOR_RIGHT -> moveCursor(1)

            // ── SPACE ──────────────────────────────────────────
            32 -> {
                val now = System.currentTimeMillis()
                if (now - lastSpaceTime < 400) {
                    // Double-space → ". " + capitalize
                    autocorrectJob?.cancel()
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 2)
                    if (typedBuffer.isNotEmpty()) { typedBuffer.deleteCharAt(typedBuffer.length - 1); typedBuffer.append(". ") }
                    activateShift()
                } else {
                    // Run autocorrect on the completed word before committing space
                    val wordToCheck = currentWord.toString()
                    currentWord.clear() // Clear immediately to prevent race conditions
                    
                    if (wordToCheck.isNotEmpty() && !isSensitiveField) {
                        autocorrectJob?.cancel()
                        autocorrectJob = serviceScope.launch {
                            val correction = predictionEngine.autocorrect(wordToCheck)
                            if (correction != null && correction != wordToCheck) {
                                withContext(Dispatchers.Main) {
                                    val ic2 = currentInputConnection ?: return@withContext
                                    val beforeCursor = ic2.getTextBeforeCursor(wordToCheck.length, 0)?.toString()
                                    if (beforeCursor.equals(wordToCheck, ignoreCase = true)) {
                                        // Guard: suppress the onUpdateSelection event that
                                        // delete+commit will fire so the cursor stays in place.
                                        suppressNextSelectionUpdate = true
                                        ic2.deleteSurroundingText(wordToCheck.length, 0)
                                        ic2.commitText("$correction ", correction.length + 1)
                                        // Update typed buffer with corrected word
                                        val excess = wordToCheck.length
                                        if (typedBuffer.length >= excess) typedBuffer.delete(typedBuffer.length - excess, typedBuffer.length)
                                        typedBuffer.append("$correction ")
                                        suggestionBar.showAutocorrectUndo(beforeCursor ?: wordToCheck)
                                    } else {
                                        ic2.commitText(" ", 1)
                                        typedBuffer.append(' ')
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    currentInputConnection?.commitText(" ", 1)
                                    typedBuffer.append(' ')
                                }
                            }
                            // Learn the original typed word, not the correction
                            predictionEngine.learn(wordToCheck)
                            
                            // Trigger next word suggestions after processing the space
                            updateNextWordSuggestions(correction ?: wordToCheck)
                        }
                    } else {
                        ic.commitText(" ", 1)
                        typedBuffer.append(' ')
                    }
                    checkAutoCapitalize()
                    
                    lastCompletedWord = currentWord.toString()
                    currentWord.clear()
                    currentWordSuffixLength = 0
                }
                lastSpaceTime = now
                justAutoSpaced = false
                scheduleSave()
            }

            10 -> {
                if (!isSensitiveField) serviceScope.launch { saveCurrentText() }
                val options = currentInputEditorInfo?.imeOptions ?: 0
                val noEnterAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                val action = options and EditorInfo.IME_MASK_ACTION
                
                if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && action != 0) {
                    ic.performEditorAction(action)
                } else {
                    ic.commitText("\n", 1)
                }
                currentWord.clear()
                currentWordSuffixLength = 0
                checkAutoCapitalize()
            }

            // ── REGULAR CHARACTER ──────────────────────────────
            else -> {
                val str = String(Character.toChars(primaryCode))
                if (primaryCode <= 0xFFFF) {
                    var ch = primaryCode.toChar()
                    if (isShiftActive && ch.isLetter()) {
                        ch = ch.uppercaseChar()
                        if (!isCapsLock) {
                            isShiftActive = false
                            draftKeysView.setShifted(false)
                        }
                    }

                    // Smart Punctuation
                    val prevChar = ic.getTextBeforeCursor(1, 0)?.toString()?.firstOrNull()
                    when (ch) {
                        '(' -> { ic.commitText("()", 2); moveCursor(-1) }
                        '[' -> { ic.commitText("[]", 2); moveCursor(-1) }
                        '{' -> { ic.commitText("{}", 2); moveCursor(-1) }
                        '"' -> { 
                            if (prevChar == null || prevChar.isWhitespace()) {
                                ic.commitText("\"\"", 2); moveCursor(-1)
                            } else ic.commitText("\"", 1)
                        }
                        else -> {
                            // Smart Auto-Spacing logic for punctuation
                            if (justAutoSpaced && (ch == ',' || ch == '.' || ch == '?' || ch == '!' || ch == ':')) {
                                ic.deleteSurroundingText(1, 0)
                                ic.commitText(ch.toString() + " ", 2)
                                if (typedBuffer.isNotEmpty()) {
                                    typedBuffer.deleteCharAt(typedBuffer.length - 1)
                                }
                                typedBuffer.append(ch).append(' ')
                            } else {
                                justAutoSpaced = false
                                ic.commitText(ch.toString(), 1)
                                typedBuffer.append(ch)
                            }
                            if (ch.isLetter()) {
                                currentWord.append(ch.lowercaseChar())
                                if (!isSensitiveField) updateSuggestions()
                            } else {
                                currentWord.clear()
                                suggestionBar.clearSuggestions()
                            }
                        }
                    }
                } else {
                    ic.commitText(str, 1)
                    typedBuffer.append(str)
                    currentWord.clear()
                    currentWordSuffixLength = 0
                    suggestionBar.clearSuggestions()
                }

                // No longer automatically switching back to Qwerty upon emoji selection.
                // The user requested that the emoji view stay open until they explicitly go back.

                scheduleSave()
            }
        }
    }

    override fun onLongPressText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        typedBuffer.append(text)
        currentWord.clear()
        currentWordSuffixLength = 0
        suggestionBar.clearSuggestions()
        if (this::draftKeysView.isInitialized) {
            draftKeysView.expectedNextChars = emptySet()
        }
        vibrate()
        scheduleSave()
    }

    override fun onSymbolTapped(symbol: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(symbol, 1)
        vibrate()
        scheduleSave()
    }

    override fun onWordDelete() {
        deleteWordBeforeCursor()
        currentWord.clear()
        currentWordSuffixLength = 0
        justAutoSpaced = false
        scheduleSave()
    }

    override fun onCursorMove(dx: Int) {
        moveCursor(dx)
    }

    override fun onSwipeDelete(deleteCount: Int) {
        for (i in 0 until deleteCount) {
            deleteWordBeforeCursor()
        }
        currentWord.clear()
        currentWordSuffixLength = 0
        justAutoSpaced = false
        scheduleSave()
    }

    // ── Input Method Lifecycle ──────────────────────────────────────

    // ── Phase 2: Swipe gesture ────────────────────────────────────

    override fun onSwipe(points: List<GesturePoint>, layout: KeyboardLayout) {
        if (isSensitiveField) return
        serviceScope.launch {
            swipeMutex.withLock {
                // Build gesture index lazily if not yet built
                if (dictionary.wordsByFirstLast.isEmpty()) {
                    dictionary.buildIndex()
                }
                val personalWords = DraftDatabase.getInstance(this@KeyboardService)
                    .personalWordDao().getAll()
                val results = gestureDecoder.decode(points, layout, personalWords)
                withContext(Dispatchers.Main) {
                    if (results.isNotEmpty()) {
                        val top = results.first()
                        currentInputConnection?.commitText("$top ", top.length + 1)
                        currentWord.clear()
                        currentWordSuffixLength = 0
                        
                        lastGlidedWordLength = top.length
                        justGlided = true
                        
                        if (results.size > 1) suggestionBar.setSuggestions(results)
                        else suggestionBar.clearSuggestions()
                        // Learn the committed word
                        serviceScope.launch { predictionEngine.learn(top) }
                        checkAutoCapitalize()
                        scheduleSave()
                    }
                }
            }
        }
    }

    // ── Phase 3: Suggestion tap ───────────────────────────────────

    override fun onEmojiTapped(emoji: String) {
        if (emoji == "🔙") {
            emojiKeyboardView.visibility = View.GONE
            draftKeysView.visibility = View.VISIBLE
            return
        }

        val ic = currentInputConnection ?: return
        ic.commitText(emoji, 1)
        typedBuffer.append(emoji)
        currentWord.clear()
        currentWordSuffixLength = 0
        suggestionBar.clearSuggestions()
        
        vibrate()
        scheduleSave()
    }

    override fun onSuggestionTapped(word: String) {
        val cleanWord = word.trim('"', ' ')
        val ic = currentInputConnection ?: return
        // Delete the partial word typed so far, then commit the suggestion + space
        if (currentWord.isNotEmpty() || currentWordSuffixLength > 0) {
            ic.deleteSurroundingText(currentWord.length, currentWordSuffixLength)
            // Also trim buffer
            val excess = currentWord.length
            if (typedBuffer.length >= excess) typedBuffer.delete(typedBuffer.length - excess, typedBuffer.length)
        }
        ic.commitText("$cleanWord ", cleanWord.length + 1)
        typedBuffer.append("$cleanWord ")
        currentWord.clear()
        currentWordSuffixLength = 0
        justAutoSpaced = true
        suggestionBar.clearSuggestions()
        serviceScope.launch { predictionEngine.learn(cleanWord) }
        checkAutoCapitalize()
        scheduleSave()
        
        if (!isSensitiveField) {
            updateNextWordSuggestions(cleanWord)
        }
    }

    override fun onAutocorrectUndoTapped(originalWord: String) {
        val ic = currentInputConnection ?: return
        deleteWordBeforeCursor() // Safely removes the corrected word + trailing space
        ic.commitText("$originalWord ", originalWord.length + 1)
        
        // Fix the typed buffer
        val trimmed = typedBuffer.trimEnd()
        val lastSpace = trimmed.lastIndexOf(' ')
        if (lastSpace != -1) {
            typedBuffer.delete(lastSpace + 1, typedBuffer.length)
        } else {
            typedBuffer.clear()
        }
        typedBuffer.append("$originalWord ")
        
        suggestionBar.clearSuggestions()
        checkAutoCapitalize()
        scheduleSave()
    }

    // ══════════════════════════════════════════════════════════════
    // Prediction helpers (Phase 3)
    // ══════════════════════════════════════════════════════════════

    private fun updateSuggestions() {
        if (isSensitiveField || (currentWord.isEmpty() && currentWordSuffixLength == 0)) {
            suggestionBar.clearSuggestions()
            if (this::draftKeysView.isInitialized) draftKeysView.expectedNextChars = emptySet()
            return
        }
        val currentWordStr = currentWord.toString()
        val suffixStr = currentInputConnection?.getTextAfterCursor(currentWordSuffixLength, 0)?.toString() ?: ""
        val fullWordStr = currentWordStr + suffixStr

        // Show immediately for responsiveness
        suggestionBar.setCurrentWord(fullWordStr)
        
        predictionJob?.cancel()
        predictionJob = serviceScope.launch {
            val isKnown = predictionEngine.isKnownWord(fullWordStr)
            val suggestions = predictionEngine.getSuggestions(fullWordStr, 2)
            val nextChars = predictionEngine.predictNextChars(fullWordStr)
            
            withContext(Dispatchers.Main) {
                if (this@KeyboardService::draftKeysView.isInitialized) {
                    draftKeysView.expectedNextChars = nextChars
                }
                
                // If not in dictionary, quote it in the suggestion bar so user knows it will be saved
                val displayWord = if (isKnown) fullWordStr else "\"$fullWordStr\""
                suggestionBar.setCurrentWord(displayWord)

                if (suggestions.isNotEmpty()) suggestionBar.setSuggestions(suggestions)
                else suggestionBar.clearSuggestions()
            }
        }
    }

    private fun updateNextWordSuggestions(previousWord: String) {
        if (previousWord.isBlank()) return
        predictionJob?.cancel()
        predictionJob = serviceScope.launch {
            val suggestions = predictionEngine.nextWordSuggestions(previousWord)
            withContext(Dispatchers.Main) {
                if (suggestions.isNotEmpty()) suggestionBar.setSuggestions(suggestions)
                else suggestionBar.clearSuggestions()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Clipboard panel (Phase 4)
    // ══════════════════════════════════════════════════════════════

    private fun toggleClipboardPanel() {
        if (clipboardPanel.isOpen) {
            clipboardPanel.hide()
        } else {
            serviceScope.launch {
                try {
                    val clips = clipboardManager.getRecentClips()
                    withContext(Dispatchers.Main) {
                        clipboardPanel.setClips(clips)
                        clipboardPanel.show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        toast("Error: ${e.message}")
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Auto-capitalize (carried over from original)
    // ══════════════════════════════════════════════════════════════

    private fun checkAutoCapitalize() {
        val ic = currentInputConnection ?: return
        val capsMode = ic.getCursorCapsMode(currentInputEditorInfo?.inputType ?: 0)
        
        if (isCapsLock) return // Caps lock overrides auto-capitalize
        
        val shouldShift = capsMode != 0
        if (isShiftActive != shouldShift) {
            isShiftActive = shouldShift
            draftKeysView.setShifted(isShiftActive)
        }
    }

    private fun activateShift() {
        if (isCapsLock) return
        isShiftActive = true
        draftKeysView.setShifted(true)
    }

    // ══════════════════════════════════════════════════════════════
    // Word delete (carried over from original)
    // ══════════════════════════════════════════════════════════════

    private fun deleteWordBeforeCursor() {
        val ic = currentInputConnection ?: return
        val textBefore = ic.getTextBeforeCursor(50, 0)?.toString() ?: return
        if (textBefore.isEmpty()) return

        val trimmed = textBefore.trimEnd()
        if (trimmed.isEmpty()) {
            ic.deleteSurroundingText(textBefore.length, 0); return
        }
        val lastSpace = trimmed.lastIndexOf(' ')
        val charsToDelete = if (lastSpace == -1) textBefore.length
                            else textBefore.length - lastSpace - 1
        ic.deleteSurroundingText(charsToDelete, 0)
    }

    // ══════════════════════════════════════════════════════════════
    // Cursor movement
    // ══════════════════════════════════════════════════════════════

    private fun moveCursor(direction: Int) {
        val ic       = currentInputConnection ?: return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val newPos   = (extracted.selectionStart + direction).coerceIn(0, extracted.text?.length ?: 0)
        ic.setSelection(newPos, newPos)
    }

    // ══════════════════════════════════════════════════════════════
    // Auto-save (debounced — carried over from original)
    // ══════════════════════════════════════════════════════════════

    private fun scheduleSave() {
        if (isSensitiveField) return
        saveJob?.cancel()
        saveJob = serviceScope.launch {
            delay(1500)
            saveCurrentText()
        }
    }

    private suspend fun saveCurrentText() {
        val ic   = currentInputConnection
        // Try ExtractedText first (works on most apps)
        val text = ic?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
            // Fallback: use our locally tracked typed buffer (for apps that block extraction)
            ?.ifBlank { typedBuffer.toString() }
            ?: typedBuffer.toString().takeIf { it.isNotBlank() }
            ?: return

        // Min length guard (lowered from 5 to 2)
        if (text.length < 2) return

        // Deduplication: skip if text hasn't changed since last save
        if (text == lastSavedText) return
        lastSavedText = text

        repository.saveDraft(
            DraftEntity(
                appPackageName = currentPackage,
                textContent    = text,
                timestamp      = System.currentTimeMillis()
            )
        )
    }

    // ══════════════════════════════════════════════════════════════
    // Draft restore (carried over from original)
    // ══════════════════════════════════════════════════════════════

    private fun restoreLastDraft() {
        serviceScope.launch {
            val draft = repository.getLatestDraft(currentPackage)
            withContext(Dispatchers.Main) {
                val ic = currentInputConnection ?: return@withContext
                if (draft != null) {
                    ic.performContextMenuAction(android.R.id.selectAll)
                    ic.commitText(draft.textContent, draft.textContent.length)
                    toast(getString(R.string.draft_restored))
                } else {
                    toast(getString(R.string.no_draft_found))
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Haptic feedback (carried over from original)
    // ══════════════════════════════════════════════════════════════

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(22, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(22L)
            }
        } catch (_: Exception) { }
    }

    // ══════════════════════════════════════════════════════════════
    // Sensitive field detection (carried over from original)
    // ══════════════════════════════════════════════════════════════

    override fun onClipboardButtonTapped() {
        toggleClipboardPanel()
    }

    override fun onPasteLastClip() {
        val ic = currentInputConnection ?: return
        serviceScope.launch {
            val latestClip = clipboardManager.getRecentClips().firstOrNull()
            withContext(Dispatchers.Main) {
                val textToPaste = latestClip?.text
                    ?: run {
                        // Fallback: paste whatever is on the system clipboard right now
                        val systemClip = (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .primaryClip
                            ?.getItemAt(0)
                            ?.coerceToText(this@KeyboardService)
                            ?.toString()
                        systemClip
                    } ?: return@withContext
                clipboardManager.setLastPasted(textToPaste)
                ic.commitText(textToPaste, textToPaste.length)
                typedBuffer.append(textToPaste)
                currentWord.clear()
                currentWordSuffixLength = 0
                suggestionBar.clearSuggestions()
                scheduleSave()
            }
        }
    }

    override fun onSettingsButtonTapped() {
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun isSensitiveInputType(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
               variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
               variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
               variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    // ── Convenience accessor ──────────────────────────────────────
    private fun personalWordDao() =
        DraftDatabase.getInstance(this).personalWordDao()

    // ── Toast shorthand ───────────────────────────────────────────
    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        
    private fun loadEmojisAsync(): List<String> {
        val emojis = mutableListOf<String>("🔙")
        try {
            val inputStream = assets.open("emoji_index.txt")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val parts = line!!.split("\t")
                if (parts.isNotEmpty()) emojis.add(parts[0])
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
            emojis.addAll(listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇"))
        }
        return emojis
    }
}
