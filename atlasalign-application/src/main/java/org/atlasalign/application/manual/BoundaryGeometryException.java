package org.atlasalign.application.manual;

import java.util.Objects;

/** Fail-closed exception carrying one typed local geometry problem. */
public final class BoundaryGeometryException extends IllegalArgumentException {

    private final BoundaryGeometryFailure failure;

    public BoundaryGeometryException(final BoundaryGeometryFailure failure) {
        super(Objects.requireNonNull(failure, "failure").detail());
        this.failure = failure;
    }

    public BoundaryGeometryFailure failure() {
        return failure;
    }
}
