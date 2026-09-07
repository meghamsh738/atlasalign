package org.atlasalign.application;

/**
 * Scientific role of a manual correspondence.
 *
 * <p>FIT points may influence a reviewer-triggered transform. CHECK points are
 * held out of every fit and provide an independent residual diagnostic.</p>
 */
public enum LandmarkRole {
    FIT,
    CHECK
}
