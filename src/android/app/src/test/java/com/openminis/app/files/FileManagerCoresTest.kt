package com.openminis.app.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Mines-38.9.11 d] The file / APK manager's pure cores, on a real fixture APK (built with aapt2 + d8 + apksigner)
 * (manifest + resources.arsc + classes.dex + v1/v2/v3 signature), so AXML / ARSC / DEX / signature / zip / hex
 * are checked against bytes Android itself produced, not hand-written samples.
 */
class FileManagerCoresTest {

    private fun tmp(name: String): File = File(System.getProperty("java.io.tmpdir"), "mines-files-test/$name").apply { parentFile?.mkdirs() }

    // ---------- fixture ----------
    /** test resources files/fixture.apk: aapt2-linked manifest + arsc, d8 classes.dex, apksigner v1+v2+v3 (built offline, 12.7 KB). */
    private fun fixture(): File {
        val url = javaClass.classLoader.getResource("files/fixture.apk") ?: error("fixture.apk missing from test resources")
        val f = tmp("fixture.apk")
        if (!f.isFile || f.length() == 0L) f.writeBytes(url.openStream().use { it.readBytes() })
        return f
    }

    // ---------- BinReader ----------
    @Test fun readerIsLittleEndianAndBoundsChecked() {
        val r = BinReader(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0xFF.toByte()))
        assertEquals(0x0201, r.u16(0))
        assertEquals(0x04030201, r.s32(0))
        assertEquals(255, r.u8(4))
        try { r.s32(3); error("expected FormatException") } catch (e: FormatException) { assertTrue(e.message!!.contains("越界")) }
    }

    @Test fun lebDecodesSignedAndUnsigned() {
        assertEquals(0 to 1, BinReader(byteArrayOf(0)).uleb(0))
        assertEquals(300 to 2, BinReader(byteArrayOf(0xAC.toByte(), 0x02)).uleb(0))
        assertEquals(-1 to 1, BinReader(byteArrayOf(0x7F)).sleb(0))
        assertEquals(-129 to 2, BinReader(byteArrayOf(0xFF.toByte(), 0x7E)).sleb(0))
    }

    // ---------- AXML ----------
    @Test fun manifestDecodesToXmlAndDom() {
        val bytes = ZipCore.readEntry(fixture(), "AndroidManifest.xml")!!
        assertTrue(AxmlCore.isAxml(bytes))
        val doc = AxmlCore.parse(bytes)
        val root = doc.root!!
        assertEquals("manifest", root.name)
        assertEquals("com.example.fixture", root.a("package"))
        assertEquals("7", root.a("versionCode"))
        assertEquals("1.2.3", root.a("versionName"))
        assertTrue("xml text is readable", doc.xml.startsWith("<?xml") && doc.xml.contains("<application"))
        assertTrue("android: namespace prefix kept", doc.xml.contains("android:name="))
        val perms = root.all("uses-permission").mapNotNull { it.a("name") }
        assertEquals(listOf("android.permission.INTERNET", "android.permission.CAMERA").sorted(), perms.sorted())
        val app = root.children.first { it.name == "application" }
        assertEquals("true", app.a("debuggable"))
        val activity = app.children.first { it.name == "activity" }
        assertEquals("true", activity.a("exported"))
        assertTrue(activity.all("intent-filter").isNotEmpty())
        assertEquals(listOf("service", "receiver", "provider"), app.children.map { it.name }.filter { it != "activity" })
    }

    @Test fun valueFormatterSpeaksTheCommonTypes() {
        assertEquals("true", AxmlCore.formatValue(0x12, -1))
        assertEquals("false", AxmlCore.formatValue(0x12, 0))
        assertEquals("42", AxmlCore.formatValue(0x10, 42))
        assertEquals("@0x7f040001", AxmlCore.formatValue(0x01, 0x7f040001))
        assertEquals("#ffb5532f", AxmlCore.formatValue(0x1c, 0xffb5532f.toInt()))
        assertTrue(AxmlCore.formatValue(0x05, (16 shl 8) or (1 shl 4) or 1).endsWith("dp"))
    }

    @Test fun strippedAttributeNamesComeFromTheResourceMap() {
        assertEquals("name", AxmlCore.ATTR_NAMES[0x01010003])
        assertEquals("exported", AxmlCore.ATTR_NAMES[0x01010010])
        assertEquals("versionName", AxmlCore.ATTR_NAMES[0x0101021c])
    }

    @Test fun axmlRejectsSomethingElse() {
        try { AxmlCore.parse("hello".toByteArray()); error("expected FormatException") } catch (e: FormatException) { /* ok */ }
    }

    // ---------- ARSC ----------
    @Test fun resourceTableListsStringsAndEntries() {
        val arsc = ArscCore(ZipCore.readEntry(fixture(), "resources.arsc")!!)
        assertTrue(arsc.globalStrings.count > 0)
        val pkg = arsc.packages.first()
        assertEquals("com.example.fixture", pkg.name)
        val stringType = arsc.typeOf(pkg, "string")!!
        val entries = stringType.configs.flatMap { arsc.entries(pkg, it) }
        assertTrue("app_name is in the table", entries.any { it.key == "app_name" && it.value.contains("夹具应用") })
        assertTrue("hello is in the table", entries.any { it.key == "hello" })
        val ints = arsc.typeOf(pkg, "integer")?.configs?.flatMap { arsc.entries(pkg, it) } ?: emptyList()
        assertTrue("integer answer = 42", ints.any { it.key == "answer" && it.value == "42" })
        assertTrue(arsc.summary().any { it.first == "包" })
        assertTrue(entries.all { ArscCore.resIdString(it.resId).startsWith("0x7f") })
    }

    // ---------- DEX ----------
    @Test fun dexHeaderChecksumAndSignatureVerify() {
        val dex = DexCore(ZipCore.readEntry(fixture(), "classes.dex")!!)
        assertTrue(DexCore.isDex(dex.bytes))
        assertTrue("adler32 matches the header", dex.checksumOk())
        assertTrue("sha-1 signature matches", dex.signatureOk())
        assertTrue(dex.classDefsSize >= 2)
        assertTrue(dex.headerLines().any { it.first == "类" })
    }

    @Test fun dexListsClassesWithJavaNames() {
        val dex = DexCore(ZipCore.readEntry(fixture(), "classes.dex")!!)
        val names = dex.classes().map { it.javaName }
        assertTrue(names.contains("com.example.fixture.Hello"))
        assertTrue(names.contains("com.example.fixture.Helper"))
        val hello = dex.classes().first { it.simpleName == "Hello" }
        assertEquals("com.example.fixture", hello.packageName)
        assertEquals("java.lang.Object", DexCore.javaName(hello.superclass!!))
        assertEquals("Hello.java", hello.sourceFile)
        val helper = dex.classes().first { it.simpleName == "Helper" }
        assertTrue(helper.interfaces.contains("Ljava/lang/Runnable;"))
    }

    @Test fun dexDisassemblesLikeSmali() {
        val dex = DexCore(ZipCore.readEntry(fixture(), "classes.dex")!!)
        val i = dex.findClass("com.example.fixture.Hello")
        assertTrue(i >= 0)
        val s = dex.disassemble(i)
        assertTrue(s.startsWith(".class public Lcom/example/fixture/Hello;"))
        assertTrue(s.contains(".super Ljava/lang/Object;"))
        assertTrue("static final field with its value", s.contains(".field public static final GREETING:Ljava/lang/String; = \"\\u4f60\\u597d, dex\"") ||
            s.contains("GREETING:Ljava/lang/String; = \"你好, dex\""))
        assertTrue("instance field", s.contains(".field private count:I"))
        assertTrue("constructor", s.contains(".method public constructor <init>(I)V"))
        assertTrue("registers directive", s.contains(".registers "))
        assertTrue("invoke with a method ref", s.contains("invoke-direct {v0}, Ljava/lang/Object;-><init>()V") || s.contains("Ljava/lang/Object;-><init>()V"))
        assertTrue("field access decoded", s.contains("Lcom/example/fixture/Hello;->count:I"))
        assertTrue("switch payload", s.contains(".packed-switch") || s.contains("packed-switch"))
        assertTrue("branch label", Regex(""":[0-9a-f]{4}""").containsMatchIn(s))
        assertTrue("method ends", s.contains(".end method"))
        assertFalse("no unknown opcodes in javac/d8 output", s.contains("未用操作码"))
        val helper = dex.disassemble(dex.findClass("com.example.fixture.Helper"))
        assertTrue("sparse switch", helper.contains("sparse-switch"))
        assertTrue("try/catch", helper.contains(".catch"))
        assertTrue("monitor", helper.contains("monitor-enter"))
    }

    @Test fun dexRefusesOtherFiles() {
        try { DexCore("not a dex file at all".toByteArray()); error("expected FormatException") } catch (e: FormatException) { assertTrue(e.message!!.contains("DEX")) }
    }

    // ---------- signatures ----------
    @Test fun signatureBlockGivesV2V3Certificates() {
        val rep = ApkSigCore.read(fixture())
        assertTrue("v2 present", rep.v2.isNotEmpty())
        assertTrue("v3 present", rep.v3.isNotEmpty())
        assertTrue(rep.schemes.containsAll(listOf("v2", "v3")))
        val c = rep.primary!!
        assertEquals(64, c.sha256.length)
        assertEquals(40, c.sha1.length)
        assertTrue(c.subject.contains("Mines Fixture"))
        assertEquals("RSA", c.keyAlgorithm)
        assertNotNull(c.notAfter)
        assertTrue(rep.blockIds.contains(ApkSigCore.ID_V2))
        assertTrue(ApkSigCore.algorithmName(0x0103).contains("RSA"))
        assertTrue("every v2 signer names its algorithms", rep.v2.all { it.algorithms.isNotEmpty() })
    }

    @Test fun signatureReadIsSafeOnANonApk() {
        val f = tmp("plain.txt").apply { writeText("not a zip") }
        val rep = ApkSigCore.read(f)
        assertTrue(rep.schemes.isEmpty())
    }

    // ---------- APK info ----------
    @Test fun apkInfoSummarisesTheFixture() {
        val info = ApkInfoCore.read(fixture())
        assertEquals("com.example.fixture", info.packageName)
        assertEquals("1.2.3", info.versionName)
        assertEquals("7", info.versionCode)
        assertEquals("24", info.minSdk)
        assertEquals("34", info.targetSdk)
        assertTrue(info.debuggable)
        assertEquals(listOf("classes.dex"), info.dexFiles)
        assertTrue(info.abis.isEmpty())
        assertTrue(info.permissions.contains("android.permission.CAMERA"))
        assertEquals("相机", ApkInfoCore.permissionZh("android.permission.CAMERA"))
        assertEquals(listOf("activity", "service", "receiver", "provider"), info.components.map { it.kind })
        assertNotNull(info.launcher)
        assertTrue(info.summaryRows().any { it.first == "证书 SHA-256" })
        assertTrue(info.warnings.isEmpty())
        // notable permissions sort to the front
        val sorted = ApkInfoCore.sortPermissions(listOf("android.permission.INTERNET", "android.permission.CAMERA"))
        assertEquals("android.permission.CAMERA", sorted.first())
    }

    // ---------- zip ----------
    @Test fun zipRefusesPathTraversal() {
        val dest = tmp("unpack-dest").apply { mkdirs() }
        assertNull(ZipCore.safeChild(dest, "../evil"))
        assertNull(ZipCore.safeChild(dest, "/etc/passwd"))
        assertNull(ZipCore.safeChild(dest, "a/../../b"))
        assertNull(ZipCore.safeChild(dest, "..\\evil"))
        assertNull(ZipCore.safeChild(dest, ""))
        assertEquals(File(dest, "a/b.txt").path, ZipCore.safeChild(dest, "a/b.txt")!!.path)
        assertEquals(File(dest, "a/b.txt").path, ZipCore.safeChild(dest, "./a/b.txt")!!.path)
    }

    @Test fun zipRoundTripsAFolder() {
        val src = tmp("packme").apply { deleteRecursively(); mkdirs() }
        File(src, "one.txt").writeText("第一")
        File(src, "sub").mkdirs()
        File(src, "sub/two.bin").writeBytes(ByteArray(1000) { it.toByte() })
        val zip = tmp("out.zip").apply { delete() }
        assertEquals(2, ZipCore.pack(listOf(src), zip))
        val items = ZipCore.list(zip)
        assertTrue(items.any { it.path == "packme/one.txt" })
        assertTrue(items.any { it.path == "packme/sub/two.bin" })
        val dest = tmp("unpacked").apply { deleteRecursively() }
        val res = ZipCore.unpack(zip, dest)
        assertEquals(2, res.files)
        assertTrue(res.skipped.isEmpty())
        assertEquals("第一", File(dest, "packme/one.txt").readText())
        assertEquals(1000, File(dest, "packme/sub/two.bin").length())
        assertTrue(ZipCore.isZip(zip.readBytes().copyOfRange(0, 8)))
    }

    @Test fun apkEntriesCanBeReadAndPeeked() {
        val names = ZipCore.list(fixture()).map { it.path }
        assertTrue(names.contains("AndroidManifest.xml"))
        assertTrue(names.contains("classes.dex"))
        assertTrue(ZipCore.peek(fixture().inputStream()).isNotEmpty())
        assertNull(ZipCore.readEntry(fixture(), "nope.txt"))
    }

    // ---------- hex ----------
    @Test fun hexRowsAndParsing() {
        val data = ByteArray(20) { it.toByte() }
        val rows = HexCore.rows(data)
        assertEquals(2, rows.size)
        assertEquals("00000000", rows[0].label)
        assertTrue(rows[0].hex.startsWith("00 01 02 03"))
        assertEquals(4, rows[1].bytes.size)
        assertTrue(rows[1].ascii.startsWith("."))
        assertArrayEquals2(byteArrayOf(0xff.toByte(), 0x00, 0x1a), HexCore.parseHex("ff 00 1a"))
        assertArrayEquals2(byteArrayOf(0xff.toByte(), 0x00, 0x1a), HexCore.parseHex("FF-00:1A"))
        assertNull(HexCore.parseHex("ff0"))
        assertNull(HexCore.parseHex("zz"))
    }

    @Test fun hexSearchAndPatch() {
        val f = tmp("hex.bin").apply { writeBytes("hello mines hello".toByteArray()) }
        assertEquals(listOf(0, 12), HexCore.find(f.readBytes(), "hello".toByteArray()))
        assertEquals(listOf(0L, 12L), HexCore.findInFile(f, "hello".toByteArray()))
        assertTrue(HexCore.patch(f, 6, "MINES".toByteArray(), "mines".toByteArray()))
        assertEquals("hello MINES hello", f.readText())
        assertFalse("a stale expectation writes nothing", HexCore.patch(f, 6, "xxxxx".toByteArray(), "mines".toByteArray()))
        assertEquals("hello MINES hello", f.readText())
        try { HexCore.patch(f, f.length() - 1, ByteArray(10)); error("expected FormatException") } catch (e: FormatException) { /* ok */ }
    }

    @Test fun sniffNamesTheCommonKinds() {
        assertEquals("ZIP 压缩包（APK / JAR 也是）", HexCore.sniff(fixture().readBytes().copyOfRange(0, 16)))
        assertEquals("DEX 字节码", HexCore.sniff(ZipCore.readEntry(fixture(), "classes.dex")!!.copyOfRange(0, 16)))
        assertEquals("二进制 XML（AXML）", HexCore.sniff(ZipCore.readEntry(fixture(), "AndroidManifest.xml")!!.copyOfRange(0, 16)))
        assertEquals("文本", HexCore.sniff("just some text".toByteArray()))
        assertEquals("1.0 KB", HexCore.humanSize(1024))
    }

    // ---------- file ops ----------
    @Test fun renameRulesAndUniqueNames() {
        assertNull(FileOpsCore.badName("报告.txt"))
        assertNotNull(FileOpsCore.badName(""))
        assertNotNull(FileOpsCore.badName("a/b"))
        assertNotNull(FileOpsCore.badName(".."))
        val dir = tmp("ops").apply { deleteRecursively(); mkdirs() }
        File(dir, "a.txt").writeText("1")
        assertEquals("a (2).txt", FileOpsCore.uniqueTarget(dir, "a.txt").name)
        File(dir, "a (2).txt").writeText("2")
        assertEquals("a (3).txt", FileOpsCore.uniqueTarget(dir, "a.txt").name)
        assertEquals("b.txt", FileOpsCore.uniqueTarget(dir, "b.txt").name)
    }

    @Test fun copyMoveDeleteAndTheFolderIntoItselfGuard() {
        val root = tmp("cmd").apply { deleteRecursively(); mkdirs() }
        val a = File(root, "A").apply { mkdirs() }
        File(a, "x.txt").writeText("x")
        val inner = File(a, "inner").apply { mkdirs() }
        assertTrue(FileOpsCore.isInside(a, inner))
        assertFalse(FileOpsCore.isInside(a, root))
        assertEquals(0, FileOpsCore.copy(listOf(a), inner).ok)
        val b = File(root, "B").apply { mkdirs() }
        val copied = FileOpsCore.copy(listOf(a), b)
        assertEquals(1, copied.ok)
        assertEquals("x", File(b, "A/x.txt").readText())
        val moved = FileOpsCore.move(listOf(File(b, "A")), root)
        assertEquals(1, moved.ok)
        assertTrue(File(root, "A (2)").isDirectory)
        assertEquals(1, FileOpsCore.delete(listOf(File(root, "A (2)"))).ok)
        assertFalse(File(root, "A (2)").exists())
        assertEquals(1, FileOpsCore.rename(File(a, "x.txt"), "y.txt").ok)
        assertTrue(File(a, "y.txt").isFile)
        assertEquals(0, FileOpsCore.rename(File(a, "y.txt"), "a/b").ok)
    }

    @Test fun listSortsFoldersFirstAndSearchFindsNamesAndText() {
        val root = tmp("ls").apply { deleteRecursively(); mkdirs() }
        File(root, "zdir").mkdirs()
        File(root, "a.txt").writeText("找我")
        File(root, ".hidden").writeText("h")
        File(root, "zdir/deep.log").writeText("nothing here")
        val listed = FileOpsCore.list(root)
        assertEquals("zdir", listed.first().name)
        assertFalse(listed.any { it.name == ".hidden" })
        assertTrue(FileOpsCore.list(root, showHidden = true).any { it.name == ".hidden" })
        assertTrue(FileOpsCore.search(root, "deep").any { it.name == "deep.log" })
        assertTrue(FileOpsCore.search(root, "*.log").any { it.name == "deep.log" })
        assertTrue(FileOpsCore.search(root, "", contentText = "找我").any { it.name == "a.txt" })
        val (bytes, count) = FileOpsCore.measure(listOf(root))
        assertTrue(count >= 3 && bytes > 0)
    }

    // ---------- align + re-sign ----------
    @Test fun alignPadsStoredEntriesAndCheckAgrees() {
        val src = fixture()
        val out = tmp("aligned.apk").apply { delete() }
        ApkAlignCore.align(src, out)
        val rep = ApkAlignCore.check(out)
        assertTrue("aligned copy has no misaligned stored entries: " + rep.misaligned, rep.aligned)
        assertTrue(rep.total >= 3)
        // content survives
        assertTrue(ZipCore.readEntry(out, "classes.dex")!!.contentEquals(ZipCore.readEntry(src, "classes.dex")!!))
    }

    @Test fun resignWithAUserKeystoreProducesAVerifiablePackage() {
        val ks = tmp("user.p12")
        val pass = "mines-test".toCharArray()
        makeKeystore(ks, pass, "mykey")
        val key = ApkResignCore.loadKey(ks, pass, "mykey")
        assertTrue(ApkResignCore.aliases(ks, pass).contains("mykey"))
        val out = tmp("resigned.apk").apply { delete() }
        val res = ApkResignCore.sign(fixture(), out, key, minSdk = 24)
        assertTrue(res.message, res.verified)
        assertTrue(res.schemes.containsAll(listOf("v1", "v2", "v3")))
        assertEquals(key.sha256, res.certSha256)
        val (ok, lines) = ApkResignCore.verify(out, 24)
        assertTrue(lines.joinToString("\n"), ok)
        // the new certificate is NOT the fixture's one
        assertFalse(res.certSha256 == ApkSigCore.read(fixture()).primary!!.sha256)
        // and the package still reads
        assertEquals("com.example.fixture", ApkInfoCore.read(out).packageName)
    }

    @Test fun repackDropsOldSignaturesAndKeepsStoredEntries() {
        val dir = tmp("unpacked-apk").apply { deleteRecursively() }
        ZipCore.unpack(fixture(), dir)
        assertTrue(File(dir, "META-INF").isDirectory)
        assertTrue(ApkResignCore.isOldSignatureFile("META-INF/FIXTURE.RSA"))
        assertTrue(ApkResignCore.isOldSignatureFile("META-INF/MANIFEST.MF"))
        assertFalse(ApkResignCore.isOldSignatureFile("META-INF/services/foo"))
        assertFalse(ApkResignCore.isOldSignatureFile("classes.dex"))
        assertTrue(ApkResignCore.shouldStore("resources.arsc"))
        assertTrue(ApkResignCore.shouldStore("lib/arm64-v8a/libx.so"))
        assertFalse(ApkResignCore.shouldStore("classes.dex"))
        val unsigned = tmp("repacked.apk").apply { delete() }
        val n = ApkResignCore.repack(dir, unsigned, ApkResignCore.storedNames(fixture()))
        assertTrue(n >= 3)
        val names = ZipCore.list(unsigned).map { it.path }
        assertEquals("AndroidManifest.xml", names.first())
        assertFalse(names.any { ApkResignCore.isOldSignatureFile(it) })
        assertTrue(ApkSigCore.read(unsigned).schemes.isEmpty())
        val ks = tmp("user2.p12"); val pass = "pw".toCharArray(); makeKeystore(ks, pass, "k2")
        val res = ApkResignCore.sign(unsigned, tmp("repacked-signed.apk").apply { delete() }, ApkResignCore.loadKey(ks, pass, "k2"), minSdk = 24)
        assertTrue(res.message, res.verified)
        assertEquals("com.example.fixture", ApkInfoCore.read(res.out).packageName)
    }

    @Test fun signingSomeoneElsesPackageCarriesTheAuthorisationNotice() {
        val info = ApkInfoCore.read(fixture())
        assertNull(ApkResignCore.authorisationNotice(info, setOf("com.example.fixture")))
        val notice = ApkResignCore.authorisationNotice(info, setOf("com.openminis.mines.plus"))
        assertNotNull(notice)
        assertTrue(notice!!.contains("授权"))
    }

    // ---------- syntax + self-signed key ----------
    @Test fun syntaxSpansFindCommentsStringsKeywords() {
        assertEquals(SyntaxCore.Lang.C_LIKE, SyntaxCore.langOf("Main.kt"))
        assertEquals(SyntaxCore.Lang.SMALI, SyntaxCore.langOf("a.smali"))
        val (s, block) = SyntaxCore.spans("val x = \"hi\" // note", SyntaxCore.Lang.C_LIKE)
        assertFalse(block)
        assertTrue(s.any { it.token == SyntaxCore.Token.KEYWORD && it.start == 0 && it.end == 3 })
        assertTrue(s.any { it.token == SyntaxCore.Token.STRING })
        assertTrue(s.last().token == SyntaxCore.Token.COMMENT)
        val (_, open) = SyntaxCore.spans("a /* start", SyntaxCore.Lang.C_LIKE)
        assertTrue("block comment carries over", open)
        val (s2, closed) = SyntaxCore.spans("still */ val y", SyntaxCore.Lang.C_LIKE, inBlockComment = true)
        assertFalse(closed)
        assertEquals(SyntaxCore.Token.COMMENT, s2.first().token)
        assertTrue(SyntaxCore.spans("# c", SyntaxCore.Lang.SHELL).first.single().token == SyntaxCore.Token.COMMENT)
        assertTrue(SyntaxCore.spans("<a href=\"x\">", SyntaxCore.Lang.XML).first.any { it.token == SyntaxCore.Token.TAG })
        assertTrue(SyntaxCore.spans("anything", SyntaxCore.Lang.NONE).first.isEmpty())
    }

    @Test fun selfSignedKeystoreLoadsAndSigns() {
        val f = tmp("made.p12").apply { delete() }
        val cert = SelfSignedCore.createKeystore(f, "pw123456".toCharArray(), "mine", "Mines 用户")
        assertTrue(cert.subjectX500Principal.name.contains("Mines"))
        assertTrue(cert.notAfter.time > System.currentTimeMillis() + 20L * 365 * 86_400_000L)
        try { SelfSignedCore.createKeystore(f, "x".toCharArray(), "mine", "x"); error("must not overwrite") } catch (e: FormatException) { /* ok */ }
        val key = ApkResignCore.loadKey(f, "pw123456".toCharArray(), null)
        assertEquals("mine", key.alias)
        val res = ApkResignCore.sign(fixture(), tmp("made-signed.apk").apply { delete() }, key, minSdk = 24)
        assertTrue(res.message, res.verified)
        try { ApkResignCore.loadKey(f, "wrong".toCharArray(), null); error("wrong password must fail") } catch (e: FormatException) { assertTrue(e.message!!.contains("密码")) }
    }

    // ---------- helpers ----------
    private fun makeKeystore(file: File, pass: CharArray, alias: String) {
        if (file.isFile) file.delete()
        val kpg = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()
        val cert = selfSigned(kp)
        val ks = java.security.KeyStore.getInstance("PKCS12")
        ks.load(null, pass)
        ks.setKeyEntry(alias, kp.private, pass, arrayOf(cert))
        file.outputStream().use { ks.store(it, pass) }
    }

    /** Minimal self-signed X.509 without BouncyCastle: build the TBS by hand and sign it. */
    private fun selfSigned(kp: java.security.KeyPair): java.security.cert.X509Certificate {
        val name = derSeq(derSet(derSeq(derOid("2.5.4.3") + derUtf8("Mines Test"))))
        val now = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val validity = derSeq(derTag(0x17, fmt.format(java.util.Date(now - 86_400_000L)).toByteArray()) +
            derTag(0x17, fmt.format(java.util.Date(now + 3_650L * 86_400_000L)).toByteArray()))
        val algId = derSeq(derOid("1.2.840.113549.1.1.11") + byteArrayOf(0x05, 0x00))
        val tbs = derSeq(
            derTag(0xA0, derInt(byteArrayOf(2))) +            // version v3
                derInt(byteArrayOf(1)) +                      // serial
                algId + name + validity + name + kp.public.encoded,
        )
        val sig = java.security.Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(tbs); sign() }
        val cert = derSeq(tbs + algId + derTag(0x03, byteArrayOf(0) + sig))
        return java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as java.security.cert.X509Certificate
    }

    private fun derTag(tag: Int, body: ByteArray): ByteArray {
        val len = when {
            body.size < 0x80 -> byteArrayOf(body.size.toByte())
            body.size < 0x100 -> byteArrayOf(0x81.toByte(), body.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + len + body
    }
    private fun derSeq(b: ByteArray) = derTag(0x30, b)
    private fun derSet(b: ByteArray) = derTag(0x31, b)
    private fun derInt(b: ByteArray) = derTag(0x02, b)
    private fun derUtf8(s: String) = derTag(0x0C, s.toByteArray())
    private fun derOid(oid: String): ByteArray {
        val parts = oid.split('.').map { it.toInt() }
        val out = java.io.ByteArrayOutputStream()
        out.write(parts[0] * 40 + parts[1])
        for (p in parts.drop(2)) {
            if (p < 128) out.write(p) else {
                val stack = ArrayList<Int>(); var v = p
                while (v > 0) { stack.add(v and 0x7F); v = v shr 7 }
                stack.reversed().forEachIndexed { i, x -> out.write(if (i == stack.size - 1) x else x or 0x80) }
            }
        }
        return derTag(0x06, out.toByteArray())
    }

    private fun assertArrayEquals2(a: ByteArray, b: ByteArray?) {
        assertNotNull(b); assertTrue(a.contentEquals(b!!))
    }
}
