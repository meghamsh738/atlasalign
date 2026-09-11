package org.atlasalign.plugin.review;

import ij.ImagePlus;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.atlasalign.application.DisplaySettings;
import org.atlasalign.application.RegistrationInput;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.core.SourceImageMetadata;
import org.atlasalign.io.imagej.ImagePlusSourceImage;

/** Bounded copied-plane display cache, independent of registration and export. */
public final class ChannelPreviewRenderer implements AutoCloseable {
    public static final long CACHE_BUDGET_BYTES = 256L * 1024 * 1024;
    @FunctionalInterface interface PlaneReader { RegistrationPreview read(RegistrationInput input); }
    public record Frame(DisplaySettings settings, BufferedImage image, Map<Integer, PreviewDisplayWindow> automaticWindows) {
        public Frame { automaticWindows = Map.copyOf(automaticWindows); }
    }
    private record Cached(float[] pixels, PreviewDisplayWindow contrast) { }
    private final PlaneReader reader;
    private final SourceImageMetadata metadata;
    private final int width;
    private final int height;
    private final long cacheBudget;
    private final Map<RegistrationInput, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private final ExecutorService worker;
    private final Executor view;
    private final AtomicLong tickets = new AtomicLong();
    private long cachedBytes;
    private Future<?> active;
    private boolean closed;

    public ChannelPreviewRenderer(final ImagePlus source, final SourceImageMetadata metadata,
            final int width, final int height) {
        this(input -> new ImagePlusSourceImage(source).createPreview(input, Math.max(width, height)), metadata,
                width, height, CACHE_BUDGET_BYTES,
                Executors.newSingleThreadExecutor(task -> {
                    final Thread thread = new Thread(task, "atlasalign-channel-view-loader"); thread.setDaemon(true); return thread;
                }), javax.swing.SwingUtilities::invokeLater);
    }

    ChannelPreviewRenderer(final PlaneReader reader, final SourceImageMetadata metadata, final int width,
            final int height, final long cacheBudget, final ExecutorService worker, final Executor view) {
        this.reader = Objects.requireNonNull(reader); this.metadata = Objects.requireNonNull(metadata);
        this.width = width; this.height = height; this.cacheBudget = cacheBudget;
        this.worker = worker; this.view = view;
        if (width < 1 || height < 1 || cacheBudget < 0) throw new IllegalArgumentException("Invalid display buffer limits");
    }

    public synchronized void request(final DisplaySettings settings, final Consumer<Frame> completed,
            final Consumer<String> failed) {
        if (closed) return;
        settings.validateAgainst(metadata);
        final long ticket = tickets.incrementAndGet();
        if (active != null) active.cancel(true);
        active = worker.submit(() -> {
            try {
                final Frame frame = render(settings, ticket);
                view.execute(() -> { if (tickets.get() == ticket) completed.accept(frame); });
            } catch (java.util.concurrent.CancellationException ignored) {
                // A newer C/Z/T or display request owns the view.
            } catch (RuntimeException error) {
                view.execute(() -> { if (tickets.get() == ticket) failed.accept(error.getMessage()); });
            }
        });
    }

    private Frame render(final DisplaySettings settings, final long ticket) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final int[] output = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
        final Map<Integer, PreviewDisplayWindow> windows = new java.util.HashMap<>();
        for (final var channel : settings.channels()) {
            checkCurrent(ticket);
            if (settings.mode() == DisplaySettings.Mode.SINGLE ? channel.index() != settings.selectedChannel() : !channel.visible()) continue;
            final var copied = plane(new RegistrationInput(channel.index(), settings.slice(), settings.frame()), ticket);
            windows.put(channel.index(), copied.contrast());
            final double minimum = channel.automaticContrast() ? copied.contrast().lower() : channel.minimum();
            final double range = channel.automaticContrast() ? copied.contrast().range() : channel.maximum() - channel.minimum();
            final int color = channel.lut().rgb();
            for (int i = 0; i < output.length; i++) {
                if ((i & 0x3fff) == 0) checkCurrent(ticket);
                final double value = Math.max(0, Math.min(1, (copied.pixels()[i] - minimum) / range));
                final int r = Math.min(255, ((output[i] >>> 16) & 255) + (int) Math.round(((color >>> 16) & 255) * value));
                final int g = Math.min(255, ((output[i] >>> 8) & 255) + (int) Math.round(((color >>> 8) & 255) * value));
                final int b = Math.min(255, (output[i] & 255) + (int) Math.round((color & 255) * value));
                output[i] = (r << 16) | (g << 8) | b;
            }
        }
        checkCurrent(ticket);
        return new Frame(settings, image, windows);
    }

    private Cached plane(final RegistrationInput input, final long ticket) {
        final Cached existing = cache.get(input);
        if (existing != null) return existing;
        final RegistrationPreview copied = reader.read(input);
        checkCurrent(ticket);
        if (!RegistrationInput.from(copied).equals(input) || copied.mapping().previewWidth() != width
                || copied.mapping().previewHeight() != height) throw new IllegalArgumentException("Display plane does not match the requested C/Z/T or preview dimensions");
        final float[] pixels = copied.pixels();
        final Cached result = new Cached(pixels, PreviewContrast.window(pixels));
        final long bytes = (long) pixels.length * Float.BYTES;
        if (bytes <= cacheBudget) {
            while (cachedBytes + bytes > cacheBudget && !cache.isEmpty()) {
                final var iterator = cache.entrySet().iterator();
                cachedBytes -= (long) iterator.next().getValue().pixels().length * Float.BYTES; iterator.remove();
            }
            cache.put(input, result); cachedBytes += bytes;
        }
        return result;
    }

    long cachedBytesForTests() { return cachedBytes; }
    private void checkCurrent(final long ticket) {
        if (Thread.currentThread().isInterrupted() || tickets.get() != ticket) throw new java.util.concurrent.CancellationException();
    }
    @Override public synchronized void close() {
        closed = true; tickets.incrementAndGet(); if (active != null) active.cancel(true); worker.shutdownNow();
    }
}
