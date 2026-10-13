package com.example.data.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesSecurity {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val IV_SIZE = 16
    private const val KEY_SIZE = 32 // 256 bits

    // Default 32-byte pre-shared key for offline mesh pairing
    private val DEFAULT_KEY_STRING = "ESP32_NRF24_SECURE_RADIO_KEY_256"

    var currentPassphrase: String = DEFAULT_KEY_STRING

    /**
     * Derives a 32-byte (256-bit) AES key using SHA-256.
     */
    fun deriveKey(passphrase: String = currentPassphrase): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(passphrase.toByteArray(Charsets.UTF_8))
    }

    /**
     * Encrypts plaintext bytes using AES-256-CBC with PKCS5 padding.
     * Returns: [16-byte IV] + [AES-256 ciphertext]
     */
    fun encrypt(plainBytes: ByteArray, passphrase: String = currentPassphrase): ByteArray {
        val keyBytes = deriveKey(passphrase)
        val secretKey = SecretKeySpec(keyBytes, ALGORITHM)

        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        val ivSpec = IvParameterSpec(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
        val cipherText = cipher.doFinal(plainBytes)

        val combined = ByteArray(IV_SIZE + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, IV_SIZE)
        System.arraycopy(cipherText, 0, combined, IV_SIZE, cipherText.size)
        return combined
    }

    /**
     * Decrypts [16-byte IV] + [AES-256 ciphertext] back to original bytes.
     */
    fun decrypt(encryptedWithIv: ByteArray, passphrase: String = currentPassphrase): ByteArray? {
        if (encryptedWithIv.size <= IV_SIZE) return null
        return try {
            val keyBytes = deriveKey(passphrase)
            val secretKey = SecretKeySpec(keyBytes, ALGORITHM)

            val iv = ByteArray(IV_SIZE)
            System.arraycopy(encryptedWithIv, 0, iv, 0, IV_SIZE)
            val ivSpec = IvParameterSpec(iv)

            val cipherTextSize = encryptedWithIv.size - IV_SIZE
            val cipherText = ByteArray(cipherTextSize)
            System.arraycopy(encryptedWithIv, IV_SIZE, cipherText, 0, cipherTextSize)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
