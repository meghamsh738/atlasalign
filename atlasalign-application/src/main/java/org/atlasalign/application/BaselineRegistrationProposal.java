package org.atlasalign.application;

import java.util.Objects;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.SimilarityTransform2D;

/**
 * A deterministic registration proposal that always requires user review.
 */
public record BaselineRegistrationProposal(
        AllenCoronalLevel coronalLevel,
        TissueGeometryResult geometry,
        SimilarityTransform2D similarity,
        AffineTransform2D affine,
        RegistrationObjectiveMode objectiveMode,
        double similarityDice,
        double affineDice) {

    public BaselineRegistrationProposal {
        coronalLevel = Objects.requireNonNull(
                coronalLevel, "coronalLevel");
        geometry = Objects.requireNonNull(geometry, "geometry");
        similarity = Objects.requireNonNull(similarity, "similarity");
        affine = Objects.requireNonNull(affine, "affine");
        objectiveMode = Objects.requireNonNull(
                objectiveMode, "objectiveMode");
        requireAtlasToPreview(
                similarity.sourceSpace(),
                similarity.destinationSpace(),
                "similarity");
        requireAtlasToPreview(
                affine.sourceSpace(),
                affine.destinationSpace(),
                "affine");
        if (!validScore(similarityDice) || !validScore(affineDice)) {
            throw new IllegalArgumentException(
                    "Registration Dice scores must be between zero and one");
        }
        if (affine.determinant() <= 0) {
            throw new IllegalArgumentException(
                    "Baseline proposal must not contain a hidden reflection");
        }
    }

    public boolean requiresUserReview() {
        return true;
    }

    private static boolean validScore(final double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }

    private static void requireAtlasToPreview(
            final CoordinateSpace2D source,
            final CoordinateSpace2D destination,
            final String name) {
        if (source != CoordinateSpace2D.ATLAS_PLANE_PIXEL
                || destination != CoordinateSpace2D.PREVIEW_PIXEL) {
            throw new IllegalArgumentException(
                    name + " must map atlas-plane pixels to preview pixels");
        }
    }
}
