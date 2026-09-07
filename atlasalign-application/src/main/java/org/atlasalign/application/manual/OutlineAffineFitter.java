package org.atlasalign.application.manual;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;

/**
 * Deterministic global outline-bounds fit in pixel-centre coordinates.
 *
 * <p>The fit maps the atlas boundary centre to the tissue-outline centre and
 * independently scales X and Y to match their axis-aligned extents. It does
 * not infer rotation, shear, or any local/nonlinear warp. Reflection is an
 * explicit caller decision applied about the atlas-bounds centre before the
 * positive-determinant bounds fit.</p>
 */
public final class OutlineAffineFitter {

    public static final String METHOD_ID = "outline-bounds-affine-v1";
    public static final String PIXEL_CENTER_CONVENTION =
            "top-left-pixel-center-is-(0,0)";
    public static final double MAXIMUM_ANISOTROPY = 5.0;
    private static final double MINIMUM_NORMALIZED_EXTENT = 1e-12;

    private OutlineAffineFitter() {
    }

    /** Fits without reflecting the atlas. */
    public static FitResult fit(
            final List<Point2D> atlasBoundary,
            final List<Point2D> tissueOutline) {
        return fit(atlasBoundary, tissueOutline, false);
    }

    /**
     * Fits atlas-plane pixels to source pixels after the supplied explicit
     * orientation choice. Lists need not have equal sizes or corresponding
     * vertices.
     */
    public static FitResult fit(
            final List<Point2D> atlasBoundary,
            final List<Point2D> tissueOutline,
            final boolean reflectAtlasX) {
        final List<Point2D> atlas = validatedCopy(
                atlasBoundary, "atlas boundary");
        final List<Point2D> tissue = validatedCopy(
                tissueOutline, "tissue outline");
        final Bounds atlasBounds = bounds(atlas, "atlas boundary");
        final Bounds tissueBounds = bounds(tissue, "tissue outline");
        final double scaleX = tissueBounds.width() / atlasBounds.width();
        final double scaleY = tissueBounds.height() / atlasBounds.height();
        requirePositiveFinite(scaleX, "X scale");
        requirePositiveFinite(scaleY, "Y scale");

        final Point2D atlasCenter = atlasBounds.center();
        final Point2D tissueCenter = tissueBounds.center();
        final AffineTransform2D orientedAtlasToSource =
                new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.SOURCE_PIXEL,
                        scaleX, 0,
                        tissueCenter.x() - scaleX * atlasCenter.x(),
                        0, scaleY,
                        tissueCenter.y() - scaleY * atlasCenter.y());
        final double anisotropy = Math.max(scaleX, scaleY)
                / Math.min(scaleX, scaleY);
        if (!Double.isFinite(anisotropy)
                || anisotropy > MAXIMUM_ANISOTROPY) {
            throw new IllegalArgumentException(
                    "Outline fit anisotropy exceeds the safe bound of "
                            + MAXIMUM_ANISOTROPY);
        }
        final String contentHash = contentHash(
                atlas, tissue, reflectAtlasX, orientedAtlasToSource);
        final Diagnostics diagnostics = new Diagnostics(
                METHOD_ID,
                PIXEL_CENTER_CONVENTION,
                atlas.size(),
                tissue.size(),
                atlasBounds,
                tissueBounds,
                scaleX,
                scaleY,
                anisotropy,
                orientedAtlasToSource.determinant(),
                contentHash);
        return new FitResult(
                orientedAtlasToSource,
                reflectAtlasX,
                atlasCenter,
                diagnostics);
    }

    private static List<Point2D> validatedCopy(
            final List<Point2D> points,
            final String name) {
        final List<Point2D> copy = List.copyOf(
                Objects.requireNonNull(points, name));
        if (copy.size() < 3) {
            throw new IllegalArgumentException(
                    name + " requires at least three points");
        }
        for (final Point2D point : copy) {
            Objects.requireNonNull(point, name + " point");
        }
        return copy;
    }

    private static Bounds bounds(
            final List<Point2D> points,
            final String name) {
        double minimumX = Double.POSITIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        for (final Point2D point : points) {
            minimumX = Math.min(minimumX, point.x());
            minimumY = Math.min(minimumY, point.y());
            maximumX = Math.max(maximumX, point.x());
            maximumY = Math.max(maximumY, point.y());
        }
        final Bounds result = new Bounds(
                minimumX, minimumY, maximumX, maximumY);
        final double largestExtent = Math.max(result.width(), result.height());
        if (!(largestExtent > 0)
                || result.width() / largestExtent
                        < MINIMUM_NORMALIZED_EXTENT
                || result.height() / largestExtent
                        < MINIMUM_NORMALIZED_EXTENT) {
            throw new IllegalArgumentException(
                    name + " must have nonzero two-dimensional X/Y extent");
        }
        return result;
    }

    private static String contentHash(
            final List<Point2D> atlas,
            final List<Point2D> tissue,
            final boolean reflected,
            final AffineTransform2D transform) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateText(digest, METHOD_ID);
            updateText(digest, PIXEL_CENTER_CONVENTION);
            updateInt(digest, atlas.size());
            for (final Point2D point : atlas) {
                updateDouble(digest, point.x());
                updateDouble(digest, point.y());
            }
            updateInt(digest, tissue.size());
            for (final Point2D point : tissue) {
                updateDouble(digest, point.x());
                updateDouble(digest, point.y());
            }
            digest.update((byte) (reflected ? 1 : 0));
            updateDouble(digest, transform.m00());
            updateDouble(digest, transform.m01());
            updateDouble(digest, transform.m02());
            updateDouble(digest, transform.m10());
            updateDouble(digest, transform.m11());
            updateDouble(digest, transform.m12());
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void updateText(
            final MessageDigest digest,
            final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(
            final MessageDigest digest,
            final int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void updateDouble(
            final MessageDigest digest,
            final double value) {
        digest.update(ByteBuffer.allocate(Double.BYTES)
                .putLong(Double.doubleToLongBits(value)).array());
    }

    private static void requirePositiveFinite(
            final double value,
            final String name) {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    name + " must be positive and finite");
        }
    }

    /** Immutable typed fit output. */
    public record FitResult(
            AffineTransform2D orientedAtlasToSource,
            boolean atlasReflected,
            Point2D atlasReflectionCenter,
            Diagnostics diagnostics) {

        public FitResult {
            orientedAtlasToSource = Objects.requireNonNull(
                    orientedAtlasToSource, "orientedAtlasToSource");
            atlasReflectionCenter = Objects.requireNonNull(
                    atlasReflectionCenter, "atlasReflectionCenter");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            if (orientedAtlasToSource.sourceSpace()
                    != CoordinateSpace2D.ATLAS_PLANE_PIXEL
                    || orientedAtlasToSource.destinationSpace()
                    != CoordinateSpace2D.SOURCE_PIXEL
                    || orientedAtlasToSource.determinant() <= 0) {
                throw new IllegalArgumentException(
                        "Fit must map oriented atlas pixels to source pixels with positive determinant");
            }
        }

        /** Applies explicit reflection, if selected, followed by the fit. */
        public Point2D applyAtlasPoint(final Point2D atlasPoint) {
            Objects.requireNonNull(atlasPoint, "atlasPoint");
            return atlasToSource().apply(atlasPoint);
        }

        /**
         * Returns the complete raw-atlas-to-source mapping. Its determinant is
         * negative only when the caller explicitly selected reflection.
         */
        public AffineTransform2D atlasToSource() {
            if (!atlasReflected) {
                return orientedAtlasToSource;
            }
            final AffineTransform2D reflection = new AffineTransform2D(
                    CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                    CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                    -1, 0, 2 * atlasReflectionCenter.x(),
                    0, 1, 0);
            return reflection.andThen(orientedAtlasToSource);
        }
    }

    /** Descriptive provenance only; it is not an accuracy/confidence score. */
    public record Diagnostics(
            String methodId,
            String pixelCenterConvention,
            int atlasPointCount,
            int tissuePointCount,
            Bounds atlasBounds,
            Bounds tissueBounds,
            double scaleX,
            double scaleY,
            double anisotropyRatio,
            double fittedDeterminant,
            String contentSha256) {

        public Diagnostics {
            methodId = requireText(methodId, "methodId");
            pixelCenterConvention = requireText(
                    pixelCenterConvention, "pixelCenterConvention");
            atlasBounds = Objects.requireNonNull(atlasBounds, "atlasBounds");
            tissueBounds = Objects.requireNonNull(tissueBounds, "tissueBounds");
            contentSha256 = requireText(contentSha256, "contentSha256");
            if (atlasPointCount < 3 || tissuePointCount < 3
                    || !Double.isFinite(scaleX)
                    || !Double.isFinite(scaleY)
                    || !Double.isFinite(anisotropyRatio)
                    || !Double.isFinite(fittedDeterminant)
                    || scaleX <= 0 || scaleY <= 0
                    || anisotropyRatio < 1 || fittedDeterminant <= 0
                    || contentSha256.length() != 64) {
                throw new IllegalArgumentException(
                        "Outline fit diagnostics must be finite and valid");
            }
        }
    }

    /** Inclusive point-centre bounds. */
    public record Bounds(
            double minimumX,
            double minimumY,
            double maximumX,
            double maximumY) {

        public Bounds {
            if (!Double.isFinite(minimumX)
                    || !Double.isFinite(minimumY)
                    || !Double.isFinite(maximumX)
                    || !Double.isFinite(maximumY)
                    || maximumX < minimumX
                    || maximumY < minimumY) {
                throw new IllegalArgumentException("Bounds must be finite");
            }
        }

        public double width() {
            return maximumX - minimumX;
        }

        public double height() {
            return maximumY - minimumY;
        }

        public Point2D center() {
            return new Point2D(
                    minimumX + width() / 2,
                    minimumY + height() / 2);
        }
    }

    private static String requireText(
            final String value,
            final String name) {
        final String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
