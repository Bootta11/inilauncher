package app.olauncher.extras

import app.olauncher.extras.search.ExpressionEvaluator
import app.olauncher.extras.search.ExpressionEvaluator.couldBeCalculation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Random

class ExpressionEvaluatorTest {

    /** An English phone (',' groups thousands) unless a test says otherwise. */
    private fun evaluate(input: String, grouping: Char = ',') = ExpressionEvaluator.evaluate(input, grouping)

    private fun assertAnswers(cases: Map<String, String>, grouping: Char = ',') = cases.forEach { (input, expected) ->
        assertEquals("\"$input\"", expected, evaluate(input, grouping))
    }

    private fun assertNoAnswer(vararg inputs: String, grouping: Char = ',') = inputs.forEach { input ->
        assertNull("\"$input\" should have no answer", evaluate(input, grouping))
    }

    @Test
    fun arithmetic() = assertAnswers(mapOf(
        "1+2*3" to "7",
        "(1+2)*3" to "9",
        "5/2" to "2.5",
        "2-3" to "-1",
        "2^3^2" to "512",
        "7%3" to "1",
        "-7%3" to "-1",
        "50%" to "0.5",
        "0.1+0.2" to "0.3",
        "0.1*3" to "0.3",
        "1,5+1" to "2.5",
        "-2*3" to "-6",
        "1++2" to "3",
        "0*-1" to "0",
    ))

    @Test
    fun signsAndPowers() = assertAnswers(mapOf(
        "-2^2" to "-4",
        "(-2)^2" to "4",
        "2^-1" to "0.5",
        "2^-2^2" to "0.0625",
        "10^0" to "1",
        "0^0" to "1",
        "2^0.5" to "1.4142135623731",
        "10^9999" to "1E+9999",
    ))

    @Test
    fun percentLikePhoneCalculators() = assertAnswers(mapOf(
        "200+10%" to "220",
        "200-10%" to "180",
        "200*10%" to "20",
        "200/10%" to "2000",
        "10%+5" to "5.1",
        "-10%" to "-0.1",
        "100+-10%" to "90",
        "(200+10%)*2" to "440",
        "50%%" to "0.005",
    ))

    @Test
    fun otherWaysOfTyping() = assertAnswers(mapOf(
        "3 × 4" to "12",
        "6 ÷ 2" to "3",
        "2 · 3" to "6",
        "2x3" to "6",
        "2X3" to "6",
        "8∕2" to "4",
        "2 – 1" to "1",
        "5 − 2" to "3",
        "5 — 2" to "3",
        "2**3" to "8",
        "3²" to "9",
        "2³" to "8",
        "=2+2" to "4",
        "2+2=" to "4",
        "= 2 + 2 =" to "4",
        "[1+2]*3" to "9",
        "{2}*3" to "6",
        "(1+2" to "3",
        "((2+3)*2" to "10",
        "۱۲+۳" to "15", // Persian digits
        "١٢+٣" to "15", // Arabic-Indic digits
        "1٫5+1" to "2.5", // Arabic decimal sign
        "1٬000+1" to "1001", // Arabic thousands sign
        "1 000+1" to "1001",
        "1 000+1" to "1001", // no-break space
        "1 000+1" to "1001", // narrow no-break space
        "２＋２" to "4", // full-width digits and plus
        "50٪" to "0.5",
    ))

    @Test
    fun exactDecimals() = assertAnswers(mapOf(
        "1/3" to "0.333333333333333",
        "2/3" to "0.666666666666667",
        "1/3*3" to "1",
        "123456789012345+1" to "123456789012346",
        "99999999999999*10" to "999999999999990",
        "10^15" to "1E+15",
        "10^-6" to "0.000001",
        "10^-7" to "1E-7",
        "123456789*987654321" to "1.21932631112635E+17",
    ))

    @Test
    fun thousandsOrDecimalDependsOnThePhone() {
        // English: ',' groups thousands
        assertNoAnswer("1,000+1", "123,456+1", grouping = ',')
        assertAnswers(mapOf("1.000+1" to "2", "0,500+1" to "1.5", "1,5+1" to "2.5", "1234,5+1" to "1235.5"), grouping = ',')
        // German: '.' groups thousands
        assertNoAnswer("1.000+1", grouping = '.')
        assertAnswers(mapOf("1,000+1" to "2", "1,5+1" to "2.5", "1.5+1" to "2.5"), grouping = '.')
        // French: a space groups thousands, so both are decimal points
        assertAnswers(mapOf("1,000+1" to "2", "1.000+1" to "2"), grouping = ' ')
        assertTrue(ExpressionEvaluator.looksGrouped("1,000", ','))
        assertFalse(ExpressionEvaluator.looksGrouped("0,500", ','))
        assertFalse(ExpressionEvaluator.looksGrouped("1234,000", ','))
        assertFalse(ExpressionEvaluator.looksGrouped("1,00", ','))
    }

    @Test
    fun notACalculation() = assertNoAnswer(
        "spotify", "42", "-42", "(5)", "-(5)", "1+", "*2", "+", "", " ", "=", "==", "1=2",
        "max 2+2", "1e5+1", "2(3)", "(2)(3)", "1)+2", "()", "1+()", ".", ".+1", "1..2+1", "1.2.3+1", "1,2.3+1",
        "12 apples + 3",
    )

    @Test
    fun impossibleMathHasNoAnswer() = assertNoAnswer(
        "1/0", "5%0", "0^-1", "(-8)^(1/3)", "9^9^9", "0.5^100000", "10^9999*1000", "10^-9999/1000",
    )

    @Test
    fun longOrDeepInputIsRefusedWithoutCrashing() {
        assertNoAnswer(
            "(".repeat(5000) + "1+1" + ")".repeat(5000),
            "2^".repeat(40) + "2",
            "-".repeat(40) + "1+1",
            "1+".repeat(60) + "1",
            "(".repeat(40) + "1+1",
        )
        assertEquals("3", evaluate("(".repeat(20) + "1+2" + ")".repeat(20)))
    }

    @Test
    fun couldBeASum() {
        listOf("1", " 12", "12*", "12*3", "(2+3", "-5", ".5", "2x3", "۱۲", "1,000", "=1+1", "[1", "2 – ").forEach {
            assertTrue("\"$it\" could be a sum", couldBeCalculation(it))
        }
        listOf("", " ", "1p", "1Password", "2fas", "x2", "spotify", "+", "*2", "!12", "=", "1 p", "1".repeat(101)).forEach {
            assertFalse("\"$it\" isn't a sum", couldBeCalculation(it))
        }
    }

    @Test
    fun neverThrowsOnRandomInput() {
        val random = Random(20261113)
        // Calculator characters, look-alikes, letters, odd spaces and half of an emoji (a lone surrogate)
        val pool = "0123456789.,+-*/%^()[]{} =×÷−–·xX²³٫٬۱٢＋abce!?#  😀"
        repeat(50_000) {
            val input = buildString { repeat(random.nextInt(121)) { append(pool[random.nextInt(pool.length)]) } }
            evaluate(input)?.let { answer -> BigDecimal(answer) } // every answer is a real number
            couldBeCalculation(input)
        }
    }

    @Test
    fun wholeNumberMathIsExact() {
        val random = Random(7)
        val shown = MathContext(15, RoundingMode.HALF_UP)
        repeat(2_000) {
            val a = BigDecimal(random.nextLong() % 1_000_000_000_000L)
            val b = BigDecimal(random.nextLong() % 1_000_000L)
            mapOf("$a+$b" to a.add(b), "$a-$b" to a.subtract(b), "$a*$b" to a.multiply(b)).forEach { (sum, exact) ->
                val answer = evaluate(sum) ?: error("no answer for $sum")
                assertEquals(sum, 0, BigDecimal(answer).compareTo(exact.round(shown)))
            }
        }
    }
}

class WebSuggestionsSendTest {
    private fun sends(query: String, web: Boolean = true, calculator: Boolean = true) =
        app.olauncher.extras.search.SearchProviders.sendsToWeb(query, web, calculator)

    @Test
    fun sumsStayOnThePhoneWhileTheCalculatorIsOn() {
        listOf("12*3", "1", "12", "(2+3", "-5", "1,000").forEach { assertFalse("\"$it\" shouldn't be sent", sends(it)) }
        listOf("kotlin", "1password", "12 apples", "2fas").forEach { assertTrue("\"$it\" should be sent", sends(it)) }
    }

    @Test
    fun calculatorOffOrWebOff() {
        assertTrue(sends("12*3", calculator = false))
        assertFalse(sends("kotlin", web = false))
        assertFalse(sends("k"))
        assertFalse(sends(" k "))
    }
}

class WebSuggestionParsingTest {
    @Test
    fun bothShapes() {
        assertEquals(listOf("kotlin", "kotlin flow"),
            app.olauncher.extras.search.SearchProviders.parseSuggestions("""["kot",["kotlin","kotlin flow"]]"""))
        assertEquals(listOf("kotlin"),
            app.olauncher.extras.search.SearchProviders.parseSuggestions("""[{"phrase":"kotlin"},{"x":1}]"""))
        assertEquals(emptyList<String>(), app.olauncher.extras.search.SearchProviders.parseSuggestions("oops"))
    }
}
