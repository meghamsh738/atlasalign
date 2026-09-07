package org.atlasalign.plugin;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Test-only Swing event-dispatch heartbeat for real-runtime validation.
 */
final class InterfaceResponsivenessHeartbeat implements AutoCloseable {

    private static final int INTERVAL_MILLISECONDS = 250;
    private static final long MAXIMUM_GAP_NANOSECONDS =
            Duration.ofSeconds(2).toNanos();

    private final AtomicInteger ticks = new AtomicInteger();
    private final AtomicLong lastNanos =
            new AtomicLong(System.nanoTime());
    private final AtomicLong maximumGapNanos = new AtomicLong();
    private final Timer timer;
    private boolean stopped;

    private InterfaceResponsivenessHeartbeat() {
        timer = new Timer(INTERVAL_MILLISECONDS, ignored -> {
            final long now = System.nanoTime();
            final long previous = lastNanos.getAndSet(now);
            maximumGapNanos.accumulateAndGet(
                    now - previous, Math::max);
            ticks.incrementAndGet();
        });
        timer.setInitialDelay(0);
        timer.setCoalesce(true);
    }

    static InterfaceResponsivenessHeartbeat start() throws Exception {
        final InterfaceResponsivenessHeartbeat heartbeat =
                new InterfaceResponsivenessHeartbeat();
        SwingUtilities.invokeAndWait(heartbeat.timer::start);
        return heartbeat;
    }

    Snapshot stop() throws Exception {
        if (!stopped) {
            SwingUtilities.invokeAndWait(timer::stop);
            stopped = true;
        }
        final long trailingGap =
                System.nanoTime() - lastNanos.get();
        maximumGapNanos.accumulateAndGet(
                trailingGap, Math::max);
        final int observedTicks = ticks.get();
        final long observedMaximum = maximumGapNanos.get();
        return new Snapshot(
                observedTicks >= 4
                        && observedMaximum <= MAXIMUM_GAP_NANOSECONDS,
                observedTicks,
                observedMaximum / 1_000_000_000.0);
    }

    @Override
    public void close() throws Exception {
        stop();
    }

    record Snapshot(
            boolean responsive,
            int ticks,
            double maximumGapSeconds) {
    }
}
