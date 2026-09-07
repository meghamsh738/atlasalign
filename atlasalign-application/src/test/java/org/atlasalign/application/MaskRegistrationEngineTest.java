package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import java.util.BitSet;
import java.util.List;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.SimilarityTransform2D;
import org.junit.jupiter.api.Test;

class MaskRegistrationEngineTest {

    private static final int DESTINATION_WIDTH = 240;
    private static final int DESTINATION_HEIGHT = 210;
    private final MaskRegistrationEngine engine =
            new MaskRegistrationEngine();
    private final TissueGeometryClassifier classifier =
            new TissueGeometryClassifier();

    /** CI can retain every correctness assertion without claiming workstation timing evidence. */
    private static void assertInteractiveBudget(final String scenario, final Runnable operation) {
        if (Boolean.parseBoolean(System.getProperty("atlasalign.performanceChecks", "true"))) {
            assertTimeout(Duration.ofSeconds(10), operation::run);
            return;
        }
        final long started = System.nanoTime();
        try {
            operation.run();
        } finally {
            System.out.println("Registration timing (budget disabled): " + scenario + " = "
                    + (System.nanoTime() - started) / 1_000_000.0 + " ms; local budget=10000 ms");
        }
    }

    @Test
    void recoversFixedSimilarityCasesWithinOnePixel() {
        final List<SimilarityTransform2D> cases = List.of(
                similarity(0.85, -12, -20, 20),
                similarity(1.15, 12, 20, -20),
                similarity(1.00, 0, -20, -20));
        final BinaryMask source = SyntheticMasks.paddedFullSection();
        for (final SimilarityTransform2D expected : cases) {
            final BinaryMask destination = MaskWarp.warp(
                    source,
                    DESTINATION_WIDTH,
                    DESTINATION_HEIGHT,
                    expected.asAffine());

            final BaselineRegistrationProposal proposal = engine.register(
                    source,
                    destination,
                    new AllenCoronalLevel(240),
                    classifier.classify(destination));

            assertEquals(
                    RegistrationObjectiveMode
                            .EXACT_FULL_RESOLUTION_COMPLETE_BOUNDARY,
                    proposal.objectiveMode());
            assertTrue(proposal.similarityDice() >= 0.97);
            assertTrue(controlPointRms(
                    expected.asAffine(),
                    proposal.similarity(),
                    source) <= 1.0);
            assertTrue(proposal.requiresUserReview());
        }
    }

    @Test
    void refinesAConservativeAffineCase() {
        final BinaryMask source = SyntheticMasks.paddedFullSection();
        final List<AffineTransform2D> cases = List.of(
                affine(1.04, 0.02, 14, 0.08, 0.96, 10),
                affine(0.96, -0.06, 18, -0.02, 1.05, 12));
        for (final AffineTransform2D expected : cases) {
            final BinaryMask destination = MaskWarp.warp(
                    source,
                    DESTINATION_WIDTH,
                    DESTINATION_HEIGHT,
                    expected);
            final BaselineRegistrationProposal proposal = engine.register(
                    source,
                    destination,
                    new AllenCoronalLevel(240),
                    classifier.classify(destination));

            assertTrue(proposal.affineDice() >= 0.97);
            final double error =
                    controlPointRms(expected, proposal.affine(), source);
            assertTrue(
                    error <= 1.5,
                    () -> "Control-point RMS was " + error
                            + " for " + proposal.affine());
        }
    }

    @Test
    void supportsNativeAtlasToOrdinaryPreviewScale() {
        final BinaryMask source = SyntheticMasks.fullSection();
        final SimilarityTransform2D expected =
                similarity(3.3, 4, 55, 38);
        final BinaryMask destination = MaskWarp.warp(
                source, 520, 420, expected.asAffine());

        final BaselineRegistrationProposal proposal = engine.register(
                source,
                destination,
                new AllenCoronalLevel(240),
                classifier.classify(destination));

        assertTrue(proposal.similarityDice() >= 0.97);
        final double nativeScaleError = controlPointRms(
                expected.asAffine(),
                proposal.similarity(),
                source);
        assertTrue(
                nativeScaleError <= 2.0,
                () -> "Native-scale RMS was " + nativeScaleError
                        + " for " + proposal.similarity());
    }

    @Test
    void registersDenseNoisyBoundaryWithinInteractiveBudget() {
        final BinaryMask mask = denseMaskWithInteriorHoles(
                320, 240);

        assertInteractiveBudget("dense noisy boundary", () -> {
            final BaselineRegistrationProposal proposal =
                    engine.register(
                            mask,
                            mask,
                            new AllenCoronalLevel(240),
                            classifier.classify(mask));
            assertTrue(proposal.affineDice() >= 0.999);
        });
    }

    @Test
    void registersFullPreviewFragmentedBoundaryWithinInteractiveBudget() {
        final BinaryMask mask = denseMaskWithInteriorHoles(
                953, 600);

        assertInteractiveBudget("full-preview fragmented boundary", () -> {
            final BaselineRegistrationProposal proposal =
                    engine.register(
                            mask,
                            mask,
                            new AllenCoronalLevel(240),
                            classifier.classify(mask));
            assertTrue(proposal.affineDice() >= 0.999);
        });
    }

    @Test
    void preservesKnownTransformWithOneSidedInteriorFragmentation() {
        final BinaryMask source =
                SyntheticMasks.fullSection();
        final SimilarityTransform2D expected =
                similarity(3.3, 4, 55, 38);
        final BinaryMask smoothDestination = MaskWarp.warp(
                source,
                520,
                420,
                expected.asAffine());
        final BinaryMask fragmentedDestination =
                addDeterministicInteriorHoles(
                        smoothDestination);
        assertTrue(boundaryPointCount(source)
                < 4_096);
        assertTrue(boundaryPointCount(fragmentedDestination)
                > 4_096);

        assertInteractiveBudget("one-sided interior fragmentation", () -> {
            final BaselineRegistrationProposal proposal =
                    engine.register(
                            source,
                            fragmentedDestination,
                            new AllenCoronalLevel(240),
                            classifier.classify(
                                    fragmentedDestination));
            assertTrue(
                    proposal.affineDice() >= 0.95,
                    () -> "Exterior-boundary Dice was "
                            + proposal.affineDice()
                            + " for " + proposal.affine());
            final double error = controlPointRms(
                    expected.asAffine(),
                    proposal.affine(),
                    source);
            assertTrue(
                    error <= 2.0,
                    () -> "Exterior-boundary RMS was " + error
                            + " for " + proposal.affine());
        });
    }

    @Test
    void allocationFreeWarpMatchesLegacyImplementationExactly() {
        final BinaryMask source =
                denseMaskWithInteriorHoles(37, 29);
        final List<AffineTransform2D> transforms = List.of(
                affine(1.03, 0.07, -3.5, -0.04, 0.97, 2.5),
                affine(0.91, -0.12, 4.499999999,
                        0.08, 1.08, -1.500000001),
                affine(1, 0, 0.5, 0, 1, -0.5));
        for (final AffineTransform2D transform : transforms) {
            assertEquals(
                    legacyWarp(source, 41, 33, transform),
                    MaskWarp.warp(source, 41, 33, transform));
        }
    }

    @Test
    void frozenFullPreviewPairRegistersWithinInteractiveBudget() {
        final BinaryMask atlas = frozenFragmentedAtlasMask();
        final BinaryMask preview =
                frozenFullPreviewFrom(atlas);

        assertInteractiveBudget("frozen full-preview pair", () -> {
            final BaselineRegistrationProposal proposal =
                    engine.register(
                            atlas,
                            preview,
                            new AllenCoronalLevel(264),
                            classifier.classify(preview));
            assertEquals(
                    RegistrationObjectiveMode
                            .EXACT_FULL_RESOLUTION_EXTERIOR_BOUNDARY,
                    proposal.objectiveMode());
            final BinaryMask similarityWarp = MaskWarp.warp(
                    atlas,
                    preview.width(),
                    preview.height(),
                    proposal.similarity().asAffine());
            final BinaryMask affineWarp = MaskWarp.warp(
                    atlas,
                    preview.width(),
                    preview.height(),
                    proposal.affine());
            assertEquals(
                    MaskWarp.dice(similarityWarp, preview),
                    proposal.similarityDice(),
                    0);
            assertEquals(
                    MaskWarp.dice(affineWarp, preview),
                    proposal.affineDice(),
                    0);
            assertEquals(preview.width(), affineWarp.width());
            assertEquals(preview.height(), affineWarp.height());
        });
    }

    @Test
    void scoreTaskPartitionIsOverflowSafe() {
        assertEquals(
                1,
                MaskRegistrationEngine.pointsPerTask(1, 1));
        assertEquals(
                4,
                MaskRegistrationEngine.pointsPerTask(10, 3));
        assertEquals(
                178_956_971,
                MaskRegistrationEngine.pointsPerTask(
                        Integer.MAX_VALUE, 12));
        assertThrows(
                IllegalArgumentException.class,
                () -> MaskRegistrationEngine.pointsPerTask(2, 3));
    }

    @Test
    void rejectsWrongDirectionWarpsAndProposals() {
        final BinaryMask source = SyntheticMasks.fullSection();
        final AffineTransform2D reversed = new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                1, 0, 0, 0, 1, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> MaskWarp.warp(source, 128, 96, reversed));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BaselineRegistrationProposal(
                        new AllenCoronalLevel(240),
                        classifier.classify(source),
                        new SimilarityTransform2D(
                                CoordinateSpace2D.PREVIEW_PIXEL,
                                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                                1, 0, 0, 0),
                        reversed,
                        RegistrationObjectiveMode
                                .EXACT_FULL_RESOLUTION_COMPLETE_BOUNDARY,
                        1,
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new BaselineRegistrationProposal(
                        new AllenCoronalLevel(240),
                        classifier.classify(source),
                        similarity(1, 0, 0, 0),
                        affine(-1, 0, 455, 0, 1, 0),
                        RegistrationObjectiveMode
                                .EXACT_FULL_RESOLUTION_COMPLETE_BOUNDARY,
                        1,
                        1));
    }

    private static SimilarityTransform2D similarity(
            final double scale,
            final double degrees,
            final double translationX,
            final double translationY) {
        return new SimilarityTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                scale,
                Math.toRadians(degrees),
                translationX,
                translationY);
    }

    private static double controlPointRms(
            final AffineTransform2D expected,
            final SimilarityTransform2D actual,
            final BinaryMask source) {
        return controlPointRms(expected, actual.asAffine(), source);
    }

    private static double controlPointRms(
            final AffineTransform2D expected,
            final AffineTransform2D actual,
            final BinaryMask source) {
        final org.atlasalign.core.MaskBounds bounds = source.bounds();
        final List<Point2D> points = List.of(
                new Point2D(bounds.minimumX(), bounds.minimumY()),
                new Point2D(bounds.maximumX(), bounds.minimumY()),
                new Point2D(bounds.minimumX(), bounds.maximumY()),
                new Point2D(bounds.maximumX(), bounds.maximumY()),
                new Point2D(
                        (bounds.minimumX() + bounds.maximumX()) * 0.5,
                        (bounds.minimumY() + bounds.maximumY()) * 0.5));
        double squaredError = 0;
        for (final Point2D point : points) {
            final Point2D expectedPoint = expected.apply(point);
            final Point2D actualPoint = actual.apply(point);
            squaredError += Math.pow(
                    expectedPoint.x() - actualPoint.x(), 2);
            squaredError += Math.pow(
                    expectedPoint.y() - actualPoint.y(), 2);
        }
        return Math.sqrt(squaredError / points.size());
    }

    private static AffineTransform2D affine(
            final double m00,
            final double m01,
            final double m02,
            final double m10,
            final double m11,
            final double m12) {
        return new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                m00, m01, m02, m10, m11, m12);
    }

    private static BinaryMask denseMaskWithInteriorHoles(
            final int width,
            final int height) {
        final boolean[] values =
                new boolean[Math.multiplyExact(width, height)];
        for (int y = 8; y < height - 8; y++) {
            for (int x = 8; x < width - 8; x++) {
                values[y * width + x] =
                        (x * 31 + y * 17) % 97 != 0;
            }
        }
        return BinaryMask.fromBooleans(
                width, height, values);
    }

    private static BinaryMask addDeterministicInteriorHoles(
            final BinaryMask mask) {
        final boolean[] values = new boolean[
                Math.multiplyExact(mask.width(), mask.height())];
        for (int y = 0; y < mask.height(); y++) {
            for (int x = 0; x < mask.width(); x++) {
                final boolean removableInterior =
                        mask.contains(x, y)
                                && mask.contains(x - 1, y)
                                && mask.contains(x + 1, y)
                                && mask.contains(x, y - 1)
                                && mask.contains(x, y + 1);
                values[y * mask.width() + x] =
                        mask.contains(x, y)
                                && !(removableInterior
                                && (x * 31 + y * 17) % 17 == 0);
            }
        }
        return BinaryMask.fromBooleans(
                mask.width(), mask.height(), values);
    }

    private static int boundaryPointCount(
            final BinaryMask mask) {
        int count = 0;
        for (int y = 0; y < mask.height(); y++) {
            for (int x = 0; x < mask.width(); x++) {
                if (mask.contains(x, y)
                        && (!mask.contains(x - 1, y)
                        || !mask.contains(x + 1, y)
                        || !mask.contains(x, y - 1)
                        || !mask.contains(x, y + 1))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static BinaryMask legacyWarp(
            final BinaryMask source,
            final int destinationWidth,
            final int destinationHeight,
            final AffineTransform2D sourceToDestination) {
        final AffineTransform2D destinationToSource =
                sourceToDestination.inverse();
        final BitSet result =
                new BitSet(destinationWidth * destinationHeight);
        for (int y = 0; y < destinationHeight; y++) {
            for (int x = 0; x < destinationWidth; x++) {
                final Point2D sourcePoint =
                        destinationToSource.apply(new Point2D(x, y));
                final int sourceX =
                        (int) Math.round(sourcePoint.x());
                final int sourceY =
                        (int) Math.round(sourcePoint.y());
                if (source.contains(sourceX, sourceY)) {
                    result.set(y * destinationWidth + x);
                }
            }
        }
        return BinaryMask.fromBitSet(
                destinationWidth, destinationHeight, result);
    }

    private static BinaryMask frozenFragmentedAtlasMask() {
        final int width = 456;
        final int height = 320;
        final boolean[] values = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final double normalizedX =
                        (x - 227.5) / 190;
                final double normalizedY =
                        (y - 159.5) / 130;
                values[y * width + x] =
                        normalizedX * normalizedX
                                + normalizedY * normalizedY <= 1
                                && (y % 4 < 2
                                || Math.abs(x - 227.5) < 8);
            }
        }
        return BinaryMask.fromBooleans(width, height, values);
    }

    private static BinaryMask frozenFullPreviewFrom(
            final BinaryMask atlas) {
        final int width = 2_048;
        final int height = 1_462;
        final boolean[] values = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int atlasX = (int) Math.round(
                        (x + 0.5) * atlas.width() / width - 0.5);
                final int atlasY = (int) Math.round(
                        (y + 0.5) * atlas.height() / height - 0.5);
                values[y * width + x] =
                        atlas.contains(atlasX, atlasY);
            }
        }
        return BinaryMask.fromBooleans(width, height, values);
    }
}
