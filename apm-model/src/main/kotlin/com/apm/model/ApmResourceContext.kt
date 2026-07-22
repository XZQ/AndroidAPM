package com.apm.model

/**
 * Standard batch-level resource identity shared by every event in one upload.
 *
 * Values are host-provided and must be stable, non-secret identifiers. User IDs, access tokens,
 * advertising IDs, and other direct personal identifiers do not belong in this resource block.
 */
data class ApmResourceContext(
    /** Logical service/application name used by the collector for dataset partitioning. */
    val serviceName: String = "",
    /** Host application release version, for example `2.4.1` or a version code. */
    val serviceVersion: String = "",
    /** Deployment environment such as `production`, `staging`, or `development`. */
    val deploymentEnvironment: String = "",
    /** Host-generated anonymous installation identity used for device-level continuity. */
    val installationId: String = ""
)
