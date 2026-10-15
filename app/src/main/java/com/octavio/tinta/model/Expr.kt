package com.octavio.tinta.model

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * Una función escrita como en el pizarrón: `x^2 - 3x + 1`, `2sen(x)`, `√(x+1)`, `e^-x`, `|x|`,
 * `ln x`, con coma o punto decimal. Variables x e y; constantes pi (π) y e. La multiplicación se
 * puede omitir (`2x`, `x(x+1)`, `3pi`). Lo que no está definido (√-1, ln 0) da NaN.
 */
class Expr private constructor(private val root: Node, val variables: Set<Char>) {

    fun eval(x: Double, y: Double = 0.0): Double = root.eval(x, y)

    class ParseError(message: String) : IllegalArgumentException(message)

    private sealed interface Node {
        fun eval(x: Double, y: Double): Double
    }

    private class Num(val v: Double) : Node {
        override fun eval(x: Double, y: Double) = v
    }

    private class Var(val name: Char) : Node {
        override fun eval(x: Double, y: Double) = if (name == 'x') x else y
    }

    private class Unary(val f: (Double) -> Double, val arg: Node) : Node {
        override fun eval(x: Double, y: Double) = f(arg.eval(x, y))
    }

    private class Binary(val f: (Double, Double) -> Double, val l: Node, val r: Node) : Node {
        override fun eval(x: Double, y: Double) = f(l.eval(x, y), r.eval(x, y))
    }

    private class Parser(text: String) {
        val tokens = tokenize(text)
        var pos = 0
        val variables = HashSet<Char>()

        fun peek() = tokens.getOrNull(pos)
        fun next() = tokens[pos++]

        fun parse(): Node {
            if (tokens.isEmpty()) throw ParseError("Escribí una función")
            val n = sum()
            if (pos < tokens.size) throw ParseError("No entiendo «${tokens[pos].text}»")
            return n
        }

        fun sum(): Node {
            var n = product()
            while (true) {
                n = when (peek()?.text) {
                    "+" -> { next(); Binary(Double::plus, n, product()) }
                    "-" -> { next(); Binary(Double::minus, n, product()) }
                    else -> return n
                }
            }
        }

        fun product(): Node {
            var n = unary()
            while (true) {
                val t = peek() ?: return n
                n = when {
                    t.text == "*" -> { next(); Binary(Double::times, n, unary()) }
                    t.text == "/" -> { next(); Binary(Double::div, n, unary()) }
                    // Multiplicación sin signo: 2x, x(x+1), 3sen(x).
                    t.startsPrimary && !(t.text == "|" && inAbs > 0) -> Binary(Double::times, n, power())
                    else -> return n
                }
            }
        }

        fun unary(): Node = when (peek()?.text) {
            "-" -> { next(); Unary({ -it }, unary()) }
            "+" -> { next(); unary() }
            else -> power()
        }

        fun power(): Node {
            val base = primary()
            if (peek()?.text == "^") {
                next()
                return Binary({ a, b -> a.pow(b) }, base, unary()) // 2^-x, y de derecha a izquierda
            }
            return base
        }

        var inAbs = 0

        fun primary(): Node {
            val t = peek() ?: throw ParseError("Falta algo al final")
            next()
            return when {
                t.number != null -> Num(t.number)
                t.text == "(" -> sum().also { expect(")") }
                t.text == "|" -> {
                    inAbs++
                    val n = sum()
                    inAbs--
                    expect("|")
                    Unary(::abs, n)
                }
                t.text == "√" -> Unary(::sqrt, power())
                t.text == "pi" -> Num(PI)
                t.text == "e" -> Num(E)
                t.text == "x" || t.text == "y" -> {
                    variables.add(t.text[0])
                    Var(t.text[0])
                }
                t.text in FUNCTIONS -> {
                    val f = FUNCTIONS.getValue(t.text)
                    // sen(x) o sen x (en ese caso toma una potencia: sen x^2 = sen(x²)).
                    val arg = if (peek()?.text == "(") { next(); sum().also { expect(")") } } else power()
                    Unary(f, arg)
                }
                else -> throw ParseError("No entiendo «${t.text}»")
            }
        }

        fun expect(text: String) {
            if (peek()?.text != text) throw ParseError("Falta «$text»")
            next()
        }
    }

    private class Token(val text: String, val number: Double? = null) {
        val startsPrimary get() = number != null || text == "(" || text == "|" || text == "√" ||
            text == "pi" || text == "e" || text == "x" || text == "y" || text in FUNCTIONS
    }

    companion object {
        fun parse(text: String): Expr {
            val p = Parser(clean(text))
            val root = p.parse()
            return Expr(root, p.variables)
        }

        /** Saca "y =", "f(x) =", "z =" o "f(x,y) =" del principio. */
        fun clean(text: String): String {
            val t = text.trim()
            val eq = t.indexOf('=')
            if (eq < 0) return t
            val left = t.substring(0, eq).replace(" ", "")
            return if (left.matches(Regex("[yzf]|[fgh]\\([xy,]*\\)"))) t.substring(eq + 1).trim() else t
        }

        private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
            "sin" to ::sin, "sen" to ::sin, "cos" to ::cos, "tan" to ::tan, "tg" to ::tan,
            "asin" to ::asin, "arcsin" to ::asin, "arcsen" to ::asin, "asen" to ::asin,
            "acos" to ::acos, "arccos" to ::acos,
            "atan" to ::atan, "arctan" to ::atan, "arctg" to ::atan,
            "sinh" to ::sinh, "senh" to ::sinh, "cosh" to ::cosh, "tanh" to ::tanh,
            "sec" to { v -> 1 / cos(v) }, "csc" to { v -> 1 / sin(v) }, "cosec" to { v -> 1 / sin(v) },
            "cot" to { v -> 1 / tan(v) }, "cotg" to { v -> 1 / tan(v) },
            "exp" to ::exp, "ln" to ::ln, "log" to ::log10, "log2" to ::log2,
            "sqrt" to ::sqrt, "raiz" to ::sqrt, "abs" to ::abs,
            "floor" to ::floor, "piso" to ::floor, "ceil" to ::ceil, "techo" to ::ceil,
            "sign" to { v -> sign(v) }, "sgn" to { v -> sign(v) },
        )

        /** Nombres que se reconocen dentro de una tira de letras ("2senx" es 2·sen(x)). */
        private val NAMES = (FUNCTIONS.keys + "pi").sortedByDescending { it.length }

        private fun tokenize(text: String): List<Token> {
            val out = ArrayList<Token>()
            var i = 0
            val s = text.replace('·', '*').replace('×', '*').replace('÷', '/').replace('−', '-')
                .replace("²", "^2").replace("³", "^3").replace("π", "pi")
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                        val start = i
                        while (i < s.length && (s[i].isDigit() ||
                                ((s[i] == '.' || s[i] == ',') && i + 1 < s.length && s[i + 1].isDigit()))
                        ) i++
                        val text2 = s.substring(start, i).replace(',', '.')
                        out.add(Token(text2, text2.toDoubleOrNull() ?: throw ParseError("Número raro: $text2")))
                    }
                    c.isLetter() -> {
                        val start = i
                        while (i < s.length && (s[i].isLetter() || (s[i].isDigit() && s.startsWith("log2", start)))) i++
                        var run = s.substring(start, i).lowercase()
                        // Se parte en nombres conocidos y variables sueltas.
                        while (run.isNotEmpty()) {
                            val name = NAMES.firstOrNull { run.startsWith(it) }
                            val piece = name ?: run.substring(0, 1)
                            if (name == null && piece !in setOf("x", "y", "e")) throw ParseError("No conozco «$run»")
                            out.add(Token(piece))
                            run = run.substring(piece.length)
                        }
                    }
                    c in "+-*/^()|√" -> {
                        out.add(Token(c.toString()))
                        i++
                    }
                    else -> throw ParseError("No entiendo «$c»")
                }
            }
            return out
        }
    }
}
