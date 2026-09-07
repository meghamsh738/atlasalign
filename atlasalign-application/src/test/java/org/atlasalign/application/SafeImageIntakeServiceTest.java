package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.atlasalign.core.CalibrationMetadata;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.core.SourceImageMetadata;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.core.StackPlaneLabel;
import org.junit.jupiter.api.Test;

class SafeImageIntakeServiceTest {

    private static final SourceImageMetadata METADATA = new SourceImageMetadata(
            100, 80, 2, 1, 1, 16, List.of("DAPI", "GFP"),
            List.of(
                    StackPlaneLabel.fromNullable("DAPI"),
                    StackPlaneLabel.fromNullable("GFP")),
            new CalibrationMetadata(0.5, 0.5, 1.0, 1.0, "um", "sec"));

    @Test
    void returnsPreviewWhenSourceIdentityIsUnchanged() {
        final SourceImageSnapshot stable = snapshot("0");
        final ReadOnlySourceImage source = new FakeSource(stable, stable);

        final SafePreviewResult result =
                new SafeImageIntakeService().preparePreview(source, 2, 64);

        assertEquals(2, result.preview().channel());
        assertEquals(stable, result.verifiedSource());
    }

    @Test
    void failsClosedWhenPixelsChange() {
        final ReadOnlySourceImage source = new FakeSource(snapshot("0"), snapshot("1"));

        assertThrows(SourceVerificationException.class,
                () -> new SafeImageIntakeService().preparePreview(source, 1, 64));
    }

    @Test
    void failsClosedWhenScientificMetadataChanges() {
        final SourceImageMetadata changedMetadata = new SourceImageMetadata(
                100, 80, 2, 1, 1, 16, List.of("DAPI", "GFP"),
                List.of(
                        StackPlaneLabel.fromNullable("DAPI"),
                        StackPlaneLabel.fromNullable("GFP")),
                new CalibrationMetadata(0.75, 0.5, 1.0, 1.0, "um", "sec"));
        final ReadOnlySourceImage source = new FakeSource(
                snapshot("0"),
                new SourceImageSnapshot(changedMetadata, "0".repeat(64)));

        assertThrows(SourceVerificationException.class,
                () -> new SafeImageIntakeService().preparePreview(source, 1, 64));
    }

    @Test
    void validatesChannelBeforeCreatingPreview() {
        final AtomicBoolean previewCalled = new AtomicBoolean();
        final ReadOnlySourceImage source = new FakeSource(snapshot("0"), snapshot("0")) {
            @Override
            public RegistrationPreview createPreview(final int channel, final int maximumDimension) {
                previewCalled.set(true);
                return super.createPreview(channel, maximumDimension);
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> new SafeImageIntakeService().preparePreview(source, 3, 64));
        assertEquals(false, previewCalled.get());
    }

    private static SourceImageSnapshot snapshot(final String digit) {
        return new SourceImageSnapshot(METADATA, digit.repeat(64));
    }

    private static class FakeSource implements ReadOnlySourceImage {
        private final SourceImageSnapshot before;
        private final SourceImageSnapshot after;
        private int snapshotCalls;

        FakeSource(final SourceImageSnapshot before, final SourceImageSnapshot after) {
            this.before = before;
            this.after = after;
        }

        @Override
        public SourceImageSnapshot snapshot() {
            return snapshotCalls++ == 0 ? before : after;
        }

        @Override
        public RegistrationPreview createPreview(final int channel, final int maximumDimension) {
            final PreviewMapping mapping = PreviewMapping.bounded(100, 80, maximumDimension);
            return new RegistrationPreview(
                    channel, 1, 1, mapping,
                    new float[mapping.previewWidth() * mapping.previewHeight()]);
        }
    }
}
