package notch.cat.cast.airplay

import com.github.serezhka.airplay.lib.internal.FairPlayVideoDecryptor
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FairPlayVideoDecryptorTest {
  @Test
  fun videoDecryptorUsesFairPlayAesKeyDirectlyWhenClientDidNotPairVerify() {
    val aesKey = ByteArray(16) { (it * 3 + 1).toByte() }
    val plain = ByteArray(31) { (it * 5 + 7).toByte() }
    val encrypted = encrypt(plain, aesKey, "7")

    FairPlayVideoDecryptor(aesKey, null, "7").decrypt(encrypted)

    assertArrayEquals(plain, encrypted)
  }

  @Test
  fun videoDecryptorStillHashesFairPlayAesKeyWithSharedSecretAfterPairVerify() {
    val aesKey = ByteArray(16) { (it * 3 + 1).toByte() }
    val sharedSecret = ByteArray(32) { (it * 11 + 13).toByte() }
    val pairedKey = MessageDigest.getInstance("SHA-512")
      .apply {
        update(aesKey)
        update(sharedSecret)
      }
      .digest()
      .copyOf(16)
    val plain = ByteArray(31) { (it * 5 + 7).toByte() }
    val encrypted = encrypt(plain, pairedKey, "7")

    FairPlayVideoDecryptor(aesKey, sharedSecret, "7").decrypt(encrypted)

    assertArrayEquals(plain, encrypted)
  }

  private fun encrypt(plain: ByteArray, seedKey: ByteArray, streamId: String): ByteArray {
    val digest = MessageDigest.getInstance("SHA-512")
    val key = digest.apply {
      update("AirPlayStreamKey$streamId".toByteArray(Charsets.UTF_8))
      update(seedKey, 0, 16)
    }.digest().copyOf(16)
    val iv = digest.apply {
      update("AirPlayStreamIV$streamId".toByteArray(Charsets.UTF_8))
      update(seedKey, 0, 16)
    }.digest().copyOf(16)
    return Cipher.getInstance("AES/CTR/NoPadding")
      .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
      .doFinal(plain)
  }
}
