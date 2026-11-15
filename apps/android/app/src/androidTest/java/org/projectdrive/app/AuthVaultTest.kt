package org.projectdrive.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.projectdrive.app.core.convoy.SessionVault
import java.io.File
import java.util.UUID

class AuthVaultTest {
    @Test fun credentialsUseKeystoreEncryptionAndCorruptionFailsClosed(){
        val owner=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(owner.cacheDir,"auth-vault-test-${UUID.randomUUID()}").apply{mkdirs()}
        val context=object:ContextWrapper(owner){override fun getNoBackupFilesDir()=directory}
        try {
            val bytes="synthetic-refresh-token-not-a-real-session".toByteArray()
            SessionVault(context).saveAccount(bytes)
            assertArrayEquals(bytes,SessionVault(context).loadAccount())
            val encrypted=File(directory,"supabase-session.aes")
            assertFalse(encrypted.readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-refresh-token"))
            val changed=encrypted.readBytes();changed[changed.lastIndex]=(changed.last().toInt() xor 1).toByte();encrypted.writeBytes(changed)
            assertTrue(runCatching{SessionVault(context).loadAccount()}.isFailure)
            SessionVault(context).clearAccount();assertNull(SessionVault(context).loadAccount())
        }finally{directory.deleteRecursively()}
    }
}
