// SPDX-FileCopyrightText: 2026 miner7222
// SPDX-License-Identifier: Apache-2.0

package io.github.miner7222.gamespace

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import java.util.Locale

/**
 * A label that shows [short] only when [full] does not fit in [maxLines] at the width it is given,
 * and ellipsizes when even [short] does not fit. The fit follows the current font scale and
 * language. Assistive technology always reads [full].
 */
@Composable
fun FitLabel(
    full: String,
    short: String?,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
    maxLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
) {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val alternative = short?.takeIf { it.isNotEmpty() && it != full }
    val measured = style.merge(TextStyle(fontSize = fontSize, fontWeight = fontWeight,
        letterSpacing = letterSpacing, lineHeight = lineHeight, textAlign = textAlign))
    Layout(content = {
        FitText(full, color, fontSize, fontWeight, letterSpacing, lineHeight, textAlign, maxLines, style)
        if (alternative != null) FitText(alternative, color, fontSize, fontWeight, letterSpacing, lineHeight,
            textAlign, maxLines, style)
    }, modifier = modifier.clearAndSetSemantics { text = AnnotatedString(full) }) { measurables, constraints ->
        val useShort = alternative != null && !measurer.fits(full, measured, maxLines, constraints, this, layoutDirection)
        val placeable = measurables[if (useShort) 1 else 0].measure(constraints)
        layout(placeable.width, placeable.height,
            mapOf(FirstBaseline to placeable[FirstBaseline], LastBaseline to placeable[LastBaseline])) {
            placeable.place(0, 0)
        }
    }
}

@Composable
private fun FitText(text: String, color: Color, fontSize: TextUnit, fontWeight: FontWeight?, letterSpacing: TextUnit,
    lineHeight: TextUnit, textAlign: TextAlign, maxLines: Int, style: TextStyle) {
    Text(text, color = color, fontSize = fontSize, fontWeight = fontWeight, letterSpacing = letterSpacing,
        lineHeight = lineHeight, textAlign = textAlign, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = style)
}

/** Whether [text] lays out in [maxLines] at the width [constraints] allow, without breaking inside a word. */
private fun TextMeasurer.fits(text: String, style: TextStyle, maxLines: Int, constraints: Constraints,
    density: Density, direction: LayoutDirection): Boolean {
    if (!constraints.hasBoundedWidth) return true
    val width = constraints.maxWidth
    val result = measure(text = text, style = style, overflow = TextOverflow.Clip, softWrap = true, maxLines = maxLines,
        constraints = Constraints(maxWidth = width), layoutDirection = direction, density = density)
    if (result.hasVisualOverflow) return false
    if (maxLines == 1) return true
    // Wrapping may split a long word apart without dropping any line, which also reads as cut off.
    return text.split(' ', '\n', '-').none { word ->
        word.isNotEmpty() && !word.isBreakableAnywhere() && measure(text = word, style = style, softWrap = false,
            maxLines = 1, layoutDirection = direction, density = density).size.width > width
    }
}

// Han and kana text may break between any two characters, so it never leaves a word cut in half.
private fun String.isBreakableAnywhere(): Boolean = any {
    when (Character.UnicodeScript.of(it.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
        else -> false
    }
}

/**
 * The short form stored in [id], or null while the current language has no short form of its own.
 * An untranslated string falls back to the English default, which must not appear in another language.
 */
fun Context.shortLabel(@StringRes id: Int): String? {
    if (id == 0) return null
    val localized = getString(id)
    val config = resources.configuration
    if (config.locales.isEmpty() || config.locales[0].language == "en") return localized
    val base = createConfigurationContext(Configuration(config).apply { setLocale(Locale.ROOT) }).getString(id)
    return localized.takeIf { it != base }
}

@Composable
fun shortLabelResource(@StringRes id: Int): String? {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    return remember(id, config) { context.shortLabel(id) }
}
