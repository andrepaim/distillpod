package dev.distillpod.core
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun sealToken(plaintext: String, key: SecretKey): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    return byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
}
fun openToken(envelope: ByteArray, key: SecretKey): String {
    require(envelope.size >= 29 && envelope[0].toInt() == 1)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
    return String(cipher.doFinal(envelope.copyOfRange(13, envelope.size)), Charsets.UTF_8)
}
