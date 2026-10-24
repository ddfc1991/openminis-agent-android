package com.openminis.app.plus

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [Mines-38.7] Passwords exported from Edge / Chrome (CSV: name,url,username,password — user 2026-10-15: their passwords
 * live in Edge / the Microsoft account, which Mines cannot read). Imported once, kept encrypted with an Android Keystore
 * AES-GCM key (never leaves the phone, never shown to an AI); the CSV itself is not kept — the user is asked to delete
 * it. During 一键关联 / any in-app sign-in the matching entry (same site) is typed into the page and submitted
 * ([fillJs]); a code, QR or 2-step check still stops for the user.
 */
object SavedLogins {
    data class Entry(val name: String, val url: String, val user: String, val pass: String) {
        val host: String get() = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("").removePrefix("www.")
    }

    private const val PREFS = "mines_saved_logins"
    private const val ALIAS = "mines_saved_logins_v1"
    @Volatile private var cache: List<Entry>? = null

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    private fun enc(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun dec(s: String): String {
        val (iv, d) = s.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(d, Base64.NO_WRAP)))
    }

    fun all(c: Context): List<Entry> {
        cache?.let { return it }
        fun parse(raw: String): List<Entry> {
            val a = JSONArray(dec(raw))
            return (0 until a.length()).map { i -> a.getJSONObject(i).let { Entry(it.optString("n"), it.optString("u"), it.optString("l"), it.optString("p")) } }
        }
        // [38.9.11.2] atomic file (+2 backups, each checked by decrypting it) → the prefs copy → one notice if all fail
        val (file, allFailed) = AtomicStore.read(c, "saved_logins.enc") { runCatching { parse(it); true }.getOrDefault(false) }
        val l = file?.let { runCatching { parse(it) }.getOrNull() }
            ?: c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("blob", null)?.let { raw -> runCatching { parse(raw) }.getOrNull() }
            ?: emptyList<Entry>().also { if (allFailed) AtomicStore.notice(c, "密码库") }
        cache = l; return l
    }

    fun count(c: Context) = all(c).size

    private fun save(c: Context, l: List<Entry>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("n", it.name).put("u", it.url).put("l", it.user).put("p", it.pass)) }
        val blob = enc(a.toString())
        AtomicStore.write(c, "saved_logins.enc", blob)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("blob", blob).apply()
        cache = l
    }

    /** [Mines-38.9] add / replace one login (autofill save, 账号详情). Same host + user = replaced. */
    fun put(c: Context, e: Entry) {
        val merged = LinkedHashMap<String, Entry>()
        (all(c) + e).forEach { merged[it.host + "|" + it.user] = it }
        save(c, merged.values.toList())
        // [Mines-38.9.1] the one identity store: this site + account now has a password
        runCatching { if (e.host.isNotBlank()) { val t = IdentityCore.guessType(e.user)
            IdentityStore.addRec(c, IdentityCore.Rec(Keychain.root(e.host), e.name.ifBlank { AutoAccounts.siteOf(e.host) }, e.user, t,
                when (t) { IdentityCore.IdType.EMAIL -> IdentityCore.Method.EMAIL_PW; IdentityCore.IdType.PHONE -> IdentityCore.Method.PHONE_PW; else -> IdentityCore.Method.USER_PW },
                sources = setOf("密码"), hasPw = true, at = System.currentTimeMillis())) } }
    }

    /** [Mines-38.9] the saved login for a host + account (exact user first, then any on that site when [anyUser]). */
    fun find(c: Context, host: String, user: String, anyUser: Boolean = false): Entry? {
        val h = host.removePrefix("www."); if (h.isBlank()) return null
        fun root(x: String) = x.split('.').takeLast(2).joinToString(".")
        val l = all(c).filter { it.host == h || (it.host.isNotBlank() && root(it.host) == root(h)) }
        return l.firstOrNull { user.isNotBlank() && it.user.equals(user, true) } ?: if (anyUser || user.isBlank()) l.firstOrNull() else null
    }

    /** [38.9.11.3] remove one login (a 多号注册 that failed) */
    fun remove(c: Context, host: String, user: String) {
        save(c, all(c).filterNot { it.host.equals(host.removePrefix("www."), true) && it.user.equals(user, true) })
    }

    fun clear(c: Context) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply(); cache = emptyList() }

    /** RFC-4180-ish CSV line split (quotes, doubled quotes). */
    private fun split(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                q && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == ',' && !q -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString()); return out
    }

    /** Imports an Edge / Chrome password CSV; returns how many logins were added (merged by site + user). */
    fun importCsv(c: Context, text: String): Int {
        val lines = text.replace("\r\n", "\n").split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return 0
        val head = split(lines[0]).map { it.trim().lowercase() }
        val iu = head.indexOf("url"); val il = head.indexOf("username"); val ip = head.indexOf("password"); val iN = head.indexOf("name")
        if (iu < 0 || ip < 0) return 0
        val got = lines.drop(1).mapNotNull { ln ->
            val f = split(ln)
            val u = f.getOrNull(iu)?.trim().orEmpty(); val p = f.getOrNull(ip).orEmpty()
            if (!u.startsWith("http") || p.isEmpty()) null
            else Entry(f.getOrNull(iN).orEmpty(), u, f.getOrNull(il).orEmpty(), p)
        }
        val merged = LinkedHashMap<String, Entry>()
        (all(c) + got).forEach { merged[it.host + "|" + it.user] = it }
        save(c, merged.values.toList())
        return got.size
    }

    fun importUri(c: Context, uri: Uri): Int = runCatching {
        c.contentResolver.openInputStream(uri)?.use { importCsv(c, it.readBytes().toString(Charsets.UTF_8)) } ?: 0
    }.getOrDefault(0)

    /** Best saved login for a page URL (same host, or same registrable domain). */
    fun forUrl(c: Context, pageUrl: String?): Entry? {
        val h = runCatching { Uri.parse(pageUrl ?: return null).host.orEmpty() }.getOrDefault("").removePrefix("www.")
        if (h.isBlank()) return null
        fun root(x: String) = x.split('.').takeLast(2).joinToString(".")
        val l = all(c)
        return l.firstOrNull { it.host == h } ?: l.firstOrNull { it.host.isNotBlank() && root(it.host) == root(h) }
    }

    /**
     * JS: types [e] into the visible username / password fields (React-safe setter + input events) and, when [submit],
     * presses the sign-in button. Returns 'filled' / 'user' (only the user field — two-step forms) / 'none'.
     */
    fun fillJs(e: Entry, submit: Boolean, overwrite: Boolean = false) = """(function(){try{
function vis(x){var r=x.getBoundingClientRect();return r.width>4&&r.height>4&&getComputedStyle(x).visibility!='hidden';}
function setv(x,v){var p=Object.getPrototypeOf(x);var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)d.set.call(x,v);else x.value=v;x.dispatchEvent(new Event('input',{bubbles:true}));x.dispatchEvent(new Event('change',{bubbles:true}));}
var ins=[].slice.call(document.querySelectorAll('input')).filter(function(x){return vis(x)&&!x.disabled&&!x.readOnly;});
var pw=ins.find(function(x){return x.type=='password';});
var us=ins.find(function(x){var h=((x.type||'')+' '+(x.name||'')+' '+(x.id||'')+' '+(x.autocomplete||'')+' '+(x.placeholder||'')).toLowerCase();return x.type!='password'&&x.type!='checkbox'&&x.type!='hidden'&&/user|email|login|account|phone|mobile|tel|账号|邮箱|手机|用户名|text/.test(h);});
var out='none';
var uv=${JSONObject.quote(e.user)};var isTel=us&&(us.type=='tel'||/手机|phone|mobile|tel/.test(((us.name||'')+' '+(us.id||'')+' '+(us.placeholder||'')).toLowerCase()));
if(us&&isTel&&!/^\+?[0-9][0-9 \-]{5,18}$/.test(uv))us=null;   // [Mines-38.7 v11] smoke: 「smoke_user」 went into a 手机号 field
var ow=${if (overwrite) "true" else "false"};
if(us&&(ow||!us.value)&&uv){setv(us,uv);out='user';}
if(pw&&(ow||!pw.value)){setv(pw,${JSONObject.quote(e.pass)});out='filled';}
if(${if (submit) "true" else "false"}&&out!='none'){
 var bs=[].slice.call(document.querySelectorAll('button,[role=button],input[type=submit]')).filter(function(x){var t=(x.innerText||x.value||'').trim();return vis(x)&&!x.disabled&&/^(登录|登 录|立即登录|下一步|继续|Log in|Sign in|Next|Continue)$/i.test(t);});
 if(bs.length){setTimeout(function(){bs[bs.length-1].click();},400);}
}
return out;}catch(err){return 'none';}})()"""
}
