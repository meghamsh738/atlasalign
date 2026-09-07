package org.atlasalign.plugin.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.atlasalign.application.guided.AnatomicalSearchPriorV1;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuidedPriorCapturePanelTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsAtNeutralLevelAndLocksBeforeAnyAutomaticOutput() throws Exception {
        final ArrayList<Integer> loaded = new ArrayList<>();
        final AtomicReference<GuidedPriorCapturePanel> panel =
                new AtomicReference<>();
        final AtomicReference<GuidedPriorCapturePanel.CapturedPrior> captured =
                new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panel.set(
                new GuidedPriorCapturePanel(
                        level -> {
                            loaded.add(level);
                            return plane(level);
                        },
                        "provider-a",
                        "1".repeat(64),
                        "2".repeat(64),
                        captured::set)));

        for (int attempt = 0; attempt < 100 && !panel.get().lockControl().isEnabled();
                attempt++) {
            Thread.sleep(10);
            SwingUtilities.invokeAndWait(() -> { });
        }
        SwingUtilities.invokeAndWait(() -> {
            panel.get().startLevelControl().setValue(200);
            panel.get().endLevelControl().setValue(264);
            panel.get().lockControl().doClick();
        });

        assertEquals(List.of(GuidedPriorCapturePanel.NEUTRAL_START_LEVEL), loaded);
        assertEquals(200, captured.get().prior().inclusiveStart()
                .zeroBasedAnteriorPosteriorIndex());
        assertTrue(captured.get().prior().capturedBeforeAutomaticDisplay());
        assertFalse(panel.get().browseLevelControl().isEnabled());
        assertFalse(panel.get().lockControl().isEnabled());
    }

    @Test
    void writerIsCreateOnlyReadOnlyAndMatchesG01FieldContract() throws Exception {
        final AnatomicalSearchPriorV1 prior =
                AnatomicalSearchPriorV1.lockBeforeAutomaticDisplay(
                        "provider-a",
                        "1".repeat(64),
                        "2".repeat(64),
                        new org.atlasalign.application.AllenCoronalLevel(200),
                        new org.atlasalign.application.AllenCoronalLevel(264),
                        org.atlasalign.application.guided.AnatomicalTissueClass
                                .FULL_SECTION,
                        java.time.Instant.parse("2026-08-09T09:00:00Z"),
                        java.time.Instant.parse("2026-08-09T09:00:10Z"),
                        java.util.Optional.empty());
        final Path output = temporaryDirectory.resolve("prior.json");

        GuidedPriorEvidenceWriter.writeCreateOnly(output, prior, 12.5, 1);

        final String text = Files.readString(output);
        assertTrue(text.contains("\"captured_before_automatic_display\":true"));
        assertTrue(text.contains("\"prior_sha256\":\""
                + prior.priorSha256() + "\""));
        assertFalse(Files.isWritable(output));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> GuidedPriorEvidenceWriter.writeCreateOnly(
                        output, prior, 12.5, 1));
    }

    private static AtlasCoronalPlane plane(final int level) {
        final int width = 30;
        final int height = 20;
        final int[] annotation = new int[width * height];
        for (int y = 3; y < 17; y++) {
            for (int x = 4; x < 26; x++) {
                annotation[y * width + x] = x < 15 ? 1 : 2;
            }
        }
        return AtlasCoronalPlane.annotationOnly(
                level,
                width,
                height,
                annotation,
                org.atlasalign.atlas.AtlasPlaneGeometry.axisAligned(
                        level, width, height, height, width));
    }
}
