package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import org.atlasalign.application.*;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.roi.*;
import org.atlasalign.core.Point2D;
import org.atlasalign.plugin.project.*;
import org.junit.jupiter.api.Test;

class ReviewProjectCodecTest {
    @Test
    void fullSessionWithTissueClippingAndUnfinishedRoiRoundTripsWithoutInitialization() {
        final var session = AlignmentReviewSession.forNewReview(ReviewPluginFixtures.segmentedScaledAtlasBasis());
        session.apply(new ReviewEdit.Translate(3, 2));
        final var metadata = session.state().basis().sourceSnapshot().metadata();
        final var input = new RegistrationInput(1, 1, 1);
        final var rois = new ReviewerRoiSession("Section", metadata.width(), metadata.height());
        rois.newPolygon("unfinished", ReviewerRoiSide.LEFT, RoiPartOperation.ADD);
        rois.addVertex(new Point2D(3, 4)); rois.addVertex(new Point2D(20, 6));
        final var project = new ReviewProject(new ReviewProject.SourceReference("source.tif", Optional.empty()),
                "/verified/atlas", session.checkpoint(), input, DisplaySettings.defaults(metadata, input),
                ExportSelection.allChannels(metadata, 1, 1), rois.snapshot(), ReviewUiState.defaults(), Optional.empty());
        final var codec = new ReviewProjectCodec();
        final byte[] saved = codec.encode(project);
        final var header = codec.header(saved);
        assertEquals(session.state().basis().sourceSnapshot(), header.sourceSnapshot());
        final var restored = codec.decode(saved);
        assertEquals(project, restored);
        final var resumed = AlignmentReviewSession.restore(restored.alignment(),
                new ReviewAcceptanceVerification(header.sourceSnapshot(), header.atlas()));
        assertEquals(session.state().content(), resumed.state().content());
        assertFalse(resumed.canUndo()); assertTrue(resumed.acceptedAlignment().isEmpty());
        final String json = new String(saved, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(json.contains("@class")); assertFalse(json.contains("timeline"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json.replace("source.tif", "wrong.tif")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
