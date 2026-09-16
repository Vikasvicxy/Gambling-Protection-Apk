package dev.gamblock.core.testing

import dev.gamblock.core.common.logging.ShieldLogger

/** Discards all log output. Safe for every test that only needs a logger seam. */
object NoOpLogger : ShieldLogger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}