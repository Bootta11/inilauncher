package app.olauncher.extras.search

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols

/**
 * A small calculator for the search box: + − × ÷ % ^, brackets and decimals. Recursive descent with
 * exact decimal maths (BigDecimal), no eval and no library. Pure and unit tested.
 *
 * It would rather show no answer than a wrong one, and never throws:
 * - [evaluate] answers only complete sums with at least one operator, so plain numbers stay searches.
 * - Unclear numbers ("1,000" where the comma groups thousands, "1.2.3") give no answer.
 * - Long (over [MAX_LENGTH]) or deeply nested (over [MAX_DEPTH]) input, division by zero, results
 *   beyond 10^±10000 and anything not a real number (√−1) give no answer.
 *
 * [couldBeCalculation] tells whether the search box may still be heading for a sum, so the app list
 * doesn't open an app by itself while you type one.
 */
object ExpressionEvaluator {

    /** Longer input is a search, not a sum. */
    const val MAX_LENGTH = 100

    /** Nested brackets, powers and signs allowed. */
    const val MAX_DEPTH = 32

    /** Working precision; answers are shown rounded to [SHOWN_DIGITS]. */
    private val WORK = MathContext(34, RoundingMode.HALF_EVEN)
    private const val SHOWN_DIGITS = 15

    /** Whole powers up to this are worked out exactly; others go through Math.pow. */
    private const val MAX_WHOLE_EXPONENT = 9999

    /** Results beyond 10^±this are no answer. */
    private const val MAX_MAGNITUDE = 10_000L

    private const val OPERATORS = "+-*/%^"

    /** What a sum can start with (after [normalize]). */
    private const val SUM_STARTS = "0123456789(-."

    /**
     * The answer to [input], e.g. "36" for "12*3", or null when it isn't a complete sum.
     * [groupingSeparator] is the phone's thousands separator (see [number]).
     */
    fun evaluate(input: String, groupingSeparator: Char = localGroupingSeparator()): String? {
        if (input.length > MAX_LENGTH) return null
        return try {
            val text = normalize(input) ?: return null
            // A sum needs an operator: "42" and "-42" stay searches
            if (text.trimStart('-', '+').none { it in OPERATORS }) return null
            val closed = closeBrackets(text) ?: return null
            val parser = Parser(closed, groupingSeparator)
            val value = parser.expression()
            if (!parser.atEnd()) null else format(value)
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    /**
     * Whether [input] is or may become a sum: it starts with a digit, "(", "-" or "." and has only
     * calculator characters. "1", "12*" and "(2+3" do; "1p", "spotify" and "+" don't. Never throws.
     */
    fun couldBeCalculation(input: String): Boolean {
        if (input.length > MAX_LENGTH) return false
        return try {
            val text = normalize(input)
            !text.isNullOrEmpty() && text[0] in SUM_STARTS
        } catch (e: Exception) {
            false
        }
    }

    /**
     * [input] as plain ASCII ("0-9 . , + - * / % ^ ( )", no spaces), or null when it has anything
     * else. Takes the other ways people type sums: × · x ÷ − – ** ² ³ [ ] { }, full-width signs,
     * digits of any script (۱۲ → 12), the Arabic decimal sign, a leading "=" and trailing "=".
     */
    internal fun normalize(input: String): String? {
        var text = input.trim()
        if (text.startsWith('=')) text = text.substring(1)
        text = text.trimEnd { it == '=' || it.isWhitespace() }
        val out = StringBuilder(text.length + 4)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c in '0'..'9' -> out.append(c)
                c.isWhitespace() || Character.isSpaceChar(c) || c == '٬' -> {} // spaces, Arabic thousands sign
                Character.isDigit(c) -> {
                    val digit = Character.digit(c, 10)
                    if (digit < 0) return null
                    out.append('0' + digit)
                }
                c == '.' || c == ',' -> out.append(c)
                c == '٫' -> out.append('.') // Arabic decimal sign
                c == '*' && text.getOrNull(i + 1) == '*' -> { out.append('^'); i++ }
                c in "*×·∙⋅xX＊" -> out.append('*')
                c in "/÷∕⁄／" -> out.append('/')
                c in "+＋" -> out.append('+')
                c in "-−–—－" -> out.append('-')
                c in "%٪％" -> out.append('%')
                c == '^' -> out.append('^')
                c == '²' -> out.append("^2")
                c == '³' -> out.append("^3")
                c in "([{（" -> out.append('(')
                c in ")]}）" -> out.append(')')
                else -> return null
            }
            i++
        }
        return out.toString()
    }

    /** [text] with its open brackets closed at the end ("(1+2" → "(1+2)"); null when a ")" has no "(". */
    private fun closeBrackets(text: String): String? {
        var open = 0
        for (c in text) {
            if (c == '(') open++
            else if (c == ')' && --open < 0) return null
        }
        return if (open == 0) text else text + ")".repeat(open)
    }

    /** 15 significant digits; plain from 10^-6 to 10^15 ("0.3", "36"), scientific outside ("1E+20"). */
    private fun format(value: BigDecimal): String {
        val shown = value.round(MathContext(SHOWN_DIGITS, RoundingMode.HALF_UP)).stripTrailingZeros()
        if (shown.signum() == 0) return "0"
        return if (magnitude(shown) in -6L..14L) shown.toPlainString() else shown.toString()
    }

    /** The power of ten of [value]'s first digit: 2 for 123, -3 for 0.00123. */
    private fun magnitude(value: BigDecimal): Long = value.precision().toLong() - value.scale() - 1

    /**
     * Whether [number] (one separator at most) could be thousands grouping: 1–3 digits not starting with
     * 0, then [grouping], then exactly 3 digits. "1,000" yes; "0,500", "1,5" and "1234,000" no.
     */
    internal fun looksGrouped(number: String, grouping: Char): Boolean {
        val at = number.indexOf(grouping)
        if (at !in 1..3 || number[0] == '0') return false
        return number.length - at - 1 == 3 && number.all { it in '0'..'9' || it == grouping }
    }

    private fun localGroupingSeparator(): Char = try {
        DecimalFormatSymbols.getInstance().groupingSeparator
    } catch (e: Exception) {
        ','
    }

    private class Parser(private val s: String, private val grouping: Char) {
        private var i = 0
        private var depth = 0

        /** A value and whether it's a lone percentage ("10%"), for "200+10%" = 220. */
        private class Part(val value: BigDecimal, val percent: Boolean)

        /** Runs [block] one nesting level deeper; too deep throws, which [evaluate] turns into null. */
        private inline fun <T> nested(block: () -> T): T {
            require(++depth <= MAX_DEPTH)
            try {
                return block()
            } finally {
                depth--
            }
        }

        fun atEnd() = i >= s.length

        private fun peek() = if (i < s.length) s[i] else '\u0000'

        /** [value], or an exception when it's out of range. */
        private fun checked(value: BigDecimal): BigDecimal {
            require(value.signum() == 0 || magnitude(value) in -MAX_MAGNITUDE..MAX_MAGNITUDE)
            return value
        }

        // expression := term (('+' | '-') term)*     "a + b%" adds b% of a, like phone calculators
        fun expression(): BigDecimal {
            var value = term().value
            while (peek() == '+' || peek() == '-') {
                val op = s[i++]
                val right = term()
                val amount = if (right.percent) checked(value.multiply(right.value, WORK)) else right.value
                value = checked(if (op == '+') value.add(amount, WORK) else value.subtract(amount, WORK))
            }
            return value
        }

        // term := factor (('*' | '/' | '%') factor)*     a '%' between two values is the remainder
        private fun term(): Part {
            val first = factor()
            var value = first.value
            var percent = first.percent
            while (peek() == '*' || peek() == '/' || peek() == '%') {
                val op = s[i++]
                val right = factor().value
                percent = false
                value = checked(when (op) {
                    '*' -> value.multiply(right, WORK)
                    '/' -> value.divide(right, WORK) // throws on zero
                    else -> value.remainder(right, WORK) // throws on zero
                })
            }
            return Part(value, percent)
        }

        // factor := unary '%'*     a '%' before the end, an operator or ')' is a percentage
        private fun factor(): Part {
            var value = unary()
            var percent = false
            while (peek() == '%' && (i + 1 >= s.length || s[i + 1] in "+-*/)%")) {
                i++
                value = checked(value.movePointLeft(2))
                percent = true
            }
            return Part(value, percent)
        }

        // unary := ('-' | '+') unary | power
        private fun unary(): BigDecimal = when (peek()) {
            '-' -> { i++; nested { unary() }.negate() }
            '+' -> { i++; nested { unary() } }
            else -> power()
        }

        // power := primary ('^' unary)?     right-associative, and "-2^2" is -(2^2)
        private fun power(): BigDecimal {
            val base = primary()
            if (peek() != '^') return base
            i++
            val exponent = nested { unary() }
            return checked(pow(base, exponent))
        }

        // primary := number | '(' expression ')'
        private fun primary(): BigDecimal {
            if (peek() != '(') return number()
            i++
            val value = nested { expression() }
            require(peek() == ')')
            i++
            return value
        }

        /**
         * Digits with at most one "." or "," as the decimal point. When that one is the phone's
         * thousands separator and the number reads as grouped thousands ("1,000" or "123,456" on an
         * English phone, "1.000" on a German one), it could mean either, so it's no answer.
         */
        private fun number(): BigDecimal {
            val start = i
            while (peek() in '0'..'9' || peek() == '.' || peek() == ',') i++
            val raw = s.substring(start, i)
            require(raw.any { it in '0'..'9' })
            require(raw.count { it == '.' || it == ',' } <= 1)
            require(!looksGrouped(raw, grouping))
            return checked(BigDecimal(raw.replace(',', '.')))
        }

        /** Whole exponents exactly; others through doubles, kept to 14 digits so their noise never shows. */
        private fun pow(base: BigDecimal, exponent: BigDecimal): BigDecimal {
            val whole = exponent.signum() == 0 || exponent.stripTrailingZeros().scale() <= 0
            if (whole && exponent.abs() <= BigDecimal(MAX_WHOLE_EXPONENT)) return base.pow(exponent.intValueExact(), WORK)
            val result = Math.pow(base.toDouble(), exponent.toDouble())
            require(!result.isNaN() && !result.isInfinite())
            // Too small for a double isn't zero
            require(result != 0.0 || base.signum() == 0)
            return BigDecimal(result).round(MathContext(14, RoundingMode.HALF_EVEN))
        }
    }
}
