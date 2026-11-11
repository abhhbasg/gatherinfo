package com.w10m.launcher

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Standard-mode calculator logic, behaving like the Windows calculator: operators apply immediately
 * (2 + 3 × 4 = 20), "=" repeats the last operation, % is relative to the left operand.
 */
class CalcEngine {
    var entry = "0"; private set
    var expression = ""; private set
    var error = false; private set
    private var acc: BigDecimal? = null
    private var op: Char? = null
    private var fresh = true           // next digit starts a new entry
    private var lastOp: Char? = null
    private var lastOperand: BigDecimal? = null
    private val mc = MathContext(16, RoundingMode.HALF_UP)

    private fun value(): BigDecimal = entry.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun show(v: BigDecimal) {
        var s = v.round(mc).stripTrailingZeros()
        if (s.scale() < 0) s = s.setScale(0)
        entry = if (s.precision() - s.scale() > 16 || s.scale() > 16) s.toString() else s.toPlainString()
    }

    private fun fail() { clear(); error = true }

    fun clear() { entry = "0"; expression = ""; acc = null; op = null; fresh = true; lastOp = null; lastOperand = null; error = false }

    fun clearEntry() { if (error) clear() else { entry = "0"; fresh = true } }

    fun digit(d: Char) {
        if (error) clear()
        if (fresh) { entry = d.toString(); fresh = false }
        else if (entry.count { it.isDigit() } < 16) entry = if (entry == "0") d.toString() else entry + d
    }

    fun dot() {
        if (error) clear()
        if (fresh) { entry = "0."; fresh = false } else if (!entry.contains('.')) entry += "."
    }

    fun backspace() {
        if (error) { clear(); return }
        if (fresh) return
        entry = entry.dropLast(1).let { if (it.isEmpty() || it == "-") "0" else it }
    }

    fun negate() {
        if (error || value().signum() == 0) return
        entry = if (entry.startsWith("-")) entry.drop(1) else "-$entry"
    }

    private fun apply(a: BigDecimal, o: Char, b: BigDecimal): BigDecimal? = when (o) {
        '+' -> a.add(b, mc)
        '−' -> a.subtract(b, mc)
        '×' -> a.multiply(b, mc)
        else -> if (b.signum() == 0) null else a.divide(b, mc)
    }

    fun operator(o: Char) {
        if (error) return
        val a = acc; val cur = op
        if (a != null && cur != null && !fresh) {
            val r = apply(a, cur, value()) ?: return fail()
            acc = r; show(r)
        } else acc = value()
        op = o; fresh = true; lastOp = null
        expression = "${format(entry)} $o"
    }

    fun equals() {
        if (error) return
        val a = acc; val cur = op
        if (a != null && cur != null) {
            val b = value()
            val r = apply(a, cur, b) ?: return fail()
            lastOp = cur; lastOperand = b
            show(r)
        } else if (lastOp != null) {
            val r = apply(value(), lastOp!!, lastOperand!!) ?: return fail()
            show(r)
        }
        acc = null; op = null; fresh = true; expression = ""
    }

    /** x% of the left operand while an operation is pending (200 + 10 % → 20); otherwise 0, as in Windows. */
    fun percent() {
        if (error) return
        val a = acc
        show(if (a != null && op != null) a.multiply(value(), mc).divide(BigDecimal(100), mc) else BigDecimal.ZERO)
        fresh = true
    }

    fun sqrt() {
        if (error) return
        val v = value()
        if (v.signum() < 0) return fail()
        show(v.sqrt(mc)); fresh = true
    }

    fun square() { if (!error) { show(value().multiply(value(), mc)); fresh = true } }

    fun reciprocal() {
        if (error) return
        if (value().signum() == 0) return fail()
        show(BigDecimal.ONE.divide(value(), mc)); fresh = true
    }

    companion object {
        /** Adds thousands separators to the integer part for display. */
        fun format(s: String): String {
            if (s.contains('E')) return s
            val neg = s.startsWith("-"); val body = s.removePrefix("-")
            val int = body.substringBefore('.'); val frac = if (body.contains('.')) "." + body.substringAfter('.') else ""
            return (if (neg) "-" else "") + int.reversed().chunked(3).joinToString(",").reversed() + frac
        }
    }
}
