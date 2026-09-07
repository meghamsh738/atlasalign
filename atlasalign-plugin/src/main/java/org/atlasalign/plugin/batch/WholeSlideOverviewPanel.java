package org.atlasalign.plugin.batch;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.IntConsumer;
import javax.swing.*;

/** Read-only source-space marker overview; viewport transforms never touch export geometry. */
final class WholeSlideOverviewPanel extends JPanel {
    private final Canvas canvas = new Canvas();
    private final IntConsumer selection;
    private final Object buildLock = new Object();
    private SwingWorker<BufferedImage, Void> worker;
    private BatchReviewItem source;
    private int channel, z, t;
    private BufferedImage image;
    private List<Marker> markers = List.of();
    private int selectedIndex;
    private String message = "Select a section";
    private double zoom = 1, panX, panY;

    WholeSlideOverviewPanel(final IntConsumer selection) {
        super(new BorderLayout(2, 2));
        this.selection = selection;
        setName("batchWholeSlideOverview");
        setBorder(BorderFactory.createTitledBorder("Whole slide"));
        setPreferredSize(new Dimension(440, 280));
        setMinimumSize(new Dimension(180, 140));
        add(canvas, BorderLayout.CENTER);
        final JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        addButton(controls, "Fit", "batchOverviewFit", () -> { zoom = 1; panX = panY = 0; canvas.repaint(); });
        addButton(controls, "−", "batchOverviewZoomOut", () -> changeZoom(1 / 1.5));
        addButton(controls, "+", "batchOverviewZoomIn", () -> changeZoom(1.5));
        controls.add(new JLabel("Drag to pan"));
        add(controls, BorderLayout.SOUTH);
    }

    private void addButton(JPanel panel, String text, String name, Runnable action) {
        final JButton button = new JButton(text);
        button.setName(name);
        button.addActionListener(event -> action.run());
        panel.add(button);
    }

    private void changeZoom(final double factor) {
        final double previous = zoom;
        zoom = Math.max(1, Math.min(16, zoom * factor));
        panX *= zoom / previous;
        panY *= zoom / previous;
        if (zoom == 1) panX = panY = 0;
        canvas.repaint();
    }

    void showSections(final List<BatchReviewItem> items, final int selected, final int requestedChannel) {
        final BatchReviewItem next = items.get(selected);
        markers = markersFor(items, next);
        selectedIndex = selected;
        canvas.getAccessibleContext().setAccessibleName("Whole slide: " + next.section().sourceName()
                + "; selected section " + (selected + 1) + ": " + next.section().name());
        final int nextChannel = Math.min(requestedChannel, next.source().getNChannels());
        final int nextZ = next.source().getZ(), nextT = next.source().getT();
        if (source != null && source.source() == next.source()
                && source.verifiedSource().equals(next.verifiedSource())
                && channel == nextChannel && z == nextZ && t == nextT) {
            canvas.repaint();
            return;
        }
        source = next;
        channel = nextChannel; z = nextZ; t = nextT;
        zoom = 1; panX = panY = 0;
        if (worker != null) worker.cancel(true);
        image = null;
        message = "Loading whole slide…";
        canvas.repaint();
        worker = new SwingWorker<>() {
            @Override protected BufferedImage doInBackground() {
                synchronized (buildLock) {
                    return SectionPreviewPanel.renderRegion(next, nextChannel, nextZ, nextT,
                            this::isCancelled, 0, 0, next.section().sourceWidth(),
                            next.section().sourceHeight(), 1024);
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
                    message = "Whole-slide preview unavailable";
                }
                canvas.repaint();
            }
        };
        worker.execute();
    }

    @Override public void removeNotify() {
        if (worker != null) worker.cancel(true);
        source = null;
        super.removeNotify();
    }

    static List<Marker> markersFor(final List<BatchReviewItem> items, final BatchReviewItem selected) {
        final List<Marker> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            // Runtime identity distinguishes separate sources even when their pixels happen to match.
            if (items.get(i).source() == selected.source()) {
                final var section = items.get(i).section();
                final Path2D.Double path = new Path2D.Double();
                final var vertices = section.markerVertices();
                path.moveTo(vertices.get(0).x(), vertices.get(0).y());
                double twiceArea = 0;
                for (int v = 0; v < vertices.size(); v++) {
                    final var point = vertices.get(v);
                    final var following = vertices.get((v + 1) % vertices.size());
                    if (v > 0) path.lineTo(point.x(), point.y());
                    twiceArea += point.x() * following.y() - following.x() * point.y();
                }
                path.closePath();
                result.add(new Marker(i, section.name(), path, Math.abs(twiceArea) / 2));
            }
        }
        return List.copyOf(result);
    }

    static int hitTest(final List<Marker> markers, final double sourceX, final double sourceY) {
        Marker best = null;
        for (Marker marker : markers) {
            if (marker.path().contains(sourceX, sourceY)
                    && (best == null || marker.area() < best.area()
                    || (marker.area() == best.area() && marker.index() < best.index()))) best = marker;
        }
        return best == null ? -1 : best.index();
    }

    /** Image boundaries are at -0.5 and size-0.5; source pixel centers map independently per axis. */
    static AffineTransform sourceToView(int sourceWidth, int sourceHeight,
            int previewWidth, int previewHeight, int viewWidth, int viewHeight,
            double zoom, double panX, double panY) {
        final double fit = Math.max(0.001, Math.min((viewWidth - 8.0) / previewWidth,
                (viewHeight - 8.0) / previewHeight)) * zoom;
        final double width = previewWidth * fit, height = previewHeight * fit;
        final AffineTransform transform = new AffineTransform();
        transform.translate((viewWidth - width) / 2 + panX, (viewHeight - height) / 2 + panY);
        transform.scale(width / sourceWidth, height / sourceHeight);
        transform.translate(0.5, 0.5);
        return transform;
    }

    record Marker(int index, String name, Path2D.Double path, double area) { }

    private final class Canvas extends JPanel {
        private Point dragStart;
        private double startX, startY;
        private boolean dragged;
        Canvas() {
            setName("batchWholeSlideCanvas");
            setBackground(Color.DARK_GRAY);
            setToolTipText("Select a numbered section; drag to pan after zooming");
            final MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    dragStart = event.getPoint(); startX = panX; startY = panY; dragged = false;
                }
                @Override public void mouseDragged(MouseEvent event) {
                    if (dragStart != null && event.getPoint().distance(dragStart) > 3) dragged = true;
                    if (dragged && zoom > 1) {
                        panX = startX + event.getX() - dragStart.x;
                        panY = startY + event.getY() - dragStart.y;
                        repaint();
                    }
                }
                @Override public void mouseReleased(MouseEvent event) {
                    if (!dragged) {
                        final int hit = hitAt(event.getPoint());
                        if (hit >= 0) selection.accept(hit);
                    }
                    dragStart = null;
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }
        private AffineTransform mapping() {
            return sourceToView(source.section().sourceWidth(), source.section().sourceHeight(),
                    image.getWidth(), image.getHeight(), getWidth(), getHeight(), zoom, panX, panY);
        }
        private int hitAt(Point point) {
            if (image == null) return -1;
            try {
                final Point2D sourcePoint = mapping().inverseTransform(point, null);
                return hitTest(markers, sourcePoint.getX(), sourcePoint.getY());
            } catch (java.awt.geom.NoninvertibleTransformException error) { return -1; }
        }
        @Override public String getToolTipText(MouseEvent event) {
            final int hit = hitAt(event.getPoint());
            for (Marker marker : markers) if (marker.index() == hit) return (hit + 1) + ". " + marker.name();
            return source == null ? "Whole slide" : source.section().sourceName()
                    + " • channel " + channel + ", Z " + z + ", T " + t + " • display-only contrast";
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                if (image == null) { g.setColor(Color.WHITE); g.drawString(message, 8, getHeight() / 2); return; }
                final AffineTransform sourceMapping = mapping();
                final AffineTransform pixels = new AffineTransform(sourceMapping);
                pixels.translate(-0.5, -0.5);
                pixels.scale(source.section().sourceWidth() / (double) image.getWidth(),
                        source.section().sourceHeight() / (double) image.getHeight());
                g.drawImage(image, pixels, null);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Selected outline is drawn last so it remains visible where sections overlap.
                for (Marker marker : markers) if (marker.index() != selectedIndex) drawMarker(g, sourceMapping, marker, false);
                for (Marker marker : markers) if (marker.index() == selectedIndex) drawMarker(g, sourceMapping, marker, true);
            } finally { g.dispose(); }
        }
        private void drawMarker(Graphics2D g, AffineTransform transform, Marker marker, boolean selected) {
            final Shape outline = transform.createTransformedShape(marker.path());
            g.setStroke(new BasicStroke(selected ? 5 : 3));
            g.setColor(Color.BLACK); g.draw(outline);
            g.setStroke(new BasicStroke(selected ? 3 : 1.5f));
            g.setColor(selected ? Color.YELLOW : Color.CYAN); g.draw(outline);
            final var bounds = outline.getBounds2D();
            final String label = Integer.toString(marker.index() + 1);
            final int x = (int) bounds.getCenterX(), y = (int) bounds.getCenterY();
            g.setColor(Color.BLACK);
            g.fillRoundRect(x - 3, y - 13, g.getFontMetrics().stringWidth(label) + 6, 18, 5, 5);
            g.setColor(selected ? Color.YELLOW : Color.WHITE); g.drawString(label, x, y);
        }
    }
}
