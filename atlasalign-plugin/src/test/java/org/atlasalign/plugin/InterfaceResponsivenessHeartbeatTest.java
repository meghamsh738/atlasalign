package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InterfaceResponsivenessHeartbeatTest {

    @Test
    void recordsAnActiveSwingEventDispatchThread() throws Exception {
        final InterfaceResponsivenessHeartbeat.Snapshot snapshot;
        try (InterfaceResponsivenessHeartbeat heartbeat =
                InterfaceResponsivenessHeartbeat.start()) {
            Thread.sleep(1_300);
            snapshot = heartbeat.stop();
        }

        assertTrue(snapshot.responsive());
        assertTrue(snapshot.ticks() >= 4);
        assertTrue(snapshot.maximumGapSeconds() <= 2);
    }
}
