package com.w10m.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class PackerTest {
    private fun t(s: TileSize) = TileSpec(s.name + Math.random(), TileKind.APP, "x", s)

    @Test fun mediumsFillThreeAcross() {
        val cells = Packer.pack(List(4) { t(TileSize.MEDIUM) }, 6)
        assertEquals(listOf(Packer.Cell(0, 0), Packer.Cell(2, 0), Packer.Cell(4, 0), Packer.Cell(0, 2)), cells)
    }

    @Test fun smallsFillMediumHole() {
        val cells = Packer.pack(listOf(t(TileSize.WIDE), t(TileSize.SMALL), t(TileSize.SMALL), t(TileSize.SMALL), t(TileSize.SMALL)), 6)
        assertEquals(listOf(Packer.Cell(0, 0), Packer.Cell(4, 0), Packer.Cell(5, 0), Packer.Cell(4, 1), Packer.Cell(5, 1)), cells)
    }

    @Test fun mediumSnapsToEvenColumn() {
        val cells = Packer.pack(listOf(t(TileSize.SMALL), t(TileSize.MEDIUM)), 6)
        assertEquals(Packer.Cell(2, 0), cells[1])
    }

    @Test fun cellSizeMatchesSpecAt360() {
        // (360 - 2*8 - 5*4) / 6 = 54 epx; medium 112; wide 228.
        val small = (360f - 2 * Metro.SIDE_MARGIN - 5 * Metro.GUTTER) / 6
        assertEquals(54f, small, 0.001f)
        assertEquals(112f, 2 * small + Metro.GUTTER, 0.001f)
        assertEquals(228f, 4 * small + 3 * Metro.GUTTER, 0.001f)
    }
}

class CalcEngineTest {
    private fun run(keys: String): CalcEngine {
        val e = CalcEngine()
        for (k in keys) when (k) {
            '+', '−', '×', '÷' -> e.operator(k)
            '=' -> e.equals(); '.' -> e.dot(); '%' -> e.percent(); 'r' -> e.sqrt(); 'n' -> e.negate(); 'q' -> e.square(); 'i' -> e.reciprocal()
            else -> e.digit(k)
        }
        return e
    }

    @Test fun addition() = assertEquals("3", run("1+2=").entry)
    @Test fun immediateExecution() = assertEquals("20", run("2+3×4=").entry)
    @Test fun decimalsAreExact() = assertEquals("0.3", run("0.1+0.2=").entry)
    @Test fun repeatEquals() = assertEquals("11", run("5+3==").entry)
    @Test fun percentOfLeftOperand() = assertEquals("220", run("200+10%=").entry)
    @Test fun squareRoot() = assertEquals("3", run("9r").entry)
    @Test fun negate() = assertEquals("-5", run("5n").entry)
    @Test fun divideByZeroIsError() = assertEquals(true, run("1÷0=").error)
    @Test fun grouping() = assertEquals("1,234,567.5", CalcEngine.format("1234567.5"))
    @Test fun division() = assertEquals("2.5", run("5÷2=").entry)
}
