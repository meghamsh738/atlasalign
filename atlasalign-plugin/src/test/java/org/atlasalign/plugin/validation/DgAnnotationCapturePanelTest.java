package org.atlasalign.plugin.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DgAnnotationCapturePanelTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void capturesSourcePixelEvidenceWithUndoRedoAndCreateOnlyLock()
            throws Exception {
        final AtomicReference<DgAnnotationCapturePanel.CapturedDgAnnotation>
                captured = new AtomicReference<>();
        final DgAnnotationCapturePanel[] panel = new DgAnnotationCapturePanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] =
                new DgAnnotationCapturePanel(
                        preview(),
                        "provider-a",
                        "a".repeat(64),
                        "pixel-center-test-mapping",
                        captured::set));

        SwingUtilities.invokeAndWait(() -> {
            panel[0].addPreviewPoint(new Point2D(2, 3));
            panel[0].addPreviewPoint(new Point2D(3, 4));
            assertEquals(2, panel[0].annotationState().centreline().size());
            panel[0].undoControl().doClick();
            assertEquals(1, panel[0].annotationState().centreline().size());
            panel[0].redoControl().doClick();
            assertEquals(2, panel[0].annotationState().centreline().size());
            panel[0].toolControl().setSelectedItem(
                    DgAnnotationCapturePanel.AnnotationTool.DG_CREST);
            panel[0].addPreviewPoint(new Point2D(3, 3));
            panel[0].toolControl().setSelectedItem(
                    DgAnnotationCapturePanel.AnnotationTool
                            .SUPRAPYRAMIDAL_BLADE_ENDPOINT);
            panel[0].addPreviewPoint(new Point2D(2, 2));
            panel[0].toolControl().setSelectedItem(
                    DgAnnotationCapturePanel.AnnotationTool
                            .INFRAPYRAMIDAL_BLADE_ENDPOINT);
            panel[0].addPreviewPoint(new Point2D(4, 5));
            panel[0].toolControl().setSelectedItem(
                    DgAnnotationCapturePanel.AnnotationTool
                            .DORSAL_CORPUS_CALLOSUM_MIDLINE);
            panel[0].addPreviewPoint(new Point2D(5, 1));
            panel[0].lockControl().doClick();
        });
        assertNotNull(captured.get());
        assertTrue(captured.get().annotation().capturedWithoutPlaneOutput());
        assertEquals(2,
                captured.get().annotation().granuleCellLayerCentreline().size());
        assertEquals(1, captured.get().annotation().inputAnchors().size());

        final Path output = temporaryDirectory.resolve("annotation.json");
        DgAnnotationEvidenceWriter.writeCreateOnly(
                output,
                captured.get().annotation(),
                captured.get().entrySeconds(),
                captured.get().preResultRevisionCount());
        final String json = Files.readString(output);
        assertTrue(json.contains("\"captured_without_plane_output\":true"));
        assertTrue(json.contains("\"annotation_sha256\":\""
                + captured.get().annotation().annotationSha256() + "\""));
        assertFalse(json.contains("atlas_level"));
        assertFalse(json.contains("candidate_score"));
        assertThrows(IllegalStateException.class,
                () -> DgAnnotationEvidenceWriter.writeCreateOnly(
                        output,
                        captured.get().annotation(),
                        captured.get().entrySeconds(),
                        captured.get().preResultRevisionCount()));
    }

    private static RegistrationPreview preview() {
        final PreviewMapping mapping = new PreviewMapping(20, 20, 10, 10);
        final float[] pixels = new float[100];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = index;
        }
        return new RegistrationPreview(1, 1, 1, mapping, pixels);
    }
}
