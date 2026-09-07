package org.atlasalign.plugin.batch;

import static org.junit.jupiter.api.Assertions.*;
import ij.ImagePlus;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class BatchReviewNavigationTest {
    @Test void preparationAndWindowFailuresReleaseRegistrationAndReachLauncher() {
        for (String phase : new String[]{"preparation", "window opening"}) {
            var image = new ImagePlus();
            var opening = BatchReviewNavigation.register(image, () -> {});
            var failure = new IllegalStateException(phase);
            assertTrue(BatchReviewNavigation.fail(image, failure));
            assertSame(failure, assertThrows(CompletionException.class, opening::join).getCause());
            assertNull(BatchReviewNavigation.existingWindow(image));
            assertFalse(BatchReviewNavigation.fail(image, failure));
            // A retry is not blocked by a retained failed crop/callback.
            var retry = BatchReviewNavigation.register(image, () -> {});
            BatchReviewNavigation.unregister(image);
            assertTrue(retry.isCompletedExceptionally());
        }
    }

    @Test void registrationsUseCropIdentityAndRejectReplacement() {
        var first = new ImagePlus("same title");
        var second = new ImagePlus("same title");
        var firstOpening = BatchReviewNavigation.register(first, () -> {});
        var secondOpening = BatchReviewNavigation.register(second, () -> {});
        try {
            assertThrows(IllegalStateException.class,
                    () -> BatchReviewNavigation.register(first, () -> {}));
            BatchReviewNavigation.fail(first, new IllegalStateException("first"));
            assertTrue(firstOpening.isCompletedExceptionally());
            assertFalse(secondOpening.isDone());
        } finally {
            BatchReviewNavigation.unregister(first);
            BatchReviewNavigation.unregister(second);
        }
    }
}
