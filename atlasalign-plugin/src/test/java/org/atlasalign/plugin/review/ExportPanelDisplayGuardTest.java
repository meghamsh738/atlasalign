package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.atlasalign.application.AlignmentReviewSession;
import org.atlasalign.application.ReviewAcceptanceVerification;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.export.SourcePixelReader;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.application.roi.ReviewerRoiSide;
import org.atlasalign.application.roi.RoiPartOperation;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.plugin.export.ManualRoiExportService;
import org.atlasalign.plugin.export.SourceSpaceExportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportPanelDisplayGuardTest {
    @TempDir Path output;

    @Test void manualSuccessCannotReenableExportDuringInspection() throws Exception { manualCompletion(false); }
    @Test void manualFailureCannotReenableExportDuringInspection() throws Exception { manualCompletion(true); }
    @Test void atlasSuccessCannotReenableExportDuringInspection() throws Exception { atlasCompletion(false); }
    @Test void atlasFailureCannotReenableExportDuringInspection() throws Exception { atlasCompletion(true); }

    private void manualCompletion(final boolean fail) throws Exception {
        final var basis = ReviewPluginFixtures.basis();
        final var source = new DelayedSource(basis.sourceSnapshot(), fail);
        final var finished = new CountDownLatch(1);
        final var starts = new AtomicInteger();
        final ManualRoiEditorPanel[] panel = {null};
        final JPanel[] compact = {null};
        final ReviewController[] controller = {null};
        final ReviewerRoiSession rois = new ReviewerRoiSession("section", 100, 80);
        final String id = rois.newPolygon("Region", ReviewerRoiSide.LEFT, RoiPartOperation.ADD);
        rois.addVertices(List.of(new Point2D(10, 10), new Point2D(25, 10), new Point2D(25, 25), new Point2D(10, 25)));
        rois.finishActivePart();
        final var selection = ExportSelection.allChannels(basis.sourceSnapshot().metadata(), 1, 1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                controller[0] = controller(new AlignmentReviewSession(basis));
                panel[0] = new ManualRoiEditorPanel(rois, basis.previewMapping(), new ReviewCanvas(controller[0]),
                        Optional.of(new ManualRoiExportService(source, basis.sourceSnapshot(), 1, 1, 1)),
                        "source.tif", () -> ReviewerRoiSide.LEFT);
                panel[0].setImageScopeController(controller[0]);
                compact[0] = panel[0].compactReviewPanel(() -> {}, () -> {});
                panel[0].addPropertyChangeListener("exportRunning", event -> {
                    if (Boolean.TRUE.equals(event.getNewValue())) starts.incrementAndGet(); else finished.countDown();
                });
                assertManualReady(panel[0], compact[0], true);
                source.armed = true;
                panel[0].setDisplayGeometryBlocked(true);
                panel[0].exportSelected(panel[0]);
                panel[0].startExport(panel[0], output, rois.snapshot(), selection);
                assertFalse(panel[0].exportRunning());
                assertManualReady(panel[0], compact[0], false);
                panel[0].setDisplayGeometryBlocked(false);
                panel[0].startExport(panel[0], output, rois.snapshot(), selection);
                assertTrue(panel[0].exportRunning());
                panel[0].startExport(panel[0], output, rois.snapshot(), selection);
                panel[0].exportSelected(compact[0]);
                assertEquals(1, starts.get(), "Both manual entry points must reject duplicate work");
                assertManualReady(panel[0], compact[0], false);
            });
            assertTrue(source.entered.await(10, TimeUnit.SECONDS), "Export must reach the delayed source verification");
            SwingUtilities.invokeAndWait(() -> {
                panel[0].setDisplayGeometryBlocked(true);
                rois.rename(id, "Edited while exporting");
                panel[0].refreshExportReadiness();
                assertManualReady(panel[0], compact[0], false);
                panel[0].setDisplayGeometryBlocked(false);
                assertManualReady(panel[0], compact[0], false);
                panel[0].setDisplayGeometryBlocked(true);
            });
            source.release.countDown();
            assertTrue(finished.await(20, TimeUnit.SECONDS), "Manual completion must return to the EDT");
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(panel[0].exportRunning());
                panel[0].refreshExportReadiness();
                assertManualReady(panel[0], compact[0], false);
                panel[0].startExport(panel[0], output, rois.snapshot(), selection);
                panel[0].exportSelected(compact[0]);
                assertEquals(1, starts.get());
                panel[0].setDisplayGeometryBlocked(false);
                assertManualReady(panel[0], compact[0], true);
                panel[0].setDisplayGeometryBlocked(true);
                rois.setSelectedForExport(id, false);
                panel[0].setDisplayGeometryBlocked(false);
                assertManualReady(panel[0], compact[0], false);
            });
            assertPublishedCount(fail ? 0 : 1);
        } finally {
            source.release.countDown();
            SwingUtilities.invokeAndWait(() -> { if (controller[0] != null) controller[0].close(); });
        }
    }

    private void atlasCompletion(final boolean fail) throws Exception {
        final var basis = ReviewPluginFixtures.basis();
        final var source = new DelayedSource(basis.sourceSnapshot(), fail);
        final var finished = new CountDownLatch(1);
        final var starts = new AtomicInteger();
        final SourceSpaceExportPanel[] panel = {null};
        final ReviewController[] controller = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                final var session = new AlignmentReviewSession(basis);
                controller[0] = controller(session);
                final var accepted = session.accept(() -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()), true);
                final var exporter = new SourceSpaceExportService(source, new Catalog(),
                        () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()), controller[0]::acceptedAlignment);
                panel[0] = new SourceSpaceExportPanel(controller[0], exporter, "source.tif", () -> {});
                panel[0].setAcceptedAlignment(accepted);
                buttonByText(panel[0], "Add region").doClick();
                panel[0].setDestination(output);
                panel[0].addPropertyChangeListener("exportRunning", event -> {
                    if (Boolean.TRUE.equals(event.getNewValue())) starts.incrementAndGet(); else finished.countDown();
                });
                assertTrue(button(panel[0], "exportSourceCrops").isEnabled());
                source.armed = true;
                panel[0].setDisplayGeometryBlocked(true);
                panel[0].startExport();
                assertFalse(panel[0].exportRunning());
                assertFalse(button(panel[0], "exportSourceCrops").isEnabled());
                panel[0].setDisplayGeometryBlocked(false);
                panel[0].startExport();
                panel[0].startExport();
                assertEquals(1, starts.get());
                assertTrue(panel[0].exportRunning());
            });
            assertTrue(source.entered.await(10, TimeUnit.SECONDS), "Atlas export must reach delayed source verification");
            SwingUtilities.invokeAndWait(() -> {
                panel[0].setDisplayGeometryBlocked(true);
                panel[0].setDestination(output);
                assertFalse(button(panel[0], "exportSourceCrops").isEnabled());
                panel[0].setDisplayGeometryBlocked(false);
                assertFalse(button(panel[0], "exportSourceCrops").isEnabled());
                panel[0].setDisplayGeometryBlocked(true);
            });
            source.release.countDown();
            assertTrue(finished.await(20, TimeUnit.SECONDS), "Atlas completion must return to the EDT");
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(panel[0].exportRunning());
                panel[0].setDestination(output);
                assertFalse(button(panel[0], "exportSourceCrops").isEnabled());
                assertFalse(button(panel[0], "exportChooseFolder").isEnabled());
                panel[0].startExport();
                assertEquals(1, starts.get());
                panel[0].setDisplayGeometryBlocked(false);
                assertTrue(button(panel[0], "exportSourceCrops").isEnabled());
                panel[0].setDisplayGeometryBlocked(true);
                panel[0].acceptanceInvalidated();
                panel[0].setDisplayGeometryBlocked(false);
                assertFalse(button(panel[0], "exportSourceCrops").isEnabled());
            });
            assertPublishedCount(fail ? 0 : 1);
        } finally {
            source.release.countDown();
            SwingUtilities.invokeAndWait(() -> {
                if (panel[0] != null) panel[0].close();
                if (controller[0] != null) controller[0].close();
            });
        }
    }

    private void assertPublishedCount(final int expected) throws Exception {
        try (var entries = Files.list(output)) {
            assertEquals(expected, entries.count(), "Only one successful export may publish; failure must clean its temporary files");
        }
    }

    private static ReviewController controller(final AlignmentReviewSession session) {
        final var basis = session.state().basis();
        return new ReviewController(session, ReviewPluginFixtures.preview(), new Catalog(),
                () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()), Runnable::run, Runnable::run);
    }

    private static void assertManualReady(final ManualRoiEditorPanel panel, final JPanel compact, final boolean expected) {
        assertEquals(expected, panel.exportReady());
        assertEquals(expected, button(panel, "manualRoiExport").isEnabled());
        assertEquals(expected, button(compact, "manualRoiReviewExport").isEnabled());
    }

    private static JButton button(final Container root, final String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && name.equals(button.getName())) return button;
            if (child instanceof Container nested) { final JButton found = button(nested, name); if (found != null) return found; }
        }
        return null;
    }

    private static JButton buttonByText(final Container root, final String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) { final JButton found = buttonByText(nested, text); if (found != null) return found; }
        }
        return null;
    }

    private static final class DelayedSource implements SourcePixelReader {
        private final SourceImageSnapshot original;
        private final boolean fail;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean armed;
        DelayedSource(final SourceImageSnapshot original, final boolean fail) { this.original = original; this.fail = fail; }
        @Override public SourceImageSnapshot snapshot() {
            if (armed) {
                entered.countDown();
                try { if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out awaiting test release"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                if (fail) throw new IllegalStateException("Deliberate source verification failure");
            }
            return original;
        }
        @Override public PixelBlock readPlane(final int channel, final int slice, final int frame, final Bounds bounds) {
            return new ByteBlock(bounds.width(), bounds.height(), new byte[bounds.pixelCount()]);
        }
    }

    private static final class Catalog implements AtlasPlaneSource, AtlasRegionCatalog {
        @Override public org.atlasalign.atlas.AtlasCoronalPlane load(final int level) { return ReviewPluginFixtures.plane(level); }
        @Override public Optional<SelectedAtlasRegion> resolveExactAcronym(final String acronym) {
            return Optional.of(new SelectedAtlasRegion(1, "DG", "Dentate gyrus", Set.of(1)));
        }
        @Override public List<SelectedAtlasRegion> search(final String query, final int maximumResults) {
            return List.of(resolveExactAcronym(query).orElseThrow());
        }
    }
}
