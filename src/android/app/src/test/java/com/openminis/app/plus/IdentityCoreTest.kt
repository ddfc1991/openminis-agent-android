package com.openminis.app.plus

import com.openminis.app.plus.IdentityCore.IdType
import com.openminis.app.plus.IdentityCore.Ident
import com.openminis.app.plus.IdentityCore.Method
import com.openminis.app.plus.IdentityCore.Rec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Mines-38.9.1] one identity store: login-method detection / choice (phone-only bug), record merge, dynamic groups. */
class IdentityCoreTest {
    // test identities only (nothing like this ships in the APK: the app reads the user's own store)
    private val gmail = Ident(IdType.EMAIL, "someone@gmail.com")
    private val mail163 = Ident(IdType.EMAIL, "other@163.com")
    private val phone = Ident(IdType.PHONE, "+8613800001111")
    private val google = Ident(IdType.GOOGLE, "someone@gmail.com", signedIn = true)
    private val github = Ident(IdType.GITHUB, "octo", signedIn = true)
    private val noPw: (String) -> Boolean = { false }

    @Test fun types() {
        assertEquals(IdType.EMAIL, IdentityCore.guessType("a.b@163.com"))
        assertEquals(IdType.PHONE, IdentityCore.guessType("+86 138 0000 1111"))
        assertEquals(IdType.WALLET, IdentityCore.guessType("0x" + "a".repeat(40)))
        assertEquals(IdType.USERNAME, IdentityCore.guessType("octocat"))
        assertEquals(IdentityCore.keyOf(IdType.PHONE, "13800001111"), IdentityCore.keyOf(IdType.PHONE, "+86 138-0000-1111"))
        assertEquals("email:someone@gmail.com", IdentityCore.groupKey(IdType.GOOGLE, "Someone@gmail.com"))
    }

    @Test fun emailTabPageChoosesEmailNotOnlyPhone() {
        // the 38.9 bug: a page with 手机号登录 / 邮箱登录 tabs only ever got the phone number
        val offers = IdentityCore.parseOffers("""{"tabs":["手机号登录","邮箱登录"],"buttons":["获取验证码","登录"],"tel":true,"sendCode":true,"code":true}""")
        assertTrue(offers.any { it.method == Method.EMAIL_CODE && it.label == "邮箱登录" })
        val plans = IdentityCore.choose(offers, null, listOf(phone, gmail), noPw)
        assertEquals(Method.EMAIL_CODE, plans.first().method)
        assertEquals("邮箱登录", plans.first().click)
        assertTrue(plans.any { it.method == Method.PHONE_SMS })          // phone is still the fallback
        assertTrue(plans.indexOfFirst { it.method == Method.EMAIL_CODE } < plans.indexOfFirst { it.method == Method.PHONE_SMS })
    }

    @Test fun emailOnlySiteNeverTriesPhone() {
        val offers = IdentityCore.parseOffers("""{"email":true,"pw":true,"buttons":["Sign in","Continue with Google","Sign in with GitHub"]}""")
        val plans = IdentityCore.choose(offers, null, listOf(phone, gmail), noPw)
        assertFalse(plans.any { it.method == Method.PHONE_SMS || it.method == Method.PHONE_PW })
    }

    @Test fun signedInProviderPreferredOverEmail() {
        val offers = IdentityCore.parseOffers("""{"email":true,"buttons":["Continue with Google","Continue with GitHub","Continue"]}""")
        assertTrue(offers.any { it.provider == IdType.GOOGLE }); assertTrue(offers.any { it.provider == IdType.GITHUB })
        val plans = IdentityCore.choose(offers, null, listOf(phone, gmail, google, github), noPw)
        assertEquals(Method.OAUTH, plans[0].method); assertEquals(IdType.GOOGLE, plans[0].provider)
        assertEquals(IdType.GITHUB, plans[1].provider)
        assertEquals(Method.EMAIL_CODE, plans[2].method)
    }

    // [Mines-38.9.5 i] user order: session → saved password → Google / GitHub → code
    @Test fun passwordBeforeProviderBeforeCode() {
        val offers = IdentityCore.parseOffers("""{"email":true,"pw":true,"code":true,"buttons":["Continue with Google","Continue with GitHub"]}""")
        val plans = IdentityCore.choose(offers, null, listOf(gmail, google, github), { it == "someone@gmail.com" })
        assertEquals(Method.EMAIL_PW, plans[0].method)
        assertEquals(IdType.GOOGLE, plans[1].provider); assertEquals(IdType.GITHUB, plans[2].provider)
        assertEquals(Method.EMAIL_CODE, plans[3].method)
    }

    @Test fun recordedMethodComesFirst() {
        val offers = IdentityCore.parseOffers("""{"tabs":["短信登录","密码登录","邮箱"],"tel":true,"sendCode":true,"buttons":["微信登录","QQ登录"]}""")
        val rec = Rec("example.cn", "Example", "13800001111", IdType.PHONE, Method.PHONE_SMS)
        val plans = IdentityCore.choose(offers, rec, listOf(gmail, phone, google), noPw)
        assertEquals(Method.PHONE_SMS, plans.first().method)
        val rec2 = Rec("example.cn", "Example", "", null, Method.OAUTH, IdType.WECHAT, via = "wechat:me")
        val p2 = IdentityCore.choose(offers, rec2, listOf(gmail, phone, Ident(IdType.WECHAT, "me")), noPw)
        assertEquals(IdType.WECHAT, p2.first().provider)
    }

    @Test fun savedPasswordTurnsIntoPasswordAttempt() {
        val offers = IdentityCore.parseOffers("""{"user":true,"userHint":"手机号/邮箱","pw":true,"buttons":["登录"]}""")
        val plans = IdentityCore.choose(offers, null, listOf(phone, gmail, mail163), { it == "other@163.com" })
        assertEquals(Method.EMAIL_PW, plans.first().method); assertEquals("other@163.com", plans.first().ident?.value)
        assertTrue(plans.none { it.method == Method.EMAIL_PW && it.ident?.value == "someone@gmail.com" })
    }

    @Test fun stepwiseEmailPage() {
        // 「输入邮箱 → 继续」: code or password comes on the next page
        val offers = IdentityCore.parseOffers("""{"email":true,"buttons":["继续"]}""")
        assertTrue(offers.any { it.method == Method.EMAIL_CODE }); assertTrue(offers.any { it.method == Method.EMAIL_PW })
        assertEquals(Method.EMAIL_CODE, IdentityCore.choose(offers, null, listOf(gmail), noPw).first().method)
    }

    @Test fun providerWords() {
        assertEquals(IdType.GOOGLE, IdentityCore.providerOf("使用 Google 账号登录"))
        assertEquals(IdType.X, IdentityCore.providerOf("Sign in with X"))
        assertEquals(IdType.X, IdentityCore.providerOf("Twitter"))
        assertEquals(IdType.WALLET, IdentityCore.providerOf("Connect Wallet"))
        assertEquals(IdType.WECHAT, IdentityCore.providerOf("微信扫码登录"))
        assertEquals(null, IdentityCore.providerOf("登录"))
    }

    @Test fun mergeKeepsOneRecordAndUpgrades() {
        var l = listOf<Rec>()
        l = IdentityCore.merge(l, Rec("www.memoh.ai", "Memoh", "", null, Method.WEB, sources = setOf("网页登录"), at = 1))
        l = IdentityCore.merge(l, Rec("memoh.ai", "Memoh", "someone@gmail.com", IdType.EMAIL, Method.EMAIL_CODE, sources = setOf("一键关联"), at = 2))
        assertEquals(1, l.size)
        assertEquals("someone@gmail.com", l[0].identity); assertEquals(Method.EMAIL_CODE, l[0].method)
        assertEquals(setOf("网页登录", "一键关联"), l[0].sources)
        // a later record without method does not erase the known method
        l = IdentityCore.merge(l, Rec("memoh.ai", "", "", null, Method.UNKNOWN, hasPw = true, at = 3))
        assertEquals(1, l.size); assertEquals(Method.EMAIL_CODE, l[0].method); assertTrue(l[0].hasPw)
        // a different identity on the same site is its own account
        l = IdentityCore.merge(l, Rec("memoh.ai", "Memoh", "+8613800001111", IdType.PHONE, Method.PHONE_SMS))
        assertEquals(2, l.size)
        // idents
        var ids = listOf<Ident>()
        ids = IdentityCore.mergeIdent(ids, Ident(IdType.PHONE, "13800001111"))
        ids = IdentityCore.mergeIdent(ids, Ident(IdType.PHONE, "+86 13800001111", signedIn = true))
        ids = IdentityCore.mergeIdent(ids, Ident(IdType.EMAIL, ""))
        assertEquals(1, ids.size); assertTrue(ids[0].signedIn)
    }

    @Test fun groupsComeFromWhatExists() {
        val recs = listOf(
            Rec("github.com", "GitHub", "octo", IdType.USERNAME, Method.OAUTH, IdType.GOOGLE, via = "google:someone@gmail.com"),
            Rec("cloudflare.com", "Cloudflare", "someone@gmail.com", IdType.EMAIL, Method.EMAIL_PW),
            Rec("okx.com", "OKX", "+8613800001111", IdType.PHONE, Method.PHONE_SMS),
            Rec("doubao.com", "豆包", "", null, Method.WEB),
        )
        val gs = IdentityCore.groups(listOf(google, Ident(IdType.TELEGRAM, "@nobody")), recs)
        // Gmail group holds the e-mail account AND the one signed in through Google, with a Google badge
        val g = gs.first { it.key == "email:someone@gmail.com" }
        assertEquals("Gmail", g.title); assertEquals(2, g.recs.size); assertTrue(IdType.GOOGLE in g.badges)
        assertTrue(gs.any { it.type == IdType.PHONE })
        assertEquals("unknown", gs.last().key)
        // a type with nothing in it is not shown (Telegram identity with no accounts, not added by hand)
        assertFalse(gs.any { it.type == IdType.TELEGRAM })
        // hand-added identity shows at once
        val gs2 = IdentityCore.groups(listOf(Ident(IdType.TELEGRAM, "@me", source = "手动添加")), emptyList())
        assertEquals(IdType.TELEGRAM, gs2.single().type)
        assertEquals(1, IdentityCore.linkedVia(recs, IdType.GOOGLE, "someone@gmail.com").size)
        assertEquals("通过 Google 登录", IdentityCore.methodText(recs[0]))
    }
}
