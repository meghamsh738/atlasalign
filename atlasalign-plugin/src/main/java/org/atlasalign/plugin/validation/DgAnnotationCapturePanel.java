package org.atlasalign.plugin.validation;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.dg.AnatomicalSide;
import org.atlasalign.application.dg.DgAnnotationV1;
import org.atlasalign.application.dg.DgInputAnchor;
import org.atlasalign.application.dg.DgVisibilityState;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;

/**
 * Atlas-free, output-blinded DG annotation panel used only by D01 validation.
 */
public final class DgAnnotationCapturePanel extends JPanel {

    private final String providerId;
    private final String sourceSha256;
    private final int sourceWidth;
    private final int sourceHeight;
    private final String sourceMappingId;
    private final PreviewMapping mapping;
    private final Consumer<CapturedDgAnnotation> lockedConsumer;
    private final Instant openedAt = Instant.now();
    private final AnnotationCanvas canvas;
    private final JComboBox<AnatomicalSide> side =
            new JComboBox<>(AnatomicalSide.values());
    private final JComboBox<DgVisibilityState> visibility =
            new JComboBox<>(DgVisibilityState.values());
    private final JComboBox<AnnotationTool> tool =
            new JComboBox<>(AnnotationTool.values());
    private final JButton undo = new JButton("Undo");
    private final JButton redo = new JButton("Redo");
    private final JButton reset = new JButton("Reset points");
    private final JButton lock = new JButton("Lock source-only DG annotation");
    private final JLabel status = new JLabel();
    private final ArrayList<AnnotationState> history = new ArrayList<>();
    private int historyIndex;
    private int revisionCount;
    private boolean updatingControls;

    public DgAnnotationCapturePanel(
            final RegistrationPreview preview,
            final String providerId,
            final String sourceSha256,
            final String sourceMappingId,
            final Consumer<CapturedDgAnnotation> lockedConsumer) {
        super(new BorderLayout(10, 10));
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.sourceSha256 = Objects.requireNonNull(
                sourceSha256, "sourceSha256");
        this.sourceMappingId = Objects.requireNonNull(
                sourceMappingId, "sourceMappingId");
        this.mapping = Objects.requireNonNull(preview, "preview").mapping();
        sourceWidth = mapping.sourceWidth();
        sourceHeight = mapping.sourceHeight();
        this.lockedConsumer = Objects.requireNonNull(
                lockedConsumer, "lockedConsumer");
        canvas = new AnnotationCanvas(
                displayImage(preview), mapping, this::addPreviewPoint);
        history.add(AnnotationState.empty(
                AnatomicalSide.LEFT, DgVisibilityState.VISIBLE_INTACT));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(canvas, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.EAST);
        side.addActionListener(event -> {
            if (!updatingControls) {
                commit(current().withSide(
                        (AnatomicalSide) side.getSelectedItem()));
            }
        });
        visibility.addActionListener(event -> {
            if (!updatingControls) {
                commit(current().withVisibility(
                        (DgVisibilityState) visibility.getSelectedItem()));
            }
        });
        undo.addActionListener(event -> undo());
        redo.addActionListener(event -> redo());
        reset.addActionListener(event -> commit(AnnotationState.empty(
                current().side(), current().visibility())));
        lock.addActionListener(event -> lockAnnotation());
        refresh();
    }

    private JPanel buildControls() {
        final JPanel controls = new JPanel(new BorderLayout(6, 6));
        controls.setPreferredSize(new Dimension(340, 660));
        final JPanel fields = new JPanel(new GridLayout(0, 1, 4, 4));
        fields.add(wrapped("D01 validation only. No atlas plane, DeepSlice result, candidate score, or reference coordinate is displayed."));
        fields.add(new JLabel("Anatomical hemisphere"));
        fields.add(side);
        fields.add(new JLabel("DG visibility / damage"));
        fields.add(visibility);
        fields.add(new JLabel("Point being captured"));
        fields.add(tool);
        fields.add(wrapped("For the granule-cell-layer centreline, click points in anatomical order along the visible blade/crest/blade curve. Named landmarks replace their previous point."));
        fields.add(undo);
        fields.add(redo);
        fields.add(reset);
        fields.add(lock);
        controls.add(fields, BorderLayout.NORTH);
        controls.add(wrappedStatus(), BorderLayout.SOUTH);
        return controls;
    }

    private JLabel wrappedStatus() {
        status.setVerticalAlignment(JLabel.TOP);
        return status;
    }

    private static JLabel wrapped(final String text) {
        return new JLabel("<html><body style='width:290px'>" + text
                + "</body></html>");
    }

    void addPreviewPoint(final Point2D previewPoint) {
        final Point2D source = mapping.previewToSource(previewPoint);
        if (source.x() < 0 || source.x() > sourceWidth - 1
                || source.y() < 0 || source.y() > sourceHeight - 1) {
            return;
        }
        final AnnotationTool selected = Objects.requireNonNull(
                (AnnotationTool) tool.getSelectedItem());
        commit(current().withPoint(selected, source));
    }

    private void commit(final AnnotationState next) {
        while (history.size() > historyIndex + 1) {
            history.remove(history.size() - 1);
        }
        history.add(next);
        historyIndex++;
        revisionCount++;
        refresh();
    }

    private void undo() {
        if (historyIndex > 0) {
            historyIndex--;
            refresh();
        }
    }

    private void redo() {
        if (historyIndex + 1 < history.size()) {
            historyIndex++;
            refresh();
        }
    }

    private void refresh() {
        final AnnotationState state = current();
        updatingControls = true;
        try {
            side.setSelectedItem(state.side());
            visibility.setSelectedItem(state.visibility());
        } finally {
            updatingControls = false;
        }
        undo.setEnabled(historyIndex > 0);
        redo.setEnabled(historyIndex + 1 < history.size());
        canvas.setAnnotation(state);
        status.setText("<html><body style='width:290px'>"
                + "Centreline points: " + state.centreline().size()
                + "<br>Named DG points: " + state.namedDgCount()
                + "/3<br>Non-DG input anchors: " + state.anchors().size()
                + "<br>Revisions before automatic output: " + revisionCount
                + "</body></html>");
    }

    private AnnotationState current() {
        return history.get(historyIndex);
    }

    private void lockAnnotation() {
        final Instant lockedAt = Instant.now();
        final AnnotationState state = current();
        try {
            final DgAnnotationV1 annotation = new DgAnnotationV1(
                    providerId,
                    sourceSha256,
                    sourceWidth,
                    sourceHeight,
                    sourceMappingId,
                    state.side(),
                    state.visibility(),
                    state.centreline(),
                    state.crest(),
                    state.suprapyramidal(),
                    state.infrapyramidal(),
                    state.anchors(),
                    openedAt,
                    lockedAt,
                    Optional.empty());
            setCaptureEnabled(false);
            status.setText("<html><body style='width:290px'>Annotation locked. "
                    + "No automatic or reference plane was displayed.</body></html>");
            lockedConsumer.accept(new CapturedDgAnnotation(
                    annotation,
                    Duration.between(openedAt, lockedAt).toNanos()
                            / 1_000_000_000.0,
                    revisionCount));
        } catch (IllegalArgumentException error) {
            status.setText("<html><body style='width:290px'>Cannot lock: "
                    + error.getMessage() + "</body></html>");
        }
    }

    private void setCaptureEnabled(final boolean enabled) {
        side.setEnabled(enabled);
        visibility.setEnabled(enabled);
        tool.setEnabled(enabled);
        undo.setEnabled(enabled && historyIndex > 0);
        redo.setEnabled(enabled && historyIndex + 1 < history.size());
        reset.setEnabled(enabled);
        lock.setEnabled(enabled);
        canvas.setEnabled(enabled);
    }

    AnnotationState annotationState() {
        return current();
    }

    JButton undoControl() {
        return undo;
    }

    JButton redoControl() {
        return redo;
    }

    JButton lockControl() {
        return lock;
    }

    JComboBox<AnnotationTool> toolControl() {
        return tool;
    }

    private static BufferedImage displayImage(
            final RegistrationPreview preview) {
        final float[] pixels = preview.pixels();
        final float[] sorted = pixels.clone();
        for (final float pixel : sorted) {
            if (!Float.isFinite(pixel)) {
                throw new IllegalArgumentException(
                        "DG preview requires finite pixels");
            }
        }
        Arrays.sort(sorted);
        final int last = sorted.length - 1;
        float lower = sorted[(int) Math.floor(last * 0.005)];
        float upper = sorted[(int) Math.ceil(last * 0.995)];
        if (!(upper > lower)) {
            lower = sorted[0];
            upper = sorted[last];
        }
        final BufferedImage image = new BufferedImage(
                preview.mapping().previewWidth(),
                preview.mapping().previewHeight(),
                BufferedImage.TYPE_BYTE_GRAY);
        final double span = upper > lower ? upper - lower : 1;
        for (int index = 0; index < pixels.length; index++) {
            final int value = (int) Math.round(Math.max(0, Math.min(
                    255, (pixels[index] - lower) * 255 / span)));
            image.getRaster().setSample(
                    index % image.getWidth(), index / image.getWidth(), 0, value);
        }
        return image;
    }

    enum AnnotationTool {
        GRANULE_CELL_LAYER_CENTRELINE,
        DG_CREST,
        SUPRAPYRAMIDAL_BLADE_ENDPOINT,
        INFRAPYRAMIDAL_BLADE_ENDPOINT,
        DORSAL_CORPUS_CALLOSUM_MIDLINE,
        LEFT_LATERAL_VENTRICLE_APEX,
        RIGHT_LATERAL_VENTRICLE_APEX,
        VENTRAL_MIDLINE_OR_THIRD_VENTRICLE
    }

    record AnnotationState(
            AnatomicalSide side,
            DgVisibilityState visibility,
            List<Point2D> centreline,
            Optional<Point2D> crest,
            Optional<Point2D> suprapyramidal,
            Optional<Point2D> infrapyramidal,
            Map<DgInputAnchor, Point2D> anchors) {

        AnnotationState {
            side = Objects.requireNonNull(side, "side");
            visibility = Objects.requireNonNull(visibility, "visibility");
            centreline = List.copyOf(centreline);
            crest = Objects.requireNonNull(crest, "crest");
            suprapyramidal = Objects.requireNonNull(
                    suprapyramidal, "suprapyramidal");
            infrapyramidal = Objects.requireNonNull(
                    infrapyramidal, "infrapyramidal");
            anchors = Map.copyOf(anchors);
        }

        static AnnotationState empty(
                final AnatomicalSide side,
                final DgVisibilityState visibility) {
            return new AnnotationState(
                    side, visibility, List.of(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Map.of());
        }

        AnnotationState withSide(final AnatomicalSide value) {
            return new AnnotationState(
                    value, visibility, centreline, crest, suprapyramidal,
                    infrapyramidal, anchors);
        }

        AnnotationState withVisibility(final DgVisibilityState value) {
            if (value == DgVisibilityState.UNCERTAIN
                    || value == DgVisibilityState.NOT_VISIBLE) {
                return empty(side, value);
            }
            return new AnnotationState(
                    side, value, centreline, crest, suprapyramidal,
                    infrapyramidal, anchors);
        }

        AnnotationState withPoint(
                final AnnotationTool tool,
                final Point2D point) {
            if (visibility == DgVisibilityState.UNCERTAIN
                    || visibility == DgVisibilityState.NOT_VISIBLE) {
                return this;
            }
            if (tool == AnnotationTool.GRANULE_CELL_LAYER_CENTRELINE) {
                final ArrayList<Point2D> points = new ArrayList<>(centreline);
                points.add(point);
                return new AnnotationState(
                        side, visibility, points, crest, suprapyramidal,
                        infrapyramidal, anchors);
            }
            final EnumMap<DgInputAnchor, Point2D> updated =
                    new EnumMap<>(DgInputAnchor.class);
            updated.putAll(anchors);
            return switch (tool) {
                case DG_CREST -> new AnnotationState(
                        side, visibility, centreline, Optional.of(point),
                        suprapyramidal, infrapyramidal, anchors);
                case SUPRAPYRAMIDAL_BLADE_ENDPOINT -> new AnnotationState(
                        side, visibility, centreline, crest,
                        Optional.of(point), infrapyramidal, anchors);
                case INFRAPYRAMIDAL_BLADE_ENDPOINT -> new AnnotationState(
                        side, visibility, centreline, crest, suprapyramidal,
                        Optional.of(point), anchors);
                case DORSAL_CORPUS_CALLOSUM_MIDLINE -> withAnchor(
                        updated,
                        DgInputAnchor.DORSAL_CORPUS_CALLOSUM_MIDLINE,
                        point);
                case LEFT_LATERAL_VENTRICLE_APEX -> withAnchor(
                        updated, DgInputAnchor.LEFT_LATERAL_VENTRICLE_APEX,
                        point);
                case RIGHT_LATERAL_VENTRICLE_APEX -> withAnchor(
                        updated, DgInputAnchor.RIGHT_LATERAL_VENTRICLE_APEX,
                        point);
                case VENTRAL_MIDLINE_OR_THIRD_VENTRICLE -> withAnchor(
                        updated,
                        DgInputAnchor.VENTRAL_MIDLINE_OR_THIRD_VENTRICLE,
                        point);
                case GRANULE_CELL_LAYER_CENTRELINE -> throw new AssertionError();
            };
        }

        private AnnotationState withAnchor(
                final EnumMap<DgInputAnchor, Point2D> updated,
                final DgInputAnchor name,
                final Point2D point) {
            updated.put(name, point);
            return new AnnotationState(
                    side, visibility, centreline, crest, suprapyramidal,
                    infrapyramidal, updated);
        }

        int namedDgCount() {
            return (crest.isPresent() ? 1 : 0)
                    + (suprapyramidal.isPresent() ? 1 : 0)
                    + (infrapyramidal.isPresent() ? 1 : 0);
        }
    }

    public record CapturedDgAnnotation(
            DgAnnotationV1 annotation,
            double entrySeconds,
            int preResultRevisionCount) {
    }

    private static final class AnnotationCanvas extends JComponent {

        private final BufferedImage image;
        private final PreviewMapping mapping;
        private final Consumer<Point2D> pointConsumer;
        private AnnotationState annotation = AnnotationState.empty(
                AnatomicalSide.LEFT, DgVisibilityState.VISIBLE_INTACT);
        private double scale = 1;
        private double offsetX;
        private double offsetY;

        private AnnotationCanvas(
                final BufferedImage image,
                final PreviewMapping mapping,
                final Consumer<Point2D> pointConsumer) {
            this.image = image;
            this.mapping = mapping;
            this.pointConsumer = pointConsumer;
            setPreferredSize(new Dimension(850, 660));
            setOpaque(true);
            setBackground(Color.BLACK);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(final MouseEvent event) {
                    if (!isEnabled()) {
                        return;
                    }
                    final Point2D preview = screenToPreview(
                            new Point2D(event.getX(), event.getY()));
                    if (preview.x() >= 0 && preview.x() <= image.getWidth() - 1
                            && preview.y() >= 0
                            && preview.y() <= image.getHeight() - 1) {
                        pointConsumer.accept(preview);
                    }
                }
            });
        }

        private void setAnnotation(final AnnotationState value) {
            annotation = value;
            repaint();
        }

        private Point2D screenToPreview(final Point2D screen) {
            return new Point2D(
                    (screen.x() - offsetX) / scale,
                    (screen.y() - offsetY) / scale);
        }

        private Point2D sourceToScreen(final Point2D source) {
            final Point2D preview = mapping.sourceToPreview(source);
            return new Point2D(
                    offsetX + preview.x() * scale,
                    offsetY + preview.y() * scale);
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            super.paintComponent(graphics);
            scale = Math.min(
                    getWidth() / (double) image.getWidth(),
                    getHeight() / (double) image.getHeight());
            offsetX = (getWidth() - image.getWidth() * scale) / 2;
            offsetY = (getHeight() - image.getHeight() * scale) / 2;
            final Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                canvas.drawImage(
                        image,
                        (int) Math.round(offsetX),
                        (int) Math.round(offsetY),
                        (int) Math.round(image.getWidth() * scale),
                        (int) Math.round(image.getHeight() * scale),
                        null);
                canvas.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setStroke(new BasicStroke(2));
                canvas.setColor(Color.CYAN);
                Point2D previous = null;
                for (final Point2D source : annotation.centreline()) {
                    final Point2D screen = sourceToScreen(source);
                    if (previous != null) {
                        canvas.drawLine(
                                (int) Math.round(previous.x()),
                                (int) Math.round(previous.y()),
                                (int) Math.round(screen.x()),
                                (int) Math.round(screen.y()));
                    }
                    drawMarker(canvas, screen, Color.CYAN);
                    previous = screen;
                }
                annotation.crest().ifPresent(point -> drawMarker(
                        canvas, sourceToScreen(point), Color.YELLOW));
                annotation.suprapyramidal().ifPresent(point -> drawMarker(
                        canvas, sourceToScreen(point), Color.GREEN));
                annotation.infrapyramidal().ifPresent(point -> drawMarker(
                        canvas, sourceToScreen(point), Color.MAGENTA));
                annotation.anchors().values().forEach(point -> drawMarker(
                        canvas, sourceToScreen(point), Color.ORANGE));
            } finally {
                canvas.dispose();
            }
        }

        private static void drawMarker(
                final Graphics2D canvas,
                final Point2D point,
                final Color color) {
            canvas.setColor(color);
            canvas.drawOval(
                    (int) Math.round(point.x()) - 4,
                    (int) Math.round(point.y()) - 4,
                    8,
                    8);
        }
    }
}
