package org.atlasalign.plugin.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.Queue;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.manual.ContourCaptureStatus;
import org.atlasalign.application.manual.ContourCompleteness;
import org.atlasalign.application.manual.ContourSegment;
import org.atlasalign.application.manual.ContourTopology;
import org.atlasalign.application.manual.ContourVertex;
import org.atlasalign.application.manual.GuidedManualWorkflowSession;
import org.atlasalign.application.manual.ManualContour;
import org.atlasalign.application.manual.ManualContourKind;
import org.atlasalign.application.manual.ManualWorkflowEdit;
import org.atlasalign.application.manual.ManualAlignmentStage;
import org.atlasalign.application.manual.PreviewCandidateResult;
import org.atlasalign.application.manual.PreviewOnlyStatus;
import org.atlasalign.application.manual.SourcePixelPoint;
import org.atlasalign.application.manual.SectionGeometry;
import org.atlasalign.application.manual.SectionObservation;
import org.atlasalign.application.manual.VerifiedAtlasGuideIdentity;
import org.atlasalign.application.manual.AnatomicalSide;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.plugin.review.ReviewPreview;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GuidedManualWizardPanelTest {

    private GuidedManualPluginFixtures.Fixture fixture;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void closeController() {
        if (fixture != null) {
            fixture.controller().close();
        }
    }

    @Test
    void firstTissueClickCreatesOneBoundedSourcePixelDraft() throws Exception {
        final GuidedManualCanvas canvas = canvas();
        final AtomicReference<ManualContour> captured = new AtomicReference<>();
        canvas.setContours(Map.of(), Optional.of("tissue-1"));
        canvas.setDrawingEnabled(true);
        canvas.setListener(new GuidedManualCanvas.Listener() {
            @Override
            public void addVertex(final String contourId, final Point2D source) {
                captured.set(new ManualContour(
                        contourId, ManualContourKind.TISSUE_OUTLINE,
                        ContourTopology.CLOSED, ContourCaptureStatus.DRAFT,
                        AnatomicalSide.BILATERAL, ContourCompleteness.COMPLETE,
                        Optional.empty(), sourceIdentity(),
                        List.of(new ContourVertex(
                                "v1", new SourcePixelPoint(
                                        source.x(), source.y()))), Set.of()));
            }
        });
        onEdt(() -> {
            canvas.setSize(900, 620);
            press(canvas, mouse(canvas, MouseEvent.MOUSE_PRESSED,
                    220, 320, MouseEvent.BUTTON1,
                    InputEvent.BUTTON1_DOWN_MASK));
            release(canvas, mouse(canvas, MouseEvent.MOUSE_RELEASED,
                    220, 320, MouseEvent.BUTTON1, 0));
        });

        final ManualContour contour = captured.get();
        assertNotNull(contour);
        assertEquals(ContourCaptureStatus.DRAFT, contour.captureStatus());
        assertEquals(1, contour.vertices().size());
        final var point = contour.vertices().get(0).point();
        assertTrue(point.x() >= 0 && point.x() <= 99);
        assertTrue(point.y() >= 0 && point.y() <= 79);
        assertEquals("a".repeat(64), contour.sourceIdentity().pixelSha256());
    }

    @Test
    void gapToolSelectsTheNearestExistingSegmentWithoutMovingVertices()
            throws Exception {
        final GuidedManualCanvas canvas = canvas();
        final ManualContour outline = tissueContour(
                "outline", ManualContourKind.TISSUE_OUTLINE);
        final AtomicReference<ContourSegment> selected =
                new AtomicReference<>();
        canvas.setContours(Map.of(outline.id(), outline),
                Optional.of(outline.id()));
        canvas.setGapTool(true);
        canvas.setListener(new GuidedManualCanvas.Listener() {
            @Override
            public void toggleGap(
                    final String contourId,
                    final ContourSegment segment) {
                assertEquals(outline.id(), contourId);
                selected.set(segment);
            }
        });

        onEdt(() -> {
            canvas.setSize(900, 620);
            press(canvas, mouse(canvas, MouseEvent.MOUSE_PRESSED,
                    220, 188, MouseEvent.BUTTON1,
                    InputEvent.BUTTON1_DOWN_MASK));
        });

        assertEquals(new ContourSegment("a", "b"), selected.get());
        assertEquals(outline.vertices(), tissueContour(
                "outline", ManualContourKind.TISSUE_OUTLINE).vertices());
    }

    @Test
    void wizardNewContourFirstClickCreatesOneRevisionWithoutSelectorRecursion()
            throws Exception {
        final GuidedManualWizardPanel panel = panel();
        final GuidedManualCanvas canvas = panel.manualCanvas();
        panel.workflowSession().apply(
                new ManualWorkflowEdit.SetSectionObservation(
                        fullSectionObservation()));
        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            findButton(panel, "3 Optional outline").doClick();
            findButton(panel, "New contour").doClick();
            canvas.setSize(900, 620);
            press(canvas, mouse(canvas, MouseEvent.MOUSE_PRESSED,
                    220, 320, MouseEvent.BUTTON1,
                    InputEvent.BUTTON1_DOWN_MASK));
            release(canvas, mouse(canvas, MouseEvent.MOUSE_RELEASED,
                    220, 320, MouseEvent.BUTTON1, 0));
        });

        assertEquals(1, panel.workflowSession().content().contours().size());
        assertEquals(3, panel.workflowSession().visibleHistory().size());
        assertEquals(ContourCaptureStatus.DRAFT,
                panel.workflowSession().content().contours().values()
                        .iterator().next().captureStatus());
    }

    @Test
    void contrastSuggestionCreatesOneAuditedDraftThenRequiresFinish()
            throws Exception {
        final GuidedManualWizardPanel panel = panel();
        final GuidedManualWorkflowSession session = panel.workflowSession();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                fullSectionObservation()));
        final long before = session.currentRevision().id();
        final String sourceHash = fixture.controller().state().basis()
                .sourceSnapshot().pixelSha256();

        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            findButton(panel, "3 Optional outline").doClick();
            findButton(panel,
                    "Suggest outline from image contrast").doClick();
        });

        assertEquals(before + 1, session.currentRevision().id());
        assertEquals(1, session.content().contours().size());
        final ManualContour proposal = session.content().contours().values()
                .iterator().next();
        assertEquals(ContourCaptureStatus.DRAFT,
                proposal.captureStatus());
        assertTrue(proposal.automaticProposalProvenance().isPresent());
        assertTrue(proposal.vertices().size() <= 48,
                "The default automatic proposal must remain easy to edit");
        assertFalse(GuidedManualCanvas.usesCompactVertexHandles(
                        true, proposal.vertices().size(), 1.0),
                "The simplified automatic outline should not require dense-dot rendering");
        assertFalse(GuidedManualCanvas.usesCompactVertexHandles(
                        false, 192, 1.0),
                "Reviewer-drawn points must remain explicit");
        assertFalse(proposal.automaticProposalProvenance().orElseThrow()
                .reviewerModified());
        assertTrue(session.content().previewResult().isEmpty());
        assertEquals(sourceHash, fixture.controller().state().basis()
                .sourceSnapshot().pixelSha256());
        assertTrue(allComponents(panel).stream()
                .filter(JLabel.class::isInstance)
                .map(JLabel.class::cast)
                .map(JLabel::getText)
                .filter(java.util.Objects::nonNull)
                .anyMatch(text -> text.contains(
                        "draft — inspect and click Finish contour")));

        assertFalse(GuidedManualCanvas.usesCompactVertexHandles(
                        true, 192, 2.0),
                "Zoomed editing must restore full vertex handles");

        onEdt(() -> findButton(panel, "Finish contour").doClick());

        final ManualContour finished = session.content().contours()
                .get(proposal.id());
        assertEquals(ContourCaptureStatus.COMPLETE,
                finished.captureStatus());
        assertFalse(finished.automaticProposalProvenance().orElseThrow()
                .reviewerModified(),
                "Explicitly finishing an unchanged proposal is not a geometry edit");
    }

    @Test
    void contrastSuggestionRejectsSameSizedPreviewOutsideReviewBasis()
            throws Exception {
        final float[] mismatchedPixels = GuidedManualPluginFixtures.preview()
                .pixels();
        mismatchedPixels[0] = 1.0f;
        fixture = GuidedManualPluginFixtures.fixture(
                new ReviewPreview(100, 80, mismatchedPixels));
        final AtomicReference<GuidedManualWizardPanel> result =
                new AtomicReference<>();
        onEdt(() -> result.set(new GuidedManualWizardPanel(
                fixture.controller(), fixture.model())));
        final GuidedManualWizardPanel panel = result.get();
        final GuidedManualWorkflowSession session = panel.workflowSession();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                fullSectionObservation()));
        final long before = session.currentRevision().id();

        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            findButton(panel, "3 Optional outline").doClick();
            findButton(panel,
                    "Suggest outline from image contrast").doClick();
        });

        assertEquals(before, session.currentRevision().id());
        assertTrue(session.content().contours().isEmpty());
        assertTrue(allComponents(panel).stream()
                .filter(JLabel.class::isInstance)
                .map(JLabel.class::cast)
                .map(JLabel::getText)
                .filter(java.util.Objects::nonNull)
                .anyMatch(text -> text.contains(
                        "do not match the immutable review-basis")));
    }

    @Test
    void contrastSuggestionNeverSilentlyReplacesExistingOutline()
            throws Exception {
        final GuidedManualWizardPanel panel = panel();
        final GuidedManualWorkflowSession session = panel.workflowSession();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                fullSectionObservation()));
        session.apply(new ManualWorkflowEdit.UpsertContour(tissueContour(
                "outline", ManualContourKind.TISSUE_OUTLINE)));
        final long before = session.currentRevision().id();

        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            findButton(panel, "3 Optional outline").doClick();
            findButton(panel,
                    "Suggest outline from image contrast").doClick();
        });

        assertEquals(before, session.currentRevision().id());
        assertEquals(Set.of("outline"), session.content().contours().keySet());
    }

    @Test
    void completedVertexDragCreatesExactlyOneWorkflowRevision()
            throws Exception {
        final GuidedManualWorkflowSession session =
                new GuidedManualWorkflowSession();
        final ManualContour original = draftAt(49.5, 39.5);
        session.apply(new ManualWorkflowEdit.UpsertContour(original));
        final GuidedManualCanvas canvas = canvas();
        canvas.setContours(Map.of(original.id(), original),
                Optional.of(original.id()));
        canvas.setDrawingEnabled(true);
        canvas.setListener(new GuidedManualCanvas.Listener() {
            @Override
            public void moveVertex(
                    final String contourId,
                    final String vertexId,
                    final Point2D source) {
                final ManualContour changed = new ManualContour(
                        original.id(), original.kind(), original.topology(),
                        original.captureStatus(), original.anatomicalSide(),
                        original.completeness(), original.atlasGuide(),
                        original.sourceIdentity(),
                        List.of(new ContourVertex(vertexId,
                                new SourcePixelPoint(source.x(), source.y()))),
                        Set.of());
                session.apply(new ManualWorkflowEdit.UpsertContour(changed));
            }
        });
        onEdt(() -> {
            canvas.setSize(900, 620);
        });
        final long before = session.currentRevision().id();

        onEdt(() -> {
            press(canvas, mouse(canvas, MouseEvent.MOUSE_PRESSED,
                    220, 320, MouseEvent.BUTTON1,
                    InputEvent.BUTTON1_DOWN_MASK));
            drag(canvas, mouse(canvas, MouseEvent.MOUSE_DRAGGED,
                    245, 340, MouseEvent.NOBUTTON,
                    InputEvent.BUTTON1_DOWN_MASK));
            assertEquals(before,
                    session.currentRevision().id());
            release(canvas, mouse(canvas, MouseEvent.MOUSE_RELEASED,
                    245, 340, MouseEvent.BUTTON1, 0));
        });

        assertEquals(before + 1,
                session.currentRevision().id());
        assertEquals(3, session.visibleHistory().size(),
                "Initial state plus add and move should be three revisions");
    }

    @Test
    void zoomAndPanRemainDisplayOnly() throws Exception {
        final GuidedManualWizardPanel panel = panel();
        final GuidedManualCanvas canvas = panel.manualCanvas();
        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            canvas.setSize(900, 620);
        });
        final long before = panel.workflowSession().currentRevision().id();

        onEdt(() -> {
            wheel(canvas, new MouseWheelEvent(
                    canvas, MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, 220, 320, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -1));
            canvas.setPanTool(true);
            press(canvas, mouse(canvas, MouseEvent.MOUSE_PRESSED,
                    220, 320, MouseEvent.BUTTON1,
                    InputEvent.BUTTON1_DOWN_MASK));
            drag(canvas, mouse(canvas, MouseEvent.MOUSE_DRAGGED,
                    250, 350, MouseEvent.NOBUTTON,
                    InputEvent.BUTTON1_DOWN_MASK));
            release(canvas, mouse(canvas, MouseEvent.MOUSE_RELEASED,
                    250, 350, MouseEvent.BUTTON1, 0));
            canvas.fitBoth();
        });

        assertEquals(before,
                panel.workflowSession().currentRevision().id());
        assertTrue(panel.workflowSession().content().contours().isEmpty());
    }

    @Test
    void layoutsUseVerticalScrollingWithOnlyThreeDirectStagesReachable()
            throws Exception {
        for (final Dimension size : List.of(
                new Dimension(1280, 800), new Dimension(1024, 768))) {
            final GuidedManualWizardPanel panel = panel();
            onEdt(() -> {
                panel.setSize(size);
                layoutTree(panel);
            });

            assertEquals(size, panel.getSize());
            for (final String stage : List.of(
                    "1 Section", "2 Plane", "3 Optional outline")) {
                onEdt(() -> {
                    findButton(panel, stage).doClick();
                    layoutTree(panel);
                });
                assertVerticalScrollContentsStayWithinViewport(panel);
            }
            for (final String stage : List.of(
                    "1 Section", "2 Plane", "3 Optional outline")) {
                final JButton button = findButton(panel, stage);
                assertTrue(button.getX() >= 0);
                assertTrue(button.getX() + button.getWidth()
                        <= panel.getWidth());
            }
            final List<String> buttonLabels = allComponents(panel).stream()
                    .filter(AbstractButton.class::isInstance)
                    .map(AbstractButton.class::cast)
                    .map(AbstractButton::getText)
                    .filter(java.util.Objects::nonNull)
                    .map(text -> text.toLowerCase(Locale.ROOT)).toList();
            assertFalse(buttonLabels.stream().anyMatch(text ->
                    text.contains("search near this visual")));
            assertFalse(buttonLabels.stream().anyMatch(text ->
                    text.contains("use this candidate")));
            assertTrue(buttonLabels.stream().anyMatch(text ->
                    text.contains("keep size/position")));
            assertTrue(buttonLabels.stream().anyMatch(text ->
                    text.contains("selected plane + reviewed outline")));
            assertFalse(buttonLabels.stream().anyMatch(text ->
                    text.contains("adjust anchor")));
            assertFalse(allComponents(panel).stream()
                    .filter(javax.swing.JLabel.class::isInstance)
                    .map(javax.swing.JLabel.class::cast)
                    .map(javax.swing.JLabel::getText)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(text -> text.contains("AP start")
                            || text.contains("tilt minimum")));
            assertEquals(2, allComponents(panel).stream()
                    .filter(JSlider.class::isInstance)
                    .map(JSlider.class::cast)
                    .filter(slider -> slider.getMinimum() == -45
                            && slider.getMaximum() == 45)
                    .count(),
                    "The visual plane must expose both signed tilt sliders");
            fixture.controller().close();
            fixture = null;
        }
    }

    @Test
    void visualTiltSlidersRemainPreviewOnly() throws Exception {
        final GuidedManualWizardPanel panel = panel();
        final long guidedRevision = panel.workflowSession()
                .currentRevision().id();
        final long reviewRevision = fixture.controller().state()
                .contentRevision();
        final List<JSlider> tilts = allComponents(panel).stream()
                .filter(JSlider.class::isInstance)
                .map(JSlider.class::cast)
                .filter(slider -> slider.getMinimum() == -45
                        && slider.getMaximum() == 45)
                .toList();

        onEdt(() -> {
            tilts.get(0).setValue(7);
            tilts.get(1).setValue(-4);
        });

        assertEquals(7, panel.neutralAtlasTilt().sagittalDegrees());
        assertEquals(-4, panel.neutralAtlasTilt().horizontalDegrees());
        assertEquals(guidedRevision,
                panel.workflowSession().currentRevision().id());
        assertEquals(reviewRevision,
                fixture.controller().state().contentRevision());
    }

    @Test
    void chosenGuideAppearsOnTissueBeforeOutlineWarp() throws Exception {
        final GuidedManualWizardPanel panel = panel();
        panel.workflowSession().apply(
                new ManualWorkflowEdit.SetSectionObservation(
                        fullSectionObservation()));
        final long guidedRevision = panel.workflowSession()
                .currentRevision().id();
        final long reviewRevision = fixture.controller().state()
                .contentRevision();

        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            findButton(panel, "2 Plane").doClick();
            findButton(panel, "Use selected verified guide").doClick();
        });

        assertTrue(panel.manualCanvas().neutralGuidePreviewPointCount() > 0,
                "The selected atlas guide must be visible over tissue before the outline is finished");
        assertEquals(guidedRevision + 1,
                panel.workflowSession().currentRevision().id(),
                "Only the explicit guide choice may enter guided history");
        assertEquals(reviewRevision,
                fixture.controller().state().contentRevision(),
                "The display-only guide must not edit the review result");
    }

    @Test
    void outlineIsOptionalAndLegacySearchStagesAreNotExposed()
            throws Exception {
        final GuidedManualWizardPanel panel = panel();
        final GuidedManualWorkflowSession session = panel.workflowSession();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                fullSectionObservation()));
        session.apply(new ManualWorkflowEdit.ChooseAnatomicalGuide(
                dgGuideIdentity()));
        onEdt(() -> {
            panel.setSize(1024, 768);
            findButton(panel, "3 Optional outline").doClick();
        });
        assertEquals(ManualAlignmentStage.DRAW_TISSUE_OUTLINE,
                session.displayedStage());
        assertFalse(allComponents(panel).stream()
                .filter(JButton.class::isInstance)
                .map(JButton.class::cast)
                .map(JButton::getText)
                .anyMatch(text -> text != null && (text.contains("Search")
                        || text.contains("candidate"))));
        assertTrue(findButton(panel,
                "Use selected plane; keep size/position").isEnabled());
    }

    @Test
    void completedFullOutlineShowsWarpedGuideInDirectOutlineStage()
            throws Exception {
        final GuidedManualWizardPanel panel = panel();
        final GuidedManualWorkflowSession session = panel.workflowSession();
        final long reviewRevisionBeforeOutline = fixture.controller().state()
                .contentRevision();
        final var reviewLevelBeforeOutline = fixture.controller().state()
                .content().coronalLevel();
        final var reviewTiltBeforeOutline = fixture.controller().state()
                .content().atlasPlaneTilt();
        final VerifiedAtlasGuideIdentity guideIdentity = dgGuideIdentity();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                fullSectionObservation()));
        session.apply(new ManualWorkflowEdit.ChooseAnatomicalGuide(
                guideIdentity));
        session.apply(new ManualWorkflowEdit.UpsertContour(tissueContour(
                "outline", ManualContourKind.TISSUE_OUTLINE)));

        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            findButton(panel, "3 Optional outline").doClick();
            layoutTree(panel);
        });
        awaitText(panel, "Exact reviewed-outline map ready");

        assertTrue(allComponents(panel).stream()
                .filter(JLabel.class::isInstance)
                .map(JLabel.class::cast)
                .map(JLabel::getText)
                .filter(java.util.Objects::nonNull)
                .anyMatch(text -> text.contains(
                        "Exact reviewed-outline map ready")),
                "The direct outline stage must expose the exact warped atlas guide");
        assertTrue(allComponents(panel).stream()
                .filter(JLabel.class::isInstance)
                .map(JLabel.class::cast)
                .map(JLabel::getText)
                .filter(java.util.Objects::nonNull)
                .anyMatch(text -> text.contains(
                        "Current review is still level")),
                "The outline stage must distinguish selected-plane preview from the unchanged review state");
        assertTrue(allComponents(panel).stream()
                .filter(JLabel.class::isInstance)
                .map(JLabel.class::cast)
                .map(JLabel::getText)
                .filter(java.util.Objects::nonNull)
                .anyMatch(text -> text.contains(
                        "global placement will change")),
                "The outline stage must quantify scale, centre, and rotation changes before application");
        final JLabel placementReport = allComponents(panel).stream()
                .filter(JLabel.class::isInstance)
                .map(JLabel.class::cast)
                .filter(label -> label.getText() != null
                        && label.getText().contains(
                                "global placement will change"))
                .findFirst().orElseThrow();
        assertTrue(placementReport.getMaximumSize().height
                        >= placementReport.getPreferredSize().height,
                "A dynamically expanded placement report must remain fully reachable through vertical scrolling rather than being clipped at its initial height");
        assertTrue(placementReport.getText().contains("horizontal size")
                        && placementReport.getText().contains("vertical size")
                        && placementReport.getText().contains("centre")
                        && placementReport.getText().contains("rotation"),
                "The visible pre-application report must name every in-plane placement component that can change");
        assertTrue(session.content().contours().values().stream()
                .noneMatch(contour -> contour.kind()
                        == ManualContourKind.ANATOMICAL_STRUCTURE));
        final boolean adjustAnchorPresent = allComponents(panel).stream()
                .filter(JToggleButton.class::isInstance)
                .map(JToggleButton.class::cast)
                .filter(button -> "Adjust anchor".equals(button.getText()))
                .findFirst().isPresent();
        assertFalse(adjustAnchorPresent,
                "The normal exact-boundary path must not expose legacy anchors");
        assertEquals(reviewRevisionBeforeOutline,
                fixture.controller().state().contentRevision(),
                "Drawing and finishing an outline must not edit the authoritative review result");
        assertEquals(reviewLevelBeforeOutline,
                fixture.controller().state().content().coronalLevel(),
                "Drawing an outline must not change the rostrocaudal level");
        assertEquals(reviewTiltBeforeOutline,
                fixture.controller().state().content().atlasPlaneTilt(),
                "Drawing an outline must not change either cutting-plane tilt");
    }

    @Test
    void checkedOutlineAppliesDirectlyWithoutCandidateRanking()
            throws Exception {
        final GuidedManualWizardPanel panel = panel();
        final GuidedManualWorkflowSession session = panel.workflowSession();
        final VerifiedAtlasGuideIdentity guideIdentity = dgGuideIdentity();
        final AtomicBoolean candidateApplied = new AtomicBoolean();
        panel.setWorkflowAppliedHandler(() -> candidateApplied.set(true));
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                fullSectionObservation()));
        session.apply(new ManualWorkflowEdit.UpsertContour(tissueContour(
                "outline", ManualContourKind.TISSUE_OUTLINE)));
        session.apply(new ManualWorkflowEdit.ChooseAnatomicalGuide(
                guideIdentity));

        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            findButton(panel, "3 Optional outline").doClick();
            layoutTree(panel);
        });

        final JButton useOutline = findButton(panel,
                "Use selected plane + reviewed outline");
        awaitEnabled(useOutline);
        assertTrue(useOutline.isEnabled());
        onEdt(useOutline::doClick);
        assertTrue(candidateApplied.get(),
                "Applying the checked visual plane must trigger the refinement handoff");
        assertTrue(fixture.controller().state().content().outlineWarp()
                .isPresent());
        assertTrue(fixture.controller().state().content()
                .outlineAnchorsConfirmed());
    }

    @Test
    void exactOutlineCalculationRunsOffTheSwingEventThread()
            throws Exception {
        final AtomicBoolean calculatedOnEventThread =
                new AtomicBoolean(true);
        final Executor background = command -> {
            final Thread worker = new Thread(() -> {
                calculatedOnEventThread.set(
                        SwingUtilities.isEventDispatchThread());
                command.run();
            }, "exact-outline-test-worker");
            worker.setDaemon(true);
            worker.start();
        };
        final GuidedManualWizardPanel panel = panel(background);
        final GuidedManualWorkflowSession session = panel.workflowSession();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                fullSectionObservation()));
        session.apply(new ManualWorkflowEdit.UpsertContour(tissueContour(
                "outline", ManualContourKind.TISSUE_OUTLINE)));
        session.apply(new ManualWorkflowEdit.ChooseAnatomicalGuide(
                dgGuideIdentity()));

        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            findButton(panel, "3 Optional outline").doClick();
        });

        awaitText(panel, "Exact reviewed-outline map ready");
        assertFalse(calculatedOnEventThread.get(),
                "Exact geometry must never be calculated on Swing's EDT");
    }

    @Test
    void staleOutlineResultCannotReplaceNewerWorkflowState()
            throws Exception {
        final Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
        final GuidedManualWizardPanel panel = panel(queued::add);
        final GuidedManualWorkflowSession session = panel.workflowSession();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                fullSectionObservation()));
        session.apply(new ManualWorkflowEdit.UpsertContour(tissueContour(
                "outline-one", ManualContourKind.TISSUE_OUTLINE)));
        session.apply(new ManualWorkflowEdit.ChooseAnatomicalGuide(
                dgGuideIdentity()));

        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            findButton(panel, "3 Optional outline").doClick();
        });
        final Runnable staleCalculation = awaitQueued(queued);

        session.apply(new ManualWorkflowEdit.UpsertContour(tissueContour(
                "outline-two", ManualContourKind.TISSUE_OUTLINE)));
        onEdt(() -> panel.updateModel(fixture.model()));
        awaitText(panel, "Finish exactly one closed");

        final Thread worker = new Thread(staleCalculation,
                "stale-outline-test-worker");
        worker.start();
        worker.join();
        onEdt(() -> { });

        assertFalse(hasText(panel, "Exact reviewed-outline map ready"));
        assertFalse(findButton(panel,
                "Use selected plane + reviewed outline").isEnabled());
        assertTrue(fixture.controller().state().content().outlineWarp()
                .isEmpty());
    }

    @Test
    void reviewedGapFailsClosedWithoutGuessingAcrossIt()
            throws Exception {
        final GuidedManualWizardPanel panel = panel();
        final GuidedManualWorkflowSession session = panel.workflowSession();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                fullSectionObservation()));
        session.apply(new ManualWorkflowEdit.UpsertContour(
                fissuredTissueOutlineWithReviewedGap()));
        session.apply(new ManualWorkflowEdit.ChooseAnatomicalGuide(
                dgGuideIdentity()));

        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            findButton(panel, "3 Optional outline").doClick();
            layoutTree(panel);
        });

        awaitText(panel, "does not guess across excluded gaps");
        assertFalse(findButton(panel,
                "Use selected plane + reviewed outline").isEnabled());
        assertTrue(fixture.controller().state().content().outlineWarp()
                .isEmpty());
    }

    @Test
    void draftOutlineCannotBeAppliedButCanBeSkipped()
            throws Exception {
        final GuidedManualWizardPanel panel = panel();
        final GuidedManualWorkflowSession session = panel.workflowSession();
        final VerifiedAtlasGuideIdentity guideIdentity = dgGuideIdentity();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                fullSectionObservation()));
        session.apply(new ManualWorkflowEdit.ChooseAnatomicalGuide(
                guideIdentity));
        session.apply(new ManualWorkflowEdit.UpsertContour(
                draftAt(49.5, 39.5)));

        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            findButton(panel, "3 Optional outline").doClick();
            layoutTree(panel);
        });

        assertFalse(findButton(panel,
                "Use selected plane + reviewed outline").isEnabled());
        assertTrue(findButton(panel,
                "Use selected plane; keep size/position").isEnabled());
    }

    @Test
    void reflectedHalfCanKeepCurrentPlacementAndOpenManualTools()
            throws Exception {
        fixture = GuidedManualPluginFixtures.fixture(
                org.atlasalign.application.SectionGeometry.IMAGE_LEFT_HALF);
        final AtomicReference<GuidedManualWizardPanel> built =
                new AtomicReference<>();
        onEdt(() -> built.set(new GuidedManualWizardPanel(
                fixture.controller(), fixture.model())));
        final GuidedManualWizardPanel panel = built.get();
        final GuidedManualWorkflowSession session = panel.workflowSession();
        final AtomicBoolean applied = new AtomicBoolean();
        panel.setWorkflowAppliedHandler(() -> applied.set(true));
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                new SectionObservation(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT,
                        ObservedAnatomicalHemisphere.RIGHT, true, true)));
        session.apply(new ManualWorkflowEdit.ChooseAnatomicalGuide(
                dgGuideIdentity()));

        onEdt(() -> {
            panel.setSize(1280, 800);
            layoutTree(panel);
            findButton(panel, "3 Optional outline").doClick();
            layoutTree(panel);
            findButton(panel,
                    "Use selected plane; keep size/position").doClick();
        });

        assertTrue(applied.get());
        assertEquals(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT,
                fixture.controller().state().content().orientation());
        assertEquals(ObservedAnatomicalHemisphere.RIGHT,
                fixture.controller().state().content()
                        .observedHemisphere());
        assertTrue(fixture.controller().state()
                .preOutlineAtlasToPreview().determinant() < 0,
                "The kept placement must be rebased to the explicit reflected orientation");
        assertTrue(fixture.controller().state().content()
                .outlineWarp().isEmpty());
    }

    @Test
    void previewResultIsExplicitlyNonValidatedAndNonPromotable() {
        final var source = ManualWorkflowIdentities.source(
                GuidedManualPluginFixtures.basis());
        final var atlas = ManualWorkflowIdentities.atlas(
                GuidedManualPluginFixtures.basis());
        final PreviewCandidateResult preview = new PreviewCandidateResult(
                "preview-1", source, atlas, List.of(),
                PreviewOnlyStatus.PREVIEW_ONLY_NOT_VALIDATED);

        assertEquals(PreviewOnlyStatus.PREVIEW_ONLY_NOT_VALIDATED,
                preview.status());
        assertFalse(preview.promotable());
        assertTrue(preview.candidates().isEmpty());
        assertEquals(GuidedManualPluginFixtures.basis().sourceSnapshot().metadata(),
                source.metadata());
    }

    private GuidedManualWizardPanel panel() throws Exception {
        return panel(java.util.concurrent.ForkJoinPool.commonPool());
    }

    private GuidedManualWizardPanel panel(final Executor executor)
            throws Exception {
        fixture = GuidedManualPluginFixtures.fixture();
        final AtomicReference<GuidedManualWizardPanel> result =
                new AtomicReference<>();
        onEdt(() -> result.set(new GuidedManualWizardPanel(
                fixture.controller(), fixture.model(), executor)));
        return result.get();
    }

    private static GuidedManualCanvas canvas() {
        return new GuidedManualCanvas(GuidedManualPluginFixtures.preview(),
                new PreviewMapping(100, 80, 100, 80));
    }

    private static org.atlasalign.application.manual.SourceImageIdentity
            sourceIdentity() {
        return ManualWorkflowIdentities.source(
                GuidedManualPluginFixtures.basis());
    }

    private static ManualContour draftAt(final double x, final double y) {
        return new ManualContour(
                "tissue-1", ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.DRAFT,
                AnatomicalSide.BILATERAL, ContourCompleteness.COMPLETE,
                Optional.empty(), sourceIdentity(),
                List.of(new ContourVertex(
                        "v1", new SourcePixelPoint(x, y))), Set.of());
    }

    private static ManualContour tissueContour(
            final String id,
            final ManualContourKind kind) {
        final ContourTopology topology = kind == ManualContourKind.TISSUE_OUTLINE
                ? ContourTopology.CLOSED : ContourTopology.OPEN;
        final List<ContourVertex> vertices = topology == ContourTopology.CLOSED
                ? List.of(
                        new ContourVertex("a", new SourcePixelPoint(9.5, 9.5)),
                        new ContourVertex("b", new SourcePixelPoint(89.5, 9.5)),
                        new ContourVertex("c", new SourcePixelPoint(89.5, 69.5)),
                        new ContourVertex("d", new SourcePixelPoint(9.5, 69.5)))
                : List.of(
                        new ContourVertex("a", new SourcePixelPoint(1, 1)),
                        new ContourVertex("b", new SourcePixelPoint(1, 70)));
        return new ManualContour(
                id, kind, topology, ContourCaptureStatus.COMPLETE,
                AnatomicalSide.BILATERAL, ContourCompleteness.COMPLETE,
                Optional.empty(), sourceIdentity(), vertices, Set.of());
    }

    private static ManualContour fissuredTissueOutlineWithReviewedGap() {
        return new ManualContour(
                "fissured-outline", ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.COMPLETE,
                AnatomicalSide.BILATERAL, ContourCompleteness.COMPLETE,
                Optional.empty(), sourceIdentity(), List.of(
                        new ContourVertex("s1", new SourcePixelPoint(10, 14)),
                        new ContourVertex("fissure-left",
                                new SourcePixelPoint(27, 10)),
                        new ContourVertex("fissure-tip",
                                new SourcePixelPoint(32, 28)),
                        new ContourVertex("fissure-right",
                                new SourcePixelPoint(37, 10)),
                        new ContourVertex("s3", new SourcePixelPoint(70, 12)),
                        new ContourVertex("s4", new SourcePixelPoint(72, 32)),
                        new ContourVertex("s5", new SourcePixelPoint(68, 54)),
                        new ContourVertex("s6", new SourcePixelPoint(40, 50)),
                        new ContourVertex("s7", new SourcePixelPoint(12, 54)),
                        new ContourVertex("s8", new SourcePixelPoint(8, 32))),
                Set.of(
                        new ContourSegment(
                                "fissure-left", "fissure-tip"),
                        new ContourSegment(
                                "fissure-tip", "fissure-right")));
    }

    private static ManualContour anatomicalContour(
            final String id,
            final VerifiedAtlasGuideIdentity guide) {
        return new ManualContour(
                id, ManualContourKind.ANATOMICAL_STRUCTURE,
                ContourTopology.OPEN, ContourCaptureStatus.COMPLETE,
                AnatomicalSide.RIGHT, ContourCompleteness.COMPLETE,
                Optional.of(guide), sourceIdentity(), List.of(
                        new ContourVertex("g1", new SourcePixelPoint(52, 12)),
                        new ContourVertex("g2", new SourcePixelPoint(88, 12)),
                        new ContourVertex("g3", new SourcePixelPoint(88, 68)),
                        new ContourVertex("g4", new SourcePixelPoint(52, 68))),
                Set.of());
    }

    private static ManualContour draftAnatomicalContour(
            final String id,
            final VerifiedAtlasGuideIdentity guide) {
        final ManualContour completed = anatomicalContour(id, guide);
        return new ManualContour(
                completed.id(), completed.kind(), completed.topology(),
                ContourCaptureStatus.DRAFT, completed.anatomicalSide(),
                completed.completeness(), completed.atlasGuide(),
                completed.sourceIdentity(), completed.vertices(),
                completed.excludedGapSegments());
    }

    private static SectionObservation fullSectionObservation() {
        return new SectionObservation(
                SectionGeometry.FULL,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.BOTH, true, true);
    }

    private static VerifiedAtlasGuideIdentity dgGuideIdentity() {
        final var atlas = ManualWorkflowIdentities.atlas(
                GuidedManualPluginFixtures.basis());
        return new VerifiedAtlasGuideIdentity(
                atlas.identitySha256(), "Allen CCF structure graph",
                atlas.atlasVersion(), 11, "DG-sg");
    }

    private static void press(
            final GuidedManualCanvas canvas,
            final MouseEvent event) {
        for (final var listener : canvas.getMouseListeners()) {
            listener.mousePressed(event);
        }
    }

    private static void release(
            final GuidedManualCanvas canvas,
            final MouseEvent event) {
        for (final var listener : canvas.getMouseListeners()) {
            listener.mouseReleased(event);
        }
    }

    private static void drag(
            final GuidedManualCanvas canvas,
            final MouseEvent event) {
        for (final var listener : canvas.getMouseMotionListeners()) {
            listener.mouseDragged(event);
        }
    }

    private static void wheel(
            final GuidedManualCanvas canvas,
            final MouseWheelEvent event) {
        for (final var listener : canvas.getMouseWheelListeners()) {
            listener.mouseWheelMoved(event);
        }
    }

    private static MouseEvent mouse(
            final Component source,
            final int id,
            final int x,
            final int y,
            final int button,
            final int modifiers) {
        return new MouseEvent(source, id, System.currentTimeMillis(),
                modifiers, x, y, 1, false, button);
    }

    private static JButton findButton(
            final Container root,
            final String text) {
        return allComponents(root).stream()
                .filter(JButton.class::isInstance)
                .map(JButton.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "Missing button: " + text));
    }

    private static JCheckBox findCheckBox(
            final Container root,
            final String text) {
        return allComponents(root).stream()
                .filter(JCheckBox.class::isInstance)
                .map(JCheckBox.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "Missing checkbox: " + text));
    }

    private static List<Component> allComponents(final Container root) {
        final List<Component> result = new ArrayList<>();
        for (final Component component : root.getComponents()) {
            result.add(component);
            if (component instanceof Container child) {
                result.addAll(allComponents(child));
            }
        }
        return result;
    }

    private static void assertVerticalScrollContentsStayWithinViewport(
            final Container root) {
        allComponents(root).stream()
                .filter(JScrollPane.class::isInstance)
                .map(JScrollPane.class::cast)
                .filter(Component::isVisible)
                .forEach(scroll -> {
                    assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                            scroll.getHorizontalScrollBarPolicy());
                    assertFalse(scroll.getHorizontalScrollBar().isVisible());
                    final JViewport viewport = scroll.getViewport();
                    final Component view = viewport.getView();
                    if (!(view instanceof Container content)
                            || viewport.getExtentSize().width <= 0) {
                        return;
                    }
                    assertTrue(content.getWidth()
                                    <= viewport.getExtentSize().width,
                            () -> "Scroll content exceeds viewport: "
                                    + content.getClass().getSimpleName()
                                    + " " + content.getWidth() + ">"
                                    + viewport.getExtentSize().width);
                    allComponents(content).stream()
                            .filter(Component::isVisible)
                            .filter(component -> component.getWidth() > 0)
                            .forEach(component -> {
                                final Rectangle bounds = SwingUtilities
                                        .convertRectangle(component.getParent(),
                                                component.getBounds(), content);
                                assertTrue(bounds.x >= 0,
                                        () -> component.getClass().getSimpleName()
                                                + " begins outside scroll content");
                                assertTrue(bounds.x + bounds.width
                                                <= content.getWidth(),
                                        () -> component.getClass().getSimpleName()
                                                + " extends past scroll content: "
                                                + bounds + " width="
                                                + content.getWidth());
                            });
                });
    }

    private static void layoutTree(final Container root) {
        root.doLayout();
        for (final Component component : root.getComponents()) {
            if (component instanceof Container child) {
                layoutTree(child);
            }
        }
    }

    private static void onEdt(final Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeAndWait(action);
        }
    }

    private static void awaitEnabled(final JButton button) throws Exception {
        final long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final AtomicBoolean enabled = new AtomicBoolean();
            onEdt(() -> enabled.set(button.isEnabled()));
            if (enabled.get()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for button: "
                + button.getText());
    }

    private static void awaitText(
            final Container root, final String expected) throws Exception {
        final long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final AtomicBoolean found = new AtomicBoolean();
            onEdt(() -> found.set(allComponents(root).stream()
                    .filter(JLabel.class::isInstance)
                    .map(JLabel.class::cast)
                    .map(JLabel::getText)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(text -> text.contains(expected))));
            if (found.get()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for text: " + expected);
    }

    private static Runnable awaitQueued(final Queue<Runnable> queued)
            throws Exception {
        final long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final Runnable task = queued.poll();
            if (task != null) {
                return task;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for outline calculation");
    }

    private static boolean hasText(
            final Container root, final String expected) {
        return allComponents(root).stream()
                .filter(JLabel.class::isInstance)
                .map(JLabel.class::cast)
                .map(JLabel::getText)
                .filter(java.util.Objects::nonNull)
                .anyMatch(text -> text.contains(expected));
    }
}
