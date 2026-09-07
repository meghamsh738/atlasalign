package org.atlasalign.plugin.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchProjectLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resumeReconnectsUnchangedOpenSourcesAndKeepsCompletedItems() {
        final ImagePlus first = new ImagePlus("first.tif",
                new ByteProcessor(20, 20));
        final ImagePlus second = new ImagePlus("second.tif",
                new ByteProcessor(18, 16));
        final List<BatchReviewItem> items = new BatchInputFactory()
                .openImages(List.of(first, second), 250, 2);
        final BatchProjectSession saved = new BatchProjectSession(
                "project", temporaryDirectory.resolve("project"), items);
        saved.setStatus("image-001", BatchReviewStatus.COMPLETE,
                "reviewed");
        saved.setStatus("image-002", BatchReviewStatus.REVIEW_OPEN,
                "window was open");

        final BatchProjectSession restored = new BatchProjectLoader().load(
                saved.projectFile(), List.of(second, first));

        assertEquals(BatchReviewStatus.COMPLETE,
                restored.items().get(0).section().status());
        assertEquals(BatchReviewStatus.PENDING,
                restored.items().get(1).section().status());
        assertEquals("second.tif",
                restored.items().get(1).source().getTitle());
    }

    @Test
    void resumeRejectsCalibrationChangesEvenWhenPixelsStillMatch() {
        final ImagePlus image = new ImagePlus("section.tif",
                new ByteProcessor(20, 20));
        image.getCalibration().pixelWidth = 0.65;
        image.getCalibration().setUnit("micron");
        final BatchProjectSession saved = new BatchProjectSession(
                "project", temporaryDirectory.resolve("calibrated"),
                new BatchInputFactory().openImages(
                        List.of(image), 250, 0));

        image.getCalibration().pixelWidth = 1.0;

        assertThrows(IllegalStateException.class,
                () -> new BatchProjectLoader().load(
                        saved.projectFile(), List.of(image)));
    }
}
