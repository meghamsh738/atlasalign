package org.atlasalign.application;

/**
 * Auditable reason that automatic plane estimation was not used.
 */
public enum ManualFallbackReason {
    NOT_CONFIGURED,
    NOT_INSTALLED,
    UNSUPPORTED_PLATFORM,
    MANIFEST_INVALID,
    INSTALLATION_CORRUPT,
    NETWORK_ISOLATION_UNAVAILABLE,
    INFERENCE_TIMEOUT,
    WORKER_FAILED,
    INVALID_WORKER_OUTPUT,
    VIRTUAL_HALF_PREPARATION_MANUAL_REQUIRED
}
