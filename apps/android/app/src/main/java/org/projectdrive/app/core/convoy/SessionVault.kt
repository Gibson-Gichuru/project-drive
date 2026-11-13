package org.projectdrive.app.core.convoy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import org.projectdrive.app.BuildConfig

@Singleton
class SessionVault @Inject constructor(@ApplicationContext context: Context) {
    private val file = java.io.File(context.noBackupFilesDir, "convoy-session.aes")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("convoy-session", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("convoy-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun parse(bytes: ByteArray): Session {
        require(bytes.size in 1..8192)
        val token = JSONObject(bytes.toString(Charsets.UTF_8)).getString("token")
        require(token.length in 1..4090 && !token.contains('\n') && !token.contains('\r'))
        val pieces = token.split('.'); require(pieces.size == 3)
        val claims = JSONObject(String(Base64.decode(pieces[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
        val id = claims.getString("sub"); require(UUID.fromString(id).toString() == id)
        val expiry = Math.multiplyExact(claims.getLong("exp"), 1000L)
        // Claims are only a local expiry/display hint. The API verifies the signature and identity.
        require(expiry > System.currentTimeMillis() && expiry <= System.currentTimeMillis() + 16 * 60_000L)
        return Session(token, id, expiry)
    }
    fun save(bytes: ByteArray): Session {
        check(BuildConfig.DEBUG) { "Development session import is disabled" }
        val session = parse(bytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(bytes)
        val atomic = android.util.AtomicFile(file)
        val out = atomic.startWrite()
        try { out.write(cipher.iv.size); out.write(cipher.iv); out.write(encrypted); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
        return session
    }
    fun load(): Session? = runCatching {
        if (!BuildConfig.DEBUG || !file.exists()) return null
        val bytes = android.util.AtomicFile(file).readFully(); require(bytes.size in 30..16384)
        val size = bytes[0].toInt(); require(size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + size))) }
        parse(cipher.doFinal(bytes.copyOfRange(1 + size, bytes.size)))
    }.getOrNull()
    fun clear() { android.util.AtomicFile(file).delete() }
}
