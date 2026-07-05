package com.draftkeys.keyboard.ui

import android.graphics.RectF

/**
 * Represents a single key on the DraftKeys keyboard canvas.
 *
 * Instances are created by [KeyboardLayout.build] which populates [bounds]
 * from the screen width at layout time. Everything else is fixed at definition.
 *
 * @param code       Key code: positive → Unicode character (e.g. 'a'.code = 97);
 *                   negative → special action (see [KeyboardLayout] constants).
 * @param label      String rendered on the key face.
 * @param bounds     Pixel bounding box on the canvas — populated by [KeyboardLayout].
 * @param isModifier True for action/modifier keys: ⇧ ⌫ SPACE ↵ ?123 ABC ← →
 * @param alternates Optional long-press popup alternatives, e.g. ["é","è","ê","ë"] for 'e'.
 */
data class KeyModel(
    val code: Int,
    val label: String,
    val bounds: RectF = RectF(),
    val isModifier: Boolean = false,
    val secondaryLabel: String? = null,
    val alternates: List<String> = emptyList()
)
