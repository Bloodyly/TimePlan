package de.fs.timeplan.grid

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class AzubiStatusTest {

    @Test
    fun `recognizes each fixed status label`() {
        assertEquals(AzubiStatus.SCHULE, AzubiStatus.from("Schule"))
        assertEquals(AzubiStatus.KRANK, AzubiStatus.from("Krank"))
        assertEquals(AzubiStatus.URLAUB, AzubiStatus.from("Urlaub"))
    }

    @Test
    fun `returns null for a monteur name or blank text`() {
        assertNull(AzubiStatus.from("144 Albrecht"))
        assertNull(AzubiStatus.from(""))
        assertNull(AzubiStatus.from(null))
    }
}
