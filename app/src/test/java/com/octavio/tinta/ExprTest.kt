package com.octavio.tinta

import com.octavio.tinta.model.Expr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.E
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class ExprTest {
    private fun f(text: String, x: Double, y: Double = 0.0) = Expr.parse(text).eval(x, y)

    @Test
    fun likeOnTheBoard() {
        assertEquals(5.0, f("x^2 - 3x + 1", 4.0), 1e-9)
        assertEquals(2 * sin(1.0), f("2sen(x)", 1.0), 1e-9)
        assertEquals(2 * sin(1.0), f("2 sin x", 1.0), 1e-9)
        assertEquals(sqrt(5.0), f("√(x+1)", 4.0), 1e-9)
        assertEquals(3.0, f("|x|", -3.0), 1e-9)
        assertEquals(1 / E, f("e^-x", 1.0), 1e-9)
        assertEquals(-4.0, f("-x^2", 2.0), 1e-9) // menos de la potencia, no al revés
        assertEquals(2.5, f("2,5", 0.0), 1e-9) // coma decimal
        assertEquals(3 * PI, f("3pi", 0.0), 1e-9)
        assertEquals(6.0, f("x(x+1)", 2.0), 1e-9)
        assertEquals(PI * 4, f("π·x²", 2.0), 1e-9)
        // Lo mismo con lo que hay en cualquier teclado.
        assertEquals(PI * 4, f("pi*x^2", 2.0), 1e-9)
        assertEquals(sqrt(5.0), f("sqrt(x+1)", 4.0), 1e-9)
        assertEquals(sin(1.0) * kotlin.math.cos(2.0), f("sen(x)*cos(y)", 1.0, 2.0), 1e-9)
        assertEquals(kotlin.math.exp(-2.0), f("e^(-(x^2+y^2))", 1.0, 1.0), 1e-9)
        assertEquals(0.0, f("ln x", 1.0), 1e-9)
        assertEquals(2.0, f("log(100)", 0.0), 1e-9)
        assertEquals(256.0, f("2^2^3", 0.0), 1e-9) // de derecha a izquierda
    }

    @Test
    fun prefixesAndTwoVariables() {
        assertEquals(9.0, f("y = x^2", 3.0), 1e-9)
        assertEquals(9.0, f("f(x) = x^2", 3.0), 1e-9)
        assertEquals(25.0, f("z = x^2 + y^2", 3.0, 4.0), 1e-9)
        assertEquals(setOf('x', 'y'), Expr.parse("x·y").variables)
        assertEquals(6.0, f("xy", 2.0, 3.0), 1e-9)
    }

    @Test
    fun undefinedIsNaN() {
        assertTrue(f("√x", -1.0).isNaN())
        assertTrue(f("ln(x)", -1.0).isNaN())
    }

    @Test(expected = Expr.ParseError::class)
    fun unknownName() {
        Expr.parse("q + 1")
    }

    @Test(expected = Expr.ParseError::class)
    fun unbalanced() {
        Expr.parse("(x + 1")
    }
}
