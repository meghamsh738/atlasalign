package org.atlasalign.plugin.validation;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.guided.AnatomicalSearchPriorV1;
import org.atlasalign.application.guided.AnatomicalTissueClass;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.plugin.review.AtlasPlaneSource;

/** Neutral level-264 atlas browser that captures no automatic output. */
public final class GuidedPriorCapturePanel extends JPanel {

    public static final int NEUTRAL_START_LEVEL = 264;
    private final AtlasPlaneSource atlasSource;
    private final String providerId;
    private final String sourceSha256;
    private final String atlasIdentitySha256;
    private final Consumer<CapturedPrior> lockedPriorConsumer;
    private final Instant openedAt;
    private final NeutralAtlasCanvas canvas = new NeutralAtlasCanvas();
    private final JSlider browseLevel = new JSlider(
            0, AllenCoronalLevel.PLANE_COUNT - 1, NEUTRAL_START_LEVEL);
    private final JSpinner startLevel = new JSpinner(
            new SpinnerNumberModel(232, 0, 527, 1));
    private final JSpinner endLevel = new JSpinner(
            new SpinnerNumberModel(296, 0, 527, 1));
    private final JComboBox<AnatomicalTissueClass> tissueClass =
            new JComboBox<>(AnatomicalTissueClass.values());
    private final JLabel status = new JLabel("Loading verified neutral atlas level 264…");
    private final JButton lock = new JButton("Lock prior before auto-align");
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private int revisionCount;

    public GuidedPriorCapturePanel(
            final AtlasPlaneSource atlasSource,
            final String providerId,
            final String sourceSha256,
            final String atlasIdentitySha256,
            final Consumer<CapturedPrior> lockedPriorConsumer) {
        super(new BorderLayout(10, 10));
        this.atlasSource = Objects.requireNonNull(atlasSource, "atlasSource");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.sourceSha256 = Objects.requireNonNull(sourceSha256, "sourceSha256");
        this.atlasIdentitySha256 = Objects.requireNonNull(
                atlasIdentitySha256, "atlasIdentitySha256");
        this.lockedPriorConsumer = Objects.requireNonNull(
                lockedPriorConsumer, "lockedPriorConsumer");
        openedAt = Instant.now();
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(canvas, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.EAST);
        browseLevel.addChangeListener(event -> {
            if (!browseLevel.getValueIsAdjusting()) {
                loadLevel(browseLevel.getValue());
            }
        });
        startLevel.addChangeListener(event -> revisionCount++);
        endLevel.addChangeListener(event -> revisionCount++);
        tissueClass.addActionListener(event -> revisionCount++);
        lock.addActionListener(event -> lockPrior());
        loadLevel(NEUTRAL_START_LEVEL);
    }

    private JPanel buildControls() {
        final JPanel controls = new JPanel(new BorderLayout(8, 8));
        controls.setPreferredSize(new Dimension(330, 620));
        final JPanel fields = new JPanel(new GridLayout(0, 1, 4, 4));
        fields.add(wrapped("Validation-only atlas browser. It starts at a neutral level and displays no r3 coordinate, automatic overlay, or candidate score."));
        fields.add(new JLabel("Browse Allen level (0–527; not bregma)"));
        fields.add(browseLevel);
        fields.add(new JLabel("Inclusive AP start"));
        fields.add(startLevel);
        fields.add(new JLabel("Inclusive AP end"));
        fields.add(endLevel);
        fields.add(wrapped("The interval must span 32–160 indices (0.8–4.0 mm). It controls candidate placement only."));
        fields.add(new JLabel("Observed tissue class"));
        fields.add(tissueClass);
        fields.add(lock);
        controls.add(fields, BorderLayout.NORTH);
        controls.add(status, BorderLayout.SOUTH);
        return controls;
    }

    private void loadLevel(final int level) {
        final int generation = loadGeneration.incrementAndGet();
        status.setText("Loading verified atlas level " + level + "…");
        lock.setEnabled(false);
        new SwingWorker<AtlasCoronalPlane, Void>() {
            @Override
            protected AtlasCoronalPlane doInBackground() {
                return atlasSource.load(level);
            }

            @Override
            protected void done() {
                if (generation != loadGeneration.get()) {
                    return;
                }
                try {
                    final AtlasCoronalPlane plane = get();
                    canvas.setPlane(plane);
                    status.setText("Verified atlas level " + level
                            + " — " + level * 25
                            + " µm from anterior origin (not bregma)");
                    lock.setEnabled(true);
                } catch (Exception error) {
                    status.setText("Atlas load failed: " + error.getMessage());
                }
            }
        }.execute();
    }

    private void lockPrior() {
        final int lower = ((Number) startLevel.getValue()).intValue();
        final int upper = ((Number) endLevel.getValue()).intValue();
        try {
            final Instant lockedAt = Instant.now();
            final AnatomicalSearchPriorV1 prior =
                    AnatomicalSearchPriorV1.lockBeforeAutomaticDisplay(
                            providerId,
                            sourceSha256,
                            atlasIdentitySha256,
                            new AllenCoronalLevel(lower),
                            new AllenCoronalLevel(upper),
                            Objects.requireNonNull(
                                    (AnatomicalTissueClass) tissueClass
                                            .getSelectedItem()),
                            openedAt,
                            lockedAt,
                            Optional.empty());
            lock.setEnabled(false);
            browseLevel.setEnabled(false);
            startLevel.setEnabled(false);
            endLevel.setEnabled(false);
            tissueClass.setEnabled(false);
            status.setText("Prior locked. Automatic output was never displayed.");
            lockedPriorConsumer.accept(new CapturedPrior(
                    prior,
                    Duration.between(openedAt, lockedAt).toNanos()
                            / 1_000_000_000.0,
                    Math.max(0, revisionCount)));
        } catch (IllegalArgumentException error) {
            status.setText(error.getMessage());
        }
    }

    static JLabel wrapped(final String text) {
        return new JLabel("<html><body style='width:280px'>" + text + "</body></html>");
    }

    JSlider browseLevelControl() {
        return browseLevel;
    }

    JSpinner startLevelControl() {
        return startLevel;
    }

    JSpinner endLevelControl() {
        return endLevel;
    }

    JButton lockControl() {
        return lock;
    }

    public record CapturedPrior(
            AnatomicalSearchPriorV1 prior,
            double entrySeconds,
            int preResultRevisionCount) {
    }

    private static final class NeutralAtlasCanvas extends JComponent {

        private BufferedImage contours;

        private NeutralAtlasCanvas() {
            setPreferredSize(new Dimension(800, 620));
            setOpaque(true);
            setBackground(Color.BLACK);
        }

        private void setPlane(final AtlasCoronalPlane plane) {
            if (!SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException(
                        "Neutral atlas canvas updates must run on the EDT");
            }
            final int width = plane.width();
            final int height = plane.height();
            final int[] labels = plane.annotationId();
            final BufferedImage image = new BufferedImage(
                    width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final int current = labels[y * width + x];
                    if (current == 0) {
                        continue;
                    }
                    final boolean boundary = x == 0 || y == 0
                            || x == width - 1 || y == height - 1
                            || labels[y * width + x - 1] != current
                            || labels[y * width + x + 1] != current
                            || labels[(y - 1) * width + x] != current
                            || labels[(y + 1) * width + x] != current;
                    if (boundary) {
                        image.setRGB(x, y, 0xffff8c2a);
                    }
                }
            }
            contours = image;
            repaint();
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            super.paintComponent(graphics);
            if (contours == null) {
                return;
            }
            final Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                final double scale = Math.min(
                        getWidth() / (double) contours.getWidth(),
                        getHeight() / (double) contours.getHeight());
                final int width = (int) Math.round(contours.getWidth() * scale);
                final int height = (int) Math.round(contours.getHeight() * scale);
                canvas.drawImage(
                        contours,
                        (getWidth() - width) / 2,
                        (getHeight() - height) / 2,
                        width,
                        height,
                        null);
            } finally {
                canvas.dispose();
            }
        }
    }
}
