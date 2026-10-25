package com.openminis.app.plus

import com.openminis.app.plus.SignInCore.Group
import com.openminis.app.plus.SignInCore.Link
import com.openminis.app.plus.SignInCore.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.15] account picker by sign-in group: who picks, what order, when to stop */
class SignInCoreTest {
    private val g = Way("a@gmail.com", Group.GOOGLE)
    private val e = Way("b@163.com", Group.EMAIL_PW)
    private val eCode = Way("b@163.com", Group.EMAIL_CODE)
    private val key = Way("API token", Group.API_KEY)

    @Test fun severalUnlinkedAccountsAskTheUser() {
        assertTrue(SignInCore.needsPick(listOf(g, e), null, null))
        assertTrue(SignInCore.needsPick(listOf(g, e, key), null, ""))
    }

    @Test fun noPickWhenLinkedNamedOrSingleAccount() {
        assertFalse(SignInCore.needsPick(listOf(g, e), Link(Group.GOOGLE, "a@gmail.com"), null))
        assertFalse(SignInCore.needsPick(listOf(g, e), null, "b@163.com"))
        assertFalse(SignInCore.needsPick(listOf(e, eCode), null, null))          // one account, two ways
        assertFalse(SignInCore.needsPick(listOf(g, key), null, null))           // an API key is not a sign-in account
        assertFalse(SignInCore.needsPick(emptyList(), null, null))
    }

    @Test fun pickChoicesDropKeysAndSortByGroup() {
        val c = SignInCore.pickChoices(listOf(e, key, g, e))
        assertEquals(listOf(g, e), c)
    }

    @Test fun linkedWayFirstAndFailedWayNeverAgain() {
        val l = Link(Group.EMAIL_PW, "b@163.com")
        assertEquals(listOf(e, eCode), SignInCore.order(listOf(eCode, e), l, emptySet()))
        assertEquals(listOf(eCode), SignInCore.order(listOf(eCode, e), l, setOf(e.key)))
    }

    @Test fun forNamedKeepsThatAccountOnly() {
        assertEquals(listOf(e, eCode), SignInCore.forNamed(listOf(g, e, eCode), "B@163.com"))
        assertEquals(listOf(g, e), SignInCore.forNamed(listOf(g, e), "zzz@x.com"))   // unknown name: nothing filtered
    }

    @Test fun stopsAfterTwoTries() {
        assertEquals(SignInCore.Next.TRY, SignInCore.next(0, 2))
        assertEquals(SignInCore.Next.TRY, SignInCore.next(1, 1))
        assertEquals(SignInCore.Next.HANDOFF, SignInCore.next(2, 5))
        assertEquals(SignInCore.Next.HANDOFF, SignInCore.next(0, 0))
        assertTrue(SignInCore.handoffLine("cloudflare.com", 2, listOf(e)).contains("等你处理"))
    }

    @Test fun groupsFromAccount() {
        assertEquals(Group.PHONE_CODE, SignInCore.groupOf(IdentityCore.Method.PHONE_SMS, null, "13690688658", false))
        assertEquals(Group.GOOGLE, SignInCore.groupOf(IdentityCore.Method.OAUTH, IdentityCore.IdType.GOOGLE, "a@gmail.com", false))
        assertEquals(Group.API_KEY, SignInCore.groupOf(IdentityCore.Method.KEY, null, "k", false))
    }

    @Test fun linksRoundTrip() {
        val m = mapOf("cloudflare.com" to Link(Group.EMAIL_PW, "b@163.com", byUser = true))
        assertEquals(m, SignInCore.linksFromJson(SignInCore.linksToJson(m)))
    }
}
