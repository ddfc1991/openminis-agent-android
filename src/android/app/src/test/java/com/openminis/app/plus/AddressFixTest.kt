package com.openminis.app.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.1] a hand-typed address with one extra digit is matched to the signed-in account (test data only). */
class AddressFixTest {
    @Test fun extraDigitTypo() {
        val known = listOf("alex.demo740@gmail.com", "other@163.com")
        assertEquals(listOf("alex.demo740@gmail.com"), AddressFix.candidates("alex.demo7400@gmail.com", known))
        assertEquals(listOf("alex.demo740@gmail.com"), AddressFix.candidates(" Alex.Demo7400@Gmail.com ", known))
        assertTrue(AddressFix.reason("alex.demo7400@gmail.com", known).contains("alex.demo740@gmail.com"))
    }
    @Test fun noFalseFix() {
        val known = listOf("alex.demo740@gmail.com")
        assertEquals(emptyList<String>(), AddressFix.candidates("alex.demo740@gmail.com", known))     // already right
        assertEquals(emptyList<String>(), AddressFix.candidates("someone.else@gmail.com", known))     // different person
        assertEquals(emptyList<String>(), AddressFix.candidates("alex.demo7400@163.com", known))      // other domain
        assertTrue(AddressFix.reason("x@gmail.com", known).startsWith("地址和应用专用密码对不上"))
    }
}
