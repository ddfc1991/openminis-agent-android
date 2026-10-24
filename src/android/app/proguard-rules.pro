# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# [T-android-vad-jni-keep / GH#250] RealTimeCutVAD's C++ layer calls back into
# VADWrapper BY NAME through JNI (GetMethodID "onVoiceStart" / "onVoiceEnd" /
# "onVoiceDidContinue"). Those three are PRIVATE Java methods with no Java-side
# caller, so R8 sees them as dead and renames them — verified in the shipped
# 1.12 release dex, where they had become b()V / c()V / d()V and the `callback`
# field was removed outright. The native lookup then fails with
#
#   java.lang.NoSuchMethodError: no non-static method
#   "Lio/codeconcept/realtimecutvadlibrary/VADWrapper;.onVoiceStart()V"
#
# thrown from processAudio (a native method), which is exactly the reported
# crash: every release user who tapped the mic got "Voice detection failed"
# before any STT provider was even contacted.
#
# proguard-android-optimize.txt keeps `native` method DECLARATIONS and @Keep,
# but nothing protects an ordinary Java method that only native code invokes.
#
# Kept whole rather than member-scoped: the library is five classes, so the
# size cost is negligible, whereas a narrow rule that missed a member the
# native side also touches (the `vadInstance` handle, for one) would degrade
# into a subtler runtime failure.
#
# NOTE FOR VERIFICATION: debug builds don't minify, so this bug is invisible
# there. Any change here must be checked against an assembleRelease APK.
-keep class io.codeconcept.realtimecutvadlibrary.** { *; }

# [Mines-offline-asr] Vosk talks to libvosk through JNA, which finds Java
# methods and Structure fields by name at runtime; R8 renaming either breaks
# recognition with an UnsatisfiedLinkError / NoSuchMethodError in release only.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# [Mines-vps-ssh] JSch instantiates ciphers, KEX, MACs and key types by class
# name from its config table (Class.forName), so nothing in it may be renamed
# or stripped. Optional integrations (BouncyCastle, JNA unix sockets, log4j,
# slf4j, Java 16 unix-domain sockets) are absent on Android.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.platform.**

# [Mines-voiceprint-34] sherpa-onnx JNI looks up Kotlin classes/fields by name
-keep class com.k2fsa.sherpa.onnx.** { *; }

# [Mines-38.9.11 d] apksig (file manager re-sign): pure JCA, no reflection into the app; silence optional references
-dontwarn com.android.apksig.**
# [Mines-38.9.11.2] JSch loads its Bouncy Castle signature / XDH classes by name; keep what they use
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }
-keep class org.bouncycastle.crypto.signers.Ed448Signer { *; }
-keep class org.bouncycastle.crypto.params.Ed25519PublicKeyParameters { *; }
-keep class org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters { *; }
-keep class org.bouncycastle.math.ec.rfc7748.** { *; }
-keep class org.bouncycastle.math.ec.rfc8032.** { *; }
-dontwarn org.bouncycastle.**
