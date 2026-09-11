package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.*;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.atlasalign.application.*;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.junit.jupiter.api.Test;

class ChannelPreviewRendererTest {
    @Test void singleAndCompositeUseExplicitPlanesWithoutChangingSourceOrRegistration() throws Exception {
        final var source = source();
        final var adapter = new ImagePlusSourceImage(source);
        final var before = adapter.snapshot(); final int c = source.getC(), z = source.getZ(), t = source.getT();
        final double min = source.getDisplayRangeMin(), max = source.getDisplayRangeMax();
        final var registration = adapter.createPreview(new RegistrationInput(1, 1, 1), 64);
        final var worker = Executors.newSingleThreadExecutor();
        try (var renderer = new ChannelPreviewRenderer(input -> adapter.createPreview(input, 64), before.metadata(), 8, 8, 1024, worker, Runnable::run)) {
            final var channels = List.of(new DisplaySettings.Channel(1, true, DisplaySettings.Lut.RED, 0, 1000, false),
                    new DisplaySettings.Channel(2, false, DisplaySettings.Lut.GREEN, 0, 1000, false),
                    new DisplaySettings.Channel(3, true, DisplaySettings.Lut.BLUE, 0, 1000, false));
            final var single = render(renderer, new DisplaySettings(DisplaySettings.Mode.SINGLE, 2, 2, 1, channels));
            assertEquals(0x008000, single.image().getRGB(0, 0) & 0xffffff);
            final var composite = render(renderer, new DisplaySettings(DisplaySettings.Mode.COMPOSITE, 1, 2, 1, channels));
            // BLUE's readable LUT contains a small red/green component; sum is clamped once per channel.
            assertEquals(0x8c4d99, composite.image().getRGB(0, 0) & 0xffffff);
            assertEquals(before, adapter.snapshot()); assertEquals(c, source.getC()); assertEquals(z, source.getZ()); assertEquals(t, source.getT());
            assertEquals(min, source.getDisplayRangeMin()); assertEquals(max, source.getDisplayRangeMax());
            assertArrayEquals(registration.pixels(), adapter.createPreview(new RegistrationInput(1, 1, 1), 64).pixels());
        }
    }

    @Test void cacheEvictsCopiedPlanesWithinByteBudget() throws Exception {
        final var image = source(); final var adapter = new ImagePlusSourceImage(image);
        final var metadata = adapter.snapshot().metadata();
        final Map<Integer, AtomicInteger> reads = new ConcurrentHashMap<>();
        try (var renderer = new ChannelPreviewRenderer(input -> {
            reads.computeIfAbsent(input.channel(), ignored -> new AtomicInteger()).incrementAndGet(); return adapter.createPreview(input, 64);
        }, metadata, 8, 8, 2 * 8 * 8 * Float.BYTES, Executors.newSingleThreadExecutor(), Runnable::run)) {
            final var base = DisplaySettings.defaults(metadata, new RegistrationInput(1, 1, 1));
            for (int channel : new int[]{1, 2, 3, 1}) render(renderer, new DisplaySettings(DisplaySettings.Mode.SINGLE, channel, 1, 1, base.channels()));
            assertEquals(2, reads.get(1).get()); assertTrue(renderer.cachedBytesForTests() <= 512);
        }
    }

    @Test void staleUncancellableReadCannotReplaceNewerRequestedChannel() throws Exception {
        final var metadata = new ImagePlusSourceImage(source()).snapshot().metadata();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(1);
        final var delivered = new CopyOnWriteArrayList<Integer>(); final var error = new AtomicReference<String>();
        try (var renderer = new ChannelPreviewRenderer(input -> {
            if (input.channel() == 1) {
                entered.countDown(); boolean ready = false;
                while (!ready) try { release.await(); ready = true; } catch (InterruptedException ignored) { }
            }
            final float[] values = new float[64]; java.util.Arrays.fill(values, input.channel());
            return new RegistrationPreview(input.channel(), input.slice(), input.frame(), new PreviewMapping(8, 8, 8, 8), values);
        }, metadata, 8, 8, 512, Executors.newSingleThreadExecutor(), Runnable::run)) {
            final var base = DisplaySettings.defaults(metadata, new RegistrationInput(1, 1, 1));
            renderer.request(base, frame -> delivered.add(frame.settings().selectedChannel()), error::set);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            renderer.request(new DisplaySettings(DisplaySettings.Mode.SINGLE, 2, 1, 1, base.channels()), frame -> {
                delivered.add(frame.settings().selectedChannel()); completed.countDown();
            }, error::set);
            release.countDown(); assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertNull(error.get()); assertEquals(List.of(2), delivered);
        } finally { release.countDown(); }
    }

    private static ChannelPreviewRenderer.Frame render(final ChannelPreviewRenderer renderer, final DisplaySettings settings) throws Exception {
        final CompletableFuture<ChannelPreviewRenderer.Frame> result = new CompletableFuture<>();
        renderer.request(settings, result::complete, error -> result.completeExceptionally(new AssertionError(error)));
        return result.get(5, TimeUnit.SECONDS);
    }
    private static ImagePlus source() {
        final ImageStack stack = new ImageStack(8, 8);
        for (int plane = 1; plane <= 12; plane++) { final short[] pixels = new short[64]; java.util.Arrays.fill(pixels, (short) (plane * 100)); stack.addSlice("plane " + plane, new ShortProcessor(8, 8, pixels, null)); }
        final ImagePlus result = new ImagePlus("multichannel.tif", stack); result.setDimensions(3, 2, 2);
        result.setOpenAsHyperStack(true); result.setPositionWithoutUpdate(3, 1, 2); result.setDisplayRange(25, 900);
        result.getCalibration().pixelWidth = .7; result.getCalibration().pixelHeight = .8; result.getCalibration().pixelDepth = 2.2;
        return result;
    }
}
