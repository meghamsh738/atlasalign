package org.atlasalign.plugin.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import java.nio.file.Path;
import java.util.List;
import org.atlasalign.core.Point2D;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchProjectSessionTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void queueOrderSettingsAndStatusAreAutosaved() throws Exception {
        final ImagePlus source = new ImagePlus("slide.tif",
                new ByteProcessor(20, 20));
        final var snapshot = new ImagePlusSourceImage(source).snapshot();
        final BatchProjectSession project = new BatchProjectSession(
                "test batch", temporaryDirectory.resolve("project"),
                List.of(item(source, snapshot, "one", "Section 1", 250),
                        item(source, snapshot, "two", "Section 2", 251)));

        project.renameSelected("Anterior");
        project.setSelectedCoronalLevel(245);
        project.setStatus("one", BatchReviewStatus.REVIEW_OPEN,
                "Window opened");
        project.moveSelected(1);

        assertEquals("two", project.items().get(0).section().id());
        assertEquals("Anterior", project.items().get(1).section().name());
        final var json = new ObjectMapper().readTree(
                project.projectFile().toFile());
        assertEquals("batch-project-v2.json", project.projectFile().getFileName().toString());
        assertEquals("atlasalign-review-batch-v2",
                json.path("schema").asText());
        assertEquals("two", json.path("sections").get(0)
                .path("id").asText());
        assertEquals("Anterior", json.path("sections").get(1)
                .path("name").asText());
        assertEquals(245, json.path("sections").get(1)
                .path("initialCoronalLevel").asInt());
        assertEquals("REVIEW_OPEN", json.path("sections").get(1)
                .path("status").asText());
        assertTrue(project.nextPendingIndex().isPresent());
    }

    private static BatchReviewItem item(
            final ImagePlus source,
            final org.atlasalign.core.SourceImageSnapshot snapshot,
            final String id,
            final String name,
            final int level) {
        final BatchSection section = new BatchSection(id, name,
                source.getTitle(), snapshot.pixelSha256(), 20, 20,
                0, 0, 20, 20,
                List.of(new Point2D(0, 0), new Point2D(20, 0),
                        new Point2D(20, 20), new Point2D(0, 20)),
                level, BatchReviewStatus.PENDING, "");
        return new BatchReviewItem(source, snapshot, section);
    }
}
