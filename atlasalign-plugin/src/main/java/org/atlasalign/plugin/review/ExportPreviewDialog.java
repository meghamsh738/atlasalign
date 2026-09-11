package org.atlasalign.plugin.review;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.*;
import org.atlasalign.application.AcceptedAlignmentSnapshot;
import org.atlasalign.application.RegistrationInput;
import org.atlasalign.application.roi.ManualRoiFootprint;
import org.atlasalign.application.roi.ReviewerRoi;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.core.SourceImageMetadata;
import org.atlasalign.plugin.export.ExportRegionSelection;
import org.atlasalign.plugin.export.SourceSpaceFootprintProjector;

/** Synchronized, bounded source/outline/mask comparison before publication. */
final class ExportPreviewDialog {
    record Images(BufferedImage source, BufferedImage outline, BufferedImage mask, String sourceLabel) { }
    private ExportPreviewDialog() { }

    static Images manual(final ReviewPreview preview, final RegistrationInput input, final SourceImageMetadata metadata,
            final List<ReviewerRoi> rois) {
        return images(preview, input, new PreviewMapping(metadata.width(), metadata.height(), preview.width(), preview.height()),
                (x, y) -> rois.stream().anyMatch(roi -> ManualRoiFootprint.containsSourcePixel(roi, x, y, metadata.width(), metadata.height())));
    }
    static Images atlas(final ReviewPreview preview, final RegistrationInput input, final AcceptedAlignmentSnapshot accepted,
            final AtlasCoronalPlane plane, final List<ExportRegionSelection> regions) {
        return images(preview, input, accepted.previewMapping(), new SourceSpaceFootprintProjector().membership(accepted, plane, regions));
    }

    private static Images images(final ReviewPreview preview, final RegistrationInput input, final PreviewMapping mapping,
            final SourceSpaceFootprintProjector.PixelMembership membership) {
        final double scale = Math.min(1, 230.0 / Math.max(preview.width(), preview.height()));
        final int width = Math.max(1, (int) Math.round(preview.width() * scale));
        final int height = Math.max(1, (int) Math.round(preview.height() * scale));
        final BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final BufferedImage outline = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final float[] pixels = preview.pixels(); final var contrast = preview.displayWindow();
        final boolean[] selected = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            for (int x = 0; x < width; x++) {
                final int px = Math.min(preview.width() - 1, (int) Math.floor((x + .5) * preview.width() / width));
                final int py = Math.min(preview.height() - 1, (int) Math.floor((y + .5) * preview.height() / height));
                final var nativePoint = mapping.previewToSource(new org.atlasalign.core.Point2D(px, py));
                final int sx = Math.max(0, Math.min(mapping.sourceWidth() - 1, (int) Math.round(nativePoint.x())));
                final int sy = Math.max(0, Math.min(mapping.sourceHeight() - 1, (int) Math.round(nativePoint.y())));
                selected[y * width + x] = membership.contains(sx, sy);
                final int value = (int) Math.max(0, Math.min(255, Math.round((pixels[py * preview.width() + px] - contrast.lower()) / contrast.range() * 255)));
                source.setRGB(x, y, value * 0x010101); mask.setRGB(x, y, selected[y * width + x] ? 0xffffff : 0);
            }
        }
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            final boolean edge = selected[y * width + x] && (x == 0 || y == 0 || x + 1 == width || y + 1 == height
                    || !selected[y * width + x - 1] || !selected[y * width + x + 1]
                    || !selected[(y - 1) * width + x] || !selected[(y + 1) * width + x]);
            outline.setRGB(x, y, edge ? 0x33ff99 : source.getRGB(x, y));
        }
        return new Images(source, outline, mask, "Source C" + input.channel() + " · Z" + input.slice() + " · T" + input.frame());
    }

    static boolean confirm(final Component owner, final JComponent summary, final Callable<Images> build) {
        final JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), "Review export", Dialog.ModalityType.APPLICATION_MODAL);
        final JPanel content = new JPanel(new BorderLayout(8, 10)); content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        final JScrollPane details = new JScrollPane(summary); details.setBorder(null);
        details.setPreferredSize(new Dimension(720, Math.min(250, summary.getPreferredSize().height + 6)));
        content.add(details, BorderLayout.NORTH);
        final JPanel previews = new JPanel(new GridLayout(1, 3, 8, 0)); previews.add(new JLabel("Preparing synchronized previews…"));
        previews.setPreferredSize(new Dimension(720, 255)); content.add(previews, BorderLayout.CENTER);
        final JLabel explanation = new JLabel("<html>Previews sample source pixels at a reduced display size. Export writes the full native pixels and masks.<br>Zero-filled masked images still require their mask for quantitative measurements.</html>");
        final JButton export = new JButton("Export"); export.setEnabled(false);
        final JButton cancel = new JButton("Cancel"); final boolean[] approved = {false};
        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT)); actions.add(cancel); actions.add(export);
        final JPanel footer = new JPanel(new BorderLayout(0, 8)); footer.add(explanation, BorderLayout.CENTER); footer.add(actions, BorderLayout.SOUTH);
        content.add(footer, BorderLayout.SOUTH);
        final SwingWorker<Images, Void> worker = new SwingWorker<>() {
            @Override protected Images doInBackground() throws Exception { return build.call(); }
            @Override protected void done() {
                if (!dialog.isDisplayable()) return;
                try {
                    final Images images = get(); previews.removeAll();
                    previews.add(imagePanel(images.sourceLabel(), images.source())); previews.add(imagePanel("Selected outline", images.outline()));
                    previews.add(imagePanel("Exact membership · sampled", images.mask()));
                    previews.revalidate(); previews.repaint(); export.setEnabled(true);
                } catch (Exception error) { previews.removeAll(); previews.add(new JLabel("Preview failed; cancel and check the review.")); previews.revalidate(); previews.repaint(); }
            }
        };
        export.addActionListener(event -> { approved[0] = true; dialog.dispose(); });
        cancel.addActionListener(event -> dialog.dispose());
        dialog.setContentPane(content); dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); dialog.pack();
        dialog.setLocationRelativeTo(owner); worker.execute(); dialog.setVisible(true); worker.cancel(true); return approved[0];
    }
    private static JComponent imagePanel(final String title, final BufferedImage image) {
        final JPanel panel = new JPanel(new BorderLayout(0, 5)); panel.add(new JLabel(title, SwingConstants.CENTER), BorderLayout.NORTH);
        panel.add(new JLabel(new ImageIcon(image), SwingConstants.CENTER), BorderLayout.CENTER); return panel;
    }
}
