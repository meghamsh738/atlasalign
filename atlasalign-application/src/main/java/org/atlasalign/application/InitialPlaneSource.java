package org.atlasalign.application;

/**
 * Auditable source of an initial coronal-level proposal.
 */
public enum InitialPlaneSource {
    LOCAL_DEEPSLICE,
    MANUAL_FALLBACK,
    /** The reviewer explicitly chose not to attempt automatic inference. */
    MANUAL_ONLY
}
