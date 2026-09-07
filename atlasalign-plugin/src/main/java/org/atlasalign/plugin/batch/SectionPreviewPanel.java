package org.atlasalign.plugin.batch;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.io.imagej.ImagePlusSourceImage;

/** Bounded, display-only crop preview. No full-resolution crop is allocated. */
final class SectionPreviewPanel extends JPanel {
    private final Object buildLock = new Object();
    private SwingWorker<BufferedImage, Void> worker;
    private String sectionId;
    private String planeDescription;
    private BufferedImage image;
    private String message = "Select a section";

    SectionPreviewPanel() {
        setName("batchSectionPreview");
        setPreferredSize(new Dimension(280, 160));
        setMinimumSize(new Dimension(100, 160));
        setBackground(Color.DARK_GRAY);
    }

    void showSection(final BatchReviewItem item, final int channel) {
        final int selectedChannel = Math.min(channel, item.source().getNChannels());
        final int z = item.source().getZ();
        final int t = item.source().getT();
        if (!item.section().id().equals(sectionId)) {
            planeDescription = " • channel " + selectedChannel + ", Z " + z
                    + ", T " + t + " • display-only contrast";
        }
        setToolTipText(item.section().name() + planeDescription);
        getAccessibleContext().setAccessibleName("Selected section preview: " + item.section().name());
        if (item.section().id().equals(sectionId)) return;
        sectionId = item.section().id();
        if (worker != null) worker.cancel(true);
        image = null;
        message = "Loading section preview…";
        repaint();
        worker = new SwingWorker<>() {
            @Override protected BufferedImage doInBackground() {
                // Superseded requests cannot run source scans concurrently.
                synchronized (buildLock) {
                    return render(item, selectedChannel, z, t, this::isCancelled);
                }
            }
            @Override protected void done() {
                if (worker != this || isCancelled()) return;
                try {
                    image = get();
                    message = "";
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    message = "Preview interrupted";
                } catch (ExecutionException | CancellationException error) {
                    message = "Preview unavailable";
                    setToolTipText(error.getCause() == null ? error.toString()
                            : error.getCause().getMessage());
                }
                repaint();
            }
        };
        worker.execute();
    }

    @Override public void removeNotify() {
        if (worker != null) worker.cancel(true);
        sectionId = null;
        super.removeNotify();
    }

    @Override protected void paintComponent(final Graphics graphics) {
        super.paintComponent(graphics);
        if (image != null) {
            final double scale = Math.min((getWidth() - 8.0) / image.getWidth(),
                    (getHeight() - 8.0) / image.getHeight());
            final int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            final int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            graphics.drawImage(image, (getWidth() - width) / 2,
                    (getHeight() - height) / 2, width, height, null);
        } else {
            graphics.setColor(Color.WHITE);
            graphics.drawString(message, 10, getHeight() / 2);
        }
    }

    static BufferedImage render(final BatchReviewItem item, final int channel,
            final int z, final int t, final BooleanSupplier cancelled) {
        return renderRegion(item, channel, z, t, cancelled,
                item.section().minimumX(), item.section().minimumY(),
                item.section().width(), item.section().height(), 320);
    }

    static BufferedImage renderRegion(final BatchReviewItem item, final int channel,
            final int z, final int t, final BooleanSupplier cancelled,
            final int minimumX, final int minimumY, final int width, final int height,
            final int maximumDimension) {
        checkCancelled(cancelled);
        final var adapter = new ImagePlusSourceImage(item.source());
        if (!item.verifiedSource().equals(adapter.snapshot())) {
            throw new IllegalStateException("The batch source changed after queue creation");
        }
        checkCancelled(cancelled);
        final var mapping = PreviewMapping.bounded(width, height, maximumDimension);
        final var processor = item.source().getStack().getProcessor(
                item.source().getStackIndex(channel, z, t));
        final var result = new BufferedImage(mapping.previewWidth(), mapping.previewHeight(),
                BufferedImage.TYPE_INT_RGB);
        final float[] samples = new float[result.getWidth() * result.getHeight()];
        for (int y = 0; y < result.getHeight(); y++) {
            checkCancelled(cancelled);
            final int sourceY = minimumY + Math.min(height - 1,
                    (int) Math.floor((y + 0.5) / mapping.scaleY()));
            for (int x = 0; x < result.getWidth(); x++) {
                final int sourceX = minimumX + Math.min(width - 1,
                        (int) Math.floor((x + 0.5) / mapping.scaleX()));
                if (item.source().getBitDepth() == 24) {
                    result.setRGB(x, y, processor.get(sourceX, sourceY));
                } else {
                    samples[y * result.getWidth() + x] = processor.getf(sourceX, sourceY);
                }
            }
        }
        if (item.source().getBitDepth() != 24) {
            final float[] sorted = samples.clone();
            for (float value : sorted) if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Section preview contains non-finite pixels");
            }
            Arrays.sort(sorted);
            float lower = sorted[(int) Math.floor((sorted.length - 1) * 0.005)];
            float upper = sorted[(int) Math.ceil((sorted.length - 1) * 0.995)];
            if (!(upper > lower)) { lower = sorted[0]; upper = sorted[sorted.length - 1]; }
            for (int i = 0; i < samples.length; i++) {
                final int gray = upper > lower ? (int) Math.round(Math.max(0, Math.min(255,
                        (samples[i] - (double) lower) * 255 / (upper - (double) lower)))) : 127;
                result.setRGB(i % result.getWidth(), i / result.getWidth(), gray * 0x010101);
            }
        }
        checkCancelled(cancelled);
        if (!item.verifiedSource().equals(adapter.snapshot())) {
            throw new IllegalStateException("The batch source changed while creating the preview");
        }
        return result;
    }

    private static void checkCancelled(final BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException();
    }
}
