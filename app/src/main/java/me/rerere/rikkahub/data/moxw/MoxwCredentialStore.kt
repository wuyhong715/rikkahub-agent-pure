package me.rerere.rikkahub.data.moxw

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted-at-rest storage for the Moxw account, mirroring the Codex/Grok/Gemini stores:
 * AES/GCM under an AndroidKeyStore key, in `noBackupFilesDir` so a cloud backup cannot
 * carry a session to a device that has no matching key.
 */
class MoxwCredentialStore(
    context: Context,
    private val json: Json,
) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    fun read(): MoxwAccountState {
        if (!file.exists()) return MoxwAccountState()
        return runCatching {
            val bytes = file.readBytes()
            require(bytes.size > IV_SIZE)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH, iv))
            json.decodeFromString<MoxwAccountState>(cipher.doFinal(encrypted).decodeToString())
        }.getOrElse {
            MoxwAccountState()
        }
    }

    fun write(state: MoxwAccountState) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.encodeToString(state).encodeToByteArray())
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        temporary.writeBytes(cipher.iv + encrypted)
        // Atomic rename in the same directory, NOT copyTo over the live file: this file holds
        // the only copy of the rotating refresh token, and a process death mid-copy leaves it
        // truncated - read() then silently falls back to "signed out" and the user has to log
        // in again. copyTo stays as the fallback for filesystems where renaming onto an
        // existing target fails.
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    fun clear() {
        file.delete()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val FILE_NAME = "moxw_account.enc"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "rikkahub_moxw_account"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH = 128
    }
}
