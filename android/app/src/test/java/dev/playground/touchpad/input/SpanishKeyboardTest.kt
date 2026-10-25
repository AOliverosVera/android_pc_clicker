package dev.playground.touchpad.input

import dev.playground.touchpad.net.Protocol.KeyStroke
import dev.playground.touchpad.net.Protocol.Mods
import dev.playground.touchpad.net.Protocol.Special
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpanishKeyboardTest {
    private val kb = SpanishKeyboard()
    private val allKeys = (SpanishLayout.rows + listOf(SpanishLayout.functionRow)).flatten()

    private fun printable(c: Char) = allKeys.filterIsInstance<KeyDef.Printable>().first { it.base == c }
    private fun modifier(m: Mod) = allKeys.filterIsInstance<KeyDef.Modifier>().first { it.modifier == m }
    private fun action(code: Int) = allKeys.filterIsInstance<KeyDef.Action>().first { it.code == code }

    /** Taps each character's key (by its unshifted character) and returns the text sent. */
    private fun typed(keys: String): String = buildString {
        for (c in keys) tap(printable(c)).forEach { appendCodePoint(it.code) }
    }

    private fun tap(key: KeyDef): List<KeyStroke> = kb.press(key).also { kb.release(key) }

    @Test fun everyRowIsFifteenUnitsWide() {
        for (row in SpanishLayout.rows) assertEquals(15f, row.sumOf { it.width.toDouble() }.toFloat(), 0.001f)
    }

    @Test fun typesLettersAndSpanishCharacters() {
        val start = typed("hola ñand")
        tap(printable('´'))
        assertEquals("hola ñandú", start + typed("u"))
        assertEquals("ç", typed("ç"))
    }

    @Test fun shiftAppliesToOneKey() {
        tap(modifier(Mod.SHIFT))
        assertTrue(kb.isOn(Mod.SHIFT))
        assertEquals("Ña", typed("ña"))
        assertFalse(kb.isOn(Mod.SHIFT))
        tap(modifier(Mod.SHIFT))
        assertEquals("!", typed("1"))
        tap(modifier(Mod.SHIFT))
        assertEquals("¿", typed("¡"))
    }

    @Test fun tappingShiftAgainTurnsItOff() {
        tap(modifier(Mod.SHIFT))
        tap(modifier(Mod.SHIFT))
        assertEquals("a", typed("a"))
    }

    @Test fun holdingShiftKeepsItOnForSeveralKeys() {
        val shift = modifier(Mod.SHIFT)
        kb.press(shift)
        assertEquals("ABC", typed("abc"))
        kb.release(shift)
        assertEquals("d", typed("d"))
    }

    @Test fun capsLockAffectsLettersOnly() {
        tap(modifier(Mod.CAPS_LOCK))
        assertEquals("AÑ1", typed("añ1"))
        tap(modifier(Mod.SHIFT))
        assertEquals("a", typed("a"))
        tap(modifier(Mod.CAPS_LOCK))
        assertEquals("a", typed("a"))
    }

    @Test fun altGrTypesTheThirdSymbol() {
        for ((key, expected) in listOf('2' to "@", '3' to "#", 'e' to "€", '+' to "]", 'º' to "\\", '´' to "{")) {
            tap(modifier(Mod.ALT_GR))
            assertEquals(expected, typed(key.toString()))
        }
        // A key without a third symbol types its normal one.
        tap(modifier(Mod.ALT_GR))
        assertEquals("q", typed("q"))
    }

    @Test fun accentsCombineWithTheNextLetter() {
        fun accent(key: Char, shift: Boolean = false) {
            if (shift) tap(modifier(Mod.SHIFT))
            assertEquals(emptyList<KeyStroke>(), tap(printable(key)))
        }
        accent('´'); assertEquals("á", typed("a"))
        accent('´'); tap(modifier(Mod.SHIFT)); assertEquals("É", typed("e"))
        accent('`'); assertEquals("à", typed("a"))
        accent('`', shift = true); assertEquals("ê", typed("e")) // ^
        accent('´', shift = true); assertEquals("ü", typed("u")) // ¨
        accent('´'); assertEquals("´", typed(" "))
        accent('´'); assertEquals("´b", typed("b"))
    }

    @Test fun backspaceCancelsAPendingAccent() {
        tap(printable('´'))
        assertEquals(emptyList<KeyStroke>(), tap(action(Special.BACKSPACE)))
        assertEquals("a", typed("a"))
        assertEquals(listOf(KeyStroke.special(Special.BACKSPACE)), tap(action(Special.BACKSPACE)))
    }

    @Test fun shortcutsCarryTheirModifiers() {
        tap(modifier(Mod.CTRL))
        assertEquals(listOf(KeyStroke.text('c'.code, Mods.CTRL)), tap(printable('c')))
        assertFalse("one-shot", kb.isOn(Mod.CTRL))

        tap(modifier(Mod.CTRL))
        tap(modifier(Mod.SHIFT))
        assertEquals(listOf(KeyStroke.text('T'.code, Mods.CTRL or Mods.SHIFT)), tap(printable('t')))

        tap(modifier(Mod.ALT))
        assertEquals(listOf(KeyStroke.special(Special.f(4), Mods.ALT)), tap(action(Special.f(4))))

        tap(modifier(Mod.SHIFT))
        assertEquals(listOf(KeyStroke.special(Special.LEFT, Mods.SHIFT)), tap(action(Special.LEFT)))
    }

    @Test fun holdingAltKeepsItForSeveralTabs() {
        val alt = modifier(Mod.ALT)
        kb.press(alt)
        val tab = KeyStroke.special(Special.TAB, Mods.ALT)
        assertEquals(listOf(tab), tap(action(Special.TAB)))
        assertEquals(listOf(tab), tap(action(Special.TAB)))
        kb.release(alt)
        assertEquals(listOf(KeyStroke.special(Special.TAB)), tap(action(Special.TAB)))
    }

    @Test fun winTwiceOpensStart() {
        assertEquals(emptyList<KeyStroke>(), tap(modifier(Mod.WIN)))
        assertEquals(listOf(KeyStroke.special(Special.MODIFIERS_ONLY, Mods.WIN)), tap(modifier(Mod.WIN)))
        assertFalse(kb.isOn(Mod.WIN))
        tap(modifier(Mod.WIN))
        assertEquals(listOf(KeyStroke.text('e'.code, Mods.WIN)), tap(printable('e')))
    }

    @Test fun accentsDontApplyToShortcuts() {
        tap(printable('´'))
        tap(modifier(Mod.CTRL))
        assertEquals(listOf(KeyStroke.text('a'.code, Mods.CTRL)), tap(printable('a')))
        assertEquals("a", typed("a"))
    }
}
