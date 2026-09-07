package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import javax.swing.JPanel;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.application.roi.ReviewerRoiSide;
import org.atlasalign.application.roi.RoiPartOperation;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.junit.jupiter.api.Test;

class ManualRoiCanvasLayerTest {
    @Test
    void compactGuideCopyKeepsExistingCornersInsteadOfUniformlyReplacingThem() {
        final List<Point2D> denseRectangle = List.of(
                new Point2D(10, 10), new Point2D(30, 10),
                new Point2D(50, 10), new Point2D(50, 30),
                new Point2D(50, 50), new Point2D(30, 50),
                new Point2D(10, 50), new Point2D(10, 30));

        final List<Point2D> copied = ManualRoiEditorPanel.resampleLoops(
                List.of(denseRectangle), 4).get(0);

        assertEquals(Set.of(new Point2D(10, 10), new Point2D(50, 10),
                new Point2D(50, 50), new Point2D(10, 50)),
                Set.copyOf(copied));
    }

    @Test
    void changedGuideIsDisclosedWithoutMovingOrApprovingThePolygon() {
        final var basis = ReviewPluginFixtures.basis();
        final var controller = new ReviewController(
                new org.atlasalign.application.AlignmentReviewSession(basis),
                ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                () -> new org.atlasalign.application.ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()), Runnable::run, Runnable::run);
        final var model = new java.util.concurrent.atomic.AtomicReference<ReviewViewModel>();
        controller.attach(new ReviewView() {
            public void render(ReviewViewModel value) { model.set(value); }
            public void showError(String title, String message) { fail(message); }
            public void reviewClosed() { }
        });
        final var session = new ReviewerRoiSession("test", 100, 80);
        final var guide = new org.atlasalign.application.roi.ReviewerRoiGuideLink(
                1, "DG", "Dentate gyrus", true, basis.atlas().identitySha256(),
                model.get().coronalLevel(), model.get().reviewState().contentRevision());
        session.createFromGuide("DG", ReviewerRoiSide.LEFT, guide,
                java.util.List.of(java.util.List.of(new Point2D(10, 10),
                        new Point2D(40, 10), new Point2D(40, 30), new Point2D(10, 30))));
        final var roi = session.snapshot().activeRoi().orElseThrow();
        assertFalse(ManualRoiEditorPanel.reviewStatus(roi, model.get()).contains("Alignment changed"));
        controller.setCoronalLevel(model.get().coronalLevel() + 1);
        assertTrue(ManualRoiEditorPanel.reviewStatus(roi, model.get()).contains("Alignment changed since copy"));
        assertTrue(ManualRoiEditorPanel.reviewStatus(roi, model.get()).contains("not recorded automatically"));
        assertEquals(roi, session.snapshot().activeRoi().orElseThrow());
        assertFalse(controller.isAccepted());
    }

    @Test
    void visibleHaloedHandlesAppearOnlyWhileEditableAndKeepLargeHitTargets() {
        final var session = new ReviewerRoiSession("test", 100, 80);
        session.newPolygon("thin ROI", ReviewerRoiSide.LEFT, RoiPartOperation.ADD);
        for (final var p : java.util.List.of(new Point2D(10, 10), new Point2D(70, 10),
                new Point2D(70, 20), new Point2D(10, 20))) { session.addVertex(p); }
        session.finishActivePart();
        final var before = session.snapshot().activeRoi().orElseThrow();
        final var layer = new ManualRoiCanvasLayer();
        layer.setState(session.snapshot(), new PreviewMapping(100, 80, 100, 80));
        layer.setEnabled(true);
        layer.setListener(new ManualRoiCanvasLayer.Listener() {
            @Override public void moveVertex(String r, String p, String v, Point2D point) {
                session.moveVertex(r, p, v, point);
            }
        });
        final var screen = ScreenMapping.fit(100, 80, 100, 80);
        final var image = new BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB);
        final var g = image.createGraphics();
        layer.paint(g, screen, 0, 100, 80);
        g.dispose();
        assertNotEquals(0, image.getRGB(6, 10) >>> 24,
                "the dark halo must make the editable point visible over tissue");
        final int core = image.getRGB(10, 10);
        assertTrue((core & 0xff) > 220
                        && ((core >>> 8) & 0xff) > 220
                        && ((core >>> 16) & 0xff) < 30,
                "the handle core must remain visibly cyan");
        final var inactiveImage = new BufferedImage(
                100, 80, BufferedImage.TYPE_INT_ARGB);
        layer.setEnabled(false);
        final var inactiveGraphics = inactiveImage.createGraphics();
        layer.paint(inactiveGraphics, screen, 0, 100, 80);
        inactiveGraphics.dispose();
        assertEquals(0, inactiveImage.getRGB(6, 10) >>> 24,
                "handles must disappear outside the actual editable stage");
        assertNotEquals(0, inactiveImage.getRGB(10, 10) >>> 24,
                "the thin ROI outline remains visible outside the editing stage");
        layer.setEnabled(true);
        final var component = new JPanel();
        assertTrue(layer.press(mouse(component, MouseEvent.MOUSE_PRESSED, 3, 10), screen),
                "The original 10-pixel click target remains available outside the tiny dot");
        layer.drag(mouse(component, MouseEvent.MOUSE_DRAGGED, 15, 12), screen);
        assertEquals(before, session.snapshot().activeRoi().orElseThrow(),
                "A drag preview must not mutate source geometry before release");
        layer.release(mouse(component, MouseEvent.MOUSE_RELEASED, 15, 12), screen);
        assertEquals(new Point2D(15, 12), session.snapshot().activeRoi().orElseThrow()
                .parts().get(0).vertices().get(0).sourcePoint());
        session.undo();
        assertEquals(before, session.snapshot().activeRoi().orElseThrow());
        session.redo();
        assertEquals(new Point2D(15, 12), session.snapshot().activeRoi().orElseThrow()
                .parts().get(0).vertices().get(0).sourcePoint());
        final String status = ManualRoiEditorPanel.reviewStatus(before, null);
        assertTrue(status.contains("Finished outline"));
        assertTrue(status.contains("Selected for exact polygon export"));
        assertTrue(status.contains("Anatomical review is not recorded automatically"));
    }

    private static MouseEvent mouse(JPanel panel, int type, int x, int y) {
        return new MouseEvent(panel, type, 0, 0, x, y, 1, false, MouseEvent.BUTTON1);
    }
}
