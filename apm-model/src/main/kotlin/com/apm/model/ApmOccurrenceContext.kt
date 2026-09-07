package com.apm.model

/**
 * Immutable application/build identity captured when an event occurs.
 *
 * This snapshot belongs to the durable event. It must not be reconstructed from the process that
 * later drains the outbox because an application upgrade can happen between occurrence and upload.
 * [installationId] must be a host-generated anonymous identifier; the collector pseudonymizes it
 * before durable server persistence.
 */
data class ApmOccurrenceContext(
    /** Host application version name at occurrence time. */
    val serviceVersion: String = "",
    /** Host application versionCode rendered as canonical decimal text. */
    val versionCode: String = "",
    /** Immutable build/release identifier selected by the host build pipeline. */
    val appBuild: String = "",
    /** Build variant or distribution channel identity. */
    val variant: String = "",
    /** Anonymous installation identity at occurrence time. */
    val installationId: String = "",
    /** Optional native frame identities captured by a native crash collector. */
    val nativeFrames: List<ApmNativeFrameIdentity> = emptyList()
) {
    /** Avoid exposing the pseudonymous installation value through accidental config/event logs. */
    override fun toString(): String {
        return "ApmOccurrenceContext(" +
            "serviceVersion=$serviceVersion, versionCode=$versionCode, appBuild=$appBuild, " +
            "variant=$variant, installationId=<redacted>, nativeFrames=$nativeFrames)"
    }
}

/**
 * Build-relative identity for one native program counter.
 *
 * Absolute process addresses are deliberately excluded. The server may symbolize only when ABI,
 * module build ID, and module-relative program counter all match an uploaded immutable artifact.
 */
data class ApmNativeFrameIdentity(
    /** Android ABI such as `arm64-v8a`. */
    val abi: String,
    /** Linker build ID of the exact loaded module. */
    val moduleBuildId: String,
    /** Stable module basename or logical module name. */
    val moduleName: String,
    /** Non-negative program counter relative to the module load address. */
    val moduleRelativePc: Long,
    /** Optional non-negative ELF load bias used to reproduce address translation. */
    val loadBias: Long? = null
)
