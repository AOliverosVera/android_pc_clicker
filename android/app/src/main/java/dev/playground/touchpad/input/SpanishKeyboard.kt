package dev.playground.touchpad.input

import dev.playground.touchpad.net.Protocol.KeyStroke
import dev.playground.touchpad.net.Protocol.Mods
import dev.playground.touchpad.net.Protocol.Special
import java.text.Normalizer

/** A key of the on-screen keyboard. [width] is relative to a letter key. */
sealed interface KeyDef {
    val width: Float

    /** Types [base]; [shift] with Shift (or Caps Lock, for letters); [altGr] with AltGr. */
    data class Printable(
        val base: Char,
        val shift: Char,
        val altGr: Char? = null,
        override val width: Float = 1f,
    ) : KeyDef

    /** A key that doesn't type text, such as Enter or an arrow. */
    data class Action(
        val code: Int,
        val label: String,
        override val width: Float = 1f,
        val repeats: Boolean = true,
    ) : KeyDef

    data class Modifier(val modifier: Mod, val label: String, override val width: Float = 1f) : KeyDef
}

enum class Mod(val bit: Int) {
    SHIFT(Mods.SHIFT),
    CTRL(Mods.CTRL),
    ALT(Mods.ALT),
    WIN(Mods.WIN),
    /** Picks the third symbol of a key (@, #, €, [ ...); the PC never sees it. */
    ALT_GR(0),
    CAPS_LOCK(0),
}

/** The Spanish (Spain) keyboard layout, row by row. Each row is 15 units wide. */
object SpanishLayout {
    private fun key(base: Char, shift: Char, altGr: Char? = null) = KeyDef.Printable(base, shift, altGr)
    private fun letter(c: Char, altGr: Char? = null) = KeyDef.Printable(c, c.uppercaseChar(), altGr)
    private fun letters(s: String) = s.map { letter(it) }
    private fun action(code: Int, label: String, width: Float = 1f, repeats: Boolean = true) =
        KeyDef.Action(code, label, width, repeats)

    /** Esc, F1–F12 and editing keys, drawn as a shorter row on top. */
    val functionRow: List<KeyDef> =
        listOf(action(Special.ESCAPE, "Esc", repeats = false)) +
            (1..12).map { action(Special.f(it), "F$it", repeats = false) } +
            listOf(action(Special.HOME, "Inicio"), action(Special.END, "Fin"), action(Special.DELETE, "Supr"))

    val rows: List<List<KeyDef>> = listOf(
        listOf(
            key('º', 'ª', '\\'), key('1', '!', '|'), key('2', '"', '@'), key('3', '·', '#'),
            key('4', '$', '~'), key('5', '%', '€'), key('6', '&', '¬'), key('7', '/'), key('8', '('),
            key('9', ')'), key('0', '='), key('\'', '?'), key('¡', '¿'),
            action(Special.BACKSPACE, "⌫", 2f),
        ),
        listOf(action(Special.TAB, "⇥", 1.5f)) + letters("qw") + letter('e', '€') + letters("rtyuiop") +
            listOf(key('`', '^', '['), key('+', '*', ']'), action(Special.ENTER, "↵", 1.5f)),
        listOf(KeyDef.Modifier(Mod.CAPS_LOCK, "⇪", 1.75f)) + letters("asdfghjklñ") +
            listOf(key('´', '¨', '{'), key('ç', 'Ç', '}'), action(Special.ENTER, "↵", 1.25f)),
        listOf(KeyDef.Modifier(Mod.SHIFT, "⇧", 1.25f), key('<', '>')) + letters("zxcvbnm") +
            listOf(key(',', ';'), key('.', ':'), key('-', '_'), KeyDef.Modifier(Mod.SHIFT, "⇧", 2.75f)),
        listOf(
            KeyDef.Modifier(Mod.CTRL, "Ctrl", 1.5f),
            KeyDef.Modifier(Mod.WIN, "⊞", 1.25f),
            KeyDef.Modifier(Mod.ALT, "Alt", 1.25f),
            KeyDef.Printable(' ', ' ', width = 4.75f),
            KeyDef.Modifier(Mod.ALT_GR, "AltGr", 1.25f),
            action(Special.LEFT, "←", 1.25f),
            action(Special.UP, "↑", 1.25f),
            action(Special.DOWN, "↓", 1.25f),
            action(Special.RIGHT, "→", 1.25f),
        ),
    )
}

/**
 * The keyboard's state: which modifiers are on, Caps Lock, and an accent
 * waiting for its letter. Turns key presses into [KeyStroke]s for the PC.
 *
 * Modifiers work like on a phone keyboard: tap one and it applies to the next
 * key, tap it again to turn it off. Holding a modifier with one finger keeps
 * it on for every key typed with another. Tapping Win twice presses Win alone.
 *
 * Accents (´ ` ^ ¨) are dead keys, as on a real Spanish keyboard: ´ then a
 * types á. They are combined here, so the PC receives the finished letter.
 */
class SpanishKeyboard {
    private val active = mutableSetOf<Mod>()
    /** Modifiers whose key is held down, and whether a key was typed meanwhile. */
    private val held = mutableMapOf<Mod, Boolean>()

    var capsLock = false
        private set

    /** The accent waiting for its letter, if any. */
    var pendingAccent: Char? = null
        private set

    fun isOn(mod: Mod): Boolean = if (mod == Mod.CAPS_LOCK) capsLock else mod in active

    /** What [key] types right now; also its label. */
    fun output(key: KeyDef.Printable): Char {
        if (Mod.ALT_GR in active) return key.altGr ?: key.base
        val shifted = Mod.SHIFT in active
        val upper = if (key.base.isLetter()) shifted != capsLock else shifted
        return if (upper) key.shift else key.base
    }

    /** A key went down. Returns what to send to the PC (often nothing). */
    fun press(key: KeyDef): List<KeyStroke> = when (key) {
        is KeyDef.Modifier -> modifierDown(key.modifier)
        is KeyDef.Printable -> finish(type(key))
        is KeyDef.Action -> finish(action(key.code))
    }

    /** A key went up. Only matters for modifiers. */
    fun release(key: KeyDef) {
        if (key is KeyDef.Modifier && held.remove(key.modifier) == true) active.remove(key.modifier)
    }

    private fun modifierDown(mod: Mod): List<KeyStroke> {
        if (mod == Mod.CAPS_LOCK) {
            capsLock = !capsLock
            return emptyList()
        }
        if (mod in active) {
            active.remove(mod)
            if (mod == Mod.WIN) {
                return finish(listOf(KeyStroke.special(Special.MODIFIERS_ONLY, commandMods() or Mods.WIN)))
            }
            return emptyList()
        }
        active.add(mod)
        held[mod] = false
        return emptyList()
    }

    private fun type(key: KeyDef.Printable): List<KeyStroke> {
        val c = output(key)
        val command = commandMods()
        if (command != 0) {
            // A shortcut such as Ctrl+C: accents don't apply.
            pendingAccent = null
            return listOf(KeyStroke.text(c.code, command or shiftMod()))
        }
        val accent = pendingAccent
        if (c in ACCENTS) {
            pendingAccent = c
            return if (accent != null) listOf(text(accent)) else emptyList()
        }
        pendingAccent = null
        if (accent == null) return listOf(text(c))
        compose(accent, c)?.let { return listOf(text(it)) }
        // Like Windows: accent + space types the accent; otherwise both.
        return if (c == ' ') listOf(text(accent)) else listOf(text(accent), text(c))
    }

    private fun action(code: Int): List<KeyStroke> {
        val accent = pendingAccent
        pendingAccent = null
        // Backspace right after an accent just cancels the accent.
        if (accent != null && code == Special.BACKSPACE && commandMods() == 0) return emptyList()
        return listOf(KeyStroke.special(code, commandMods() or shiftMod()))
    }

    private fun commandMods(): Int =
        active.filter { it == Mod.CTRL || it == Mod.ALT || it == Mod.WIN }.fold(0) { bits, m -> bits or m.bit }

    private fun shiftMod(): Int = if (Mod.SHIFT in active) Mods.SHIFT else 0

    /** Tapped modifiers end after one key; held ones stay until released. */
    private fun finish(out: List<KeyStroke>): List<KeyStroke> {
        for (mod in active.toList()) {
            if (mod in held) held[mod] = true else active.remove(mod)
        }
        return out
    }

    private fun text(c: Char) = KeyStroke.text(c.code)

    private companion object {
        /** Each accent's combining mark. */
        val ACCENTS = mapOf('´' to '́', '`' to '̀', '^' to '̂', '¨' to '̈')

        /** á from ´ and a, ü from ¨ and u...; null if there is no such letter. */
        fun compose(accent: Char, c: Char): Char? {
            val mark = ACCENTS[accent] ?: return null
            val composed = Normalizer.normalize("$c$mark", Normalizer.Form.NFC)
            return composed.singleOrNull()
        }
    }
}
