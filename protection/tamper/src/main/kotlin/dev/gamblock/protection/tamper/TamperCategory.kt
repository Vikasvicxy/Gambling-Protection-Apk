package dev.gamblock.protection.tamper

/** Categories of tamper-relevant evidence the log can hold. */
enum class TamperCategory {
    /** Root/superuser indicators present. */
    ROOT_DETECTED,
    /** Xposed-style runtime instrumentation present. */
    XPOSE_DETECTED,
    /** Build was signed with AOSP test keys. */
    TEST_KEYS,
    /** Package is running debuggable (dangerous on a blocking app). */
    DEBUGGABLE_APK,
    /** App certificate mismatch vs. installed package. */
    MODIFIED_APK,
    /** A signed update failed signature/hash verification. */
    UPDATE_SIGNATURE_FAILURE,
    /** The evidence chain itself failed HMAC verification. */
    EVIDENCE_CHAIN_INVALID,
    /** Fallback for unknown causes. */
    UNKNOWN,
}