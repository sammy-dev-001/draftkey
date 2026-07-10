package com.draftkeys.keyboard.ui

import com.draftkeys.keyboard.gesture.GesturePoint

/**
 * Replaces [android.inputmethodservice.KeyboardView.OnKeyboardActionListener].
 *
 * [DraftKeysView] calls these methods; [KeyboardService] implements them.
 */
interface KeyboardActionListener {

    /**
     * A key was tapped (finger down + up without meaningful movement).
     * @param primaryCode The key's [KeyModel.code].
     * @param x The exact raw x coordinate of the tap (for spatial modeling)
     * @param y The exact raw y coordinate of the tap (for spatial modeling)
     */
    fun onKey(primaryCode: Int, x: Float = -1f, y: Float = -1f)

    /**
     * The long-press backspace threshold was reached — [KeyboardService]
     * should delete the whole word before the cursor.
     */
    fun onWordDelete()

    /**
     * The user swiped horizontally on the spacebar.
     * @param dx Distance swiped (typically scaled to characters).
     */
    fun onCursorMove(dx: Int)

    /**
     * The user swiped horizontally from the backspace key.
     * @param deleteCount Number of words or characters to delete based on distance.
     */
    fun onSwipeDelete(deleteCount: Int)

    /** Finger pressed down on a key — used for haptic feedback. */
    fun onPress(primaryCode: Int)

    /** Finger lifted from a key. */
    fun onRelease(primaryCode: Int)

    /**
     * A swipe gesture finished with enough displacement to be a word gesture.
     * [KeyboardService] should dispatch this to [com.draftkeys.keyboard.gesture.GestureDecoder]
     * on a background coroutine, then commit the top result.
     *
     * @param points Ordered list of touch points sampled during the swipe.
     * @param layout The current keyboard layout (needed to map points → keys).
     */
    fun onSwipe(points: List<GesturePoint>, layout: KeyboardLayout)

    /**
     * A suggestion chip in [SuggestionBarView] was tapped.
     * @param word The selected suggestion word.
     */
    fun onSuggestionTapped(word: String)

    /**
     * A key was long-pressed to emit its secondary label.
     */
    fun onLongPressText(text: String)
    
    /**
     * A symbol key was tapped.
     */
    fun onSymbolTapped(symbol: String)
    
    /**
     * An emoji was tapped on the Emoji Keyboard.
     */
    fun onEmojiTapped(emoji: String)

    /**
     * The undo autocorrect chip was tapped in SuggestionBarView.
     */
    fun onAutocorrectUndoTapped(originalWord: String)

    /**
     * The settings button in the suggestion bar was tapped.
     */
    fun onSettingsButtonTapped()

    /**
     * The clipboard button in the suggestion bar was tapped.
     * Pastes the most recent clipboard item directly.
     */
    fun onPasteLastClip()

    /**
     * The clipboard panel toggle button (toolbar) was tapped.
     */
    fun onClipboardButtonTapped()
    
    /**
     * The voice input microphone button was tapped.
     */
    fun onVoiceTapped()
}
