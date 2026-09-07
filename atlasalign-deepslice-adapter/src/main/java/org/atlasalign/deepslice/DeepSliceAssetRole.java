package org.atlasalign.deepslice;

/**
 * Required role of a file in a managed installation.
 */
public enum DeepSliceAssetRole {
    PYTHON_EXECUTABLE,
    WORKER_SCRIPT,
    PYTHON_RUNTIME,
    DEEPSLICE_PACKAGE,
    NATIVE_LIBRARY,
    MODEL_PRIMARY,
    MODEL_SECONDARY,
    MODEL_BACKBONE,
    METADATA
}
