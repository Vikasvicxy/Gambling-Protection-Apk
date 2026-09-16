package dev.gamblock.core.common.logging

import android.util.Log

/**
 * Logging seam. Production uses [AndroidLogger]. Never log credentials, tokens or
 * personal content - the VAULT name makes the policy explicit.
 */
interface ShieldLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

class AndroidLogger : ShieldLogger {
    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}

object Logs {
    const val VAULT = "ShieldVault" // reserved: never put secrets here
    const val SECURITY = "ShieldSecurity"
    const val VPN = "ShieldVpn"
    const val DNS = "ShieldDns"
    const val HEALTH = "ShieldHealth"
    const val COMMITMENT = "ShieldCommitment"
    const val BOOT = "ShieldBoot"
    const val DB = "ShieldDb"
    const val UI = "ShieldUi"
}