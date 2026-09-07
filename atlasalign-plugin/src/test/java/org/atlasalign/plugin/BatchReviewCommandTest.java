package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.*;
import ij.gui.Roi;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.scijava.plugin.Parameter;

class BatchReviewCommandTest {
    @Test
    void highlightedLastMarkerDoesNotLimitDefaultImport() {
        final Roi[] all = new Roi[9];
        for (int i = 0; i < all.length; i++) {
            all[i] = new Roi(i * 10, 0, 8, 8);
            all[i].setName("Section " + (i + 1));
        }
        assertEquals(List.of(all), BatchReviewCommand.selectMarkers(all,
                new int[] {8}, false));
        assertEquals(List.of(all[8]), BatchReviewCommand.selectMarkers(all,
                new int[] {8}, true));
        assertEquals(List.of(all[1], all[5]), BatchReviewCommand.selectMarkers(all,
                new int[] {1, 5}, true));
    }

    @Test
    void selectedOnlyNeverSilentlyFallsBackToAllMarkers() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
                BatchReviewCommand.selectMarkers(new Roi[] {new Roi(0, 0, 5, 5)},
                        new int[0], true));
        final var field = BatchReviewCommand.class.getDeclaredField("selectedMarkersOnly");
        field.setAccessible(true);
        assertFalse(field.getBoolean(new BatchReviewCommand()));
        assertFalse(field.getAnnotation(Parameter.class).persist());
    }
}
