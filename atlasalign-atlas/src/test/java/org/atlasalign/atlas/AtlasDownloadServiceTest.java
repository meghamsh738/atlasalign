package org.atlasalign.atlas;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtlasDownloadServiceTest {
    @TempDir Path temp;

    private static final byte[] ONTOLOGY = ("{\"success\":true,\"msg\":[{\"id\":997,\"name\":\"root\","
            + "\"acronym\":\"root\",\"parent_structure_id\":null,\"hemisphere_id\":3,\"children\":[]}]}")
            .getBytes(StandardCharsets.UTF_8);

    @Test void installsIntoPathWithSpacesAndReusesVerifiedCacheOffline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final Path target = temp.resolve("atlas with spaces");
            var verified = fixture.service().install(target, () -> false, ignored -> { });
            assertEquals(1, verified.ontology().size());
            assertTrue(Files.isRegularFile(target.resolve("manifest.json")));
            assertEquals(3, fixture.requests.get());
            fixture.server.stop(0);
            assertEquals(target, fixture.service().install(target, () -> false, ignored -> { }).cacheDirectory());
            assertEquals(3, fixture.requests.get());
        }
    }

    @Test void resumesAfterCancellationAndDoesNotPublishPartialCache() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final Path target = temp.resolve("cancelled");
            final AtomicBoolean cancelled = new AtomicBoolean();
            assertThrows(CancellationException.class, () -> fixture.service().install(target, cancelled::get,
                    progress -> { if (progress.completedBytes() > 0) cancelled.set(true); }));
            assertFalse(Files.exists(target));
            assertTrue(Files.exists(temp.resolve("cancelled.download/template.nrrd")));
            cancelled.set(false);
            fixture.service().install(target, cancelled::get, ignored -> { });
            assertTrue(fixture.ranges.get() >= 1);
            assertTrue(Files.exists(target.resolve("manifest.json")));
        }
    }

    @Test void interruptedTransferResumesAndIgnoredRangeRestartsSafely() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final Path target = temp.resolve("interrupted");
            fixture.interrupt.set(true);
            assertThrows(IOException.class, () -> fixture.service().install(target, () -> false, ignored -> { }));
            assertFalse(Files.exists(target));
            fixture.interrupt.set(false);
            fixture.ignoreRange.set(true);
            fixture.service().install(target, () -> false, ignored -> { });
            assertTrue(fixture.ranges.get() > 0);
            assertEquals(fixture.manifest.asset("template").sha256(), AtlasIntegrity.sha256(target.resolve("template.nrrd")));
        }
    }

    @Test void rejectsCorruptBytesThenCanRetryWithoutReplacingExistingCaches() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final Path target = temp.resolve("corrupt");
            fixture.corrupt.set(true);
            assertThrows(IOException.class, () -> fixture.service().install(target, () -> false, ignored -> { }));
            assertFalse(Files.exists(target));
            fixture.corrupt.set(false);
            fixture.service().install(target, () -> false, ignored -> { });
            Files.writeString(target.resolve("template.nrrd"), "user modified");
            int requests = fixture.requests.get();
            assertThrows(AtlasCacheException.class, () -> fixture.service().install(target, () -> false, ignored -> { }));
            assertEquals("user modified", Files.readString(target.resolve("template.nrrd")));
            assertEquals(requests, fixture.requests.get());
        }
    }

    @Test void rejectsWrongResumeRangeAndNeverPublishes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            final Path target = temp.resolve("bad range");
            Files.createDirectories(temp.resolve("bad range.download"));
            Files.write(temp.resolve("bad range.download/template.nrrd"), new byte[10]);
            fixture.badRange.set(true);
            assertThrows(IOException.class, () -> fixture.service().install(target, () -> false, ignored -> { }));
            assertFalse(Files.exists(target));
        }
    }

    private static class Fixture implements AutoCloseable {
        final HttpServer server;
        final Map<String, byte[]> data = new LinkedHashMap<>();
        final AtlasManifest manifest;
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger ranges = new AtomicInteger();
        final AtomicBoolean interrupt = new AtomicBoolean();
        final AtomicBoolean corrupt = new AtomicBoolean();
        final AtomicBoolean ignoreRange = new AtomicBoolean();
        final AtomicBoolean badRange = new AtomicBoolean();
        Fixture() throws Exception {
            final byte[] template = new byte[512 * 1024];
            new Random(42).nextBytes(template);
            data.put("template.nrrd", template);
            data.put("annotation.nrrd", "annotation".getBytes(StandardCharsets.UTF_8));
            data.put("ontology.json", ONTOLOGY);
            final List<AtlasAsset> assets = new ArrayList<>();
            final String[] roles = {"template", "annotation", "ontology"};
            int i = 0;
            for (var entry : data.entrySet()) assets.add(new AtlasAsset(roles[i++], entry.getKey(),
                    URI.create("https://fixture.invalid/" + entry.getKey()), entry.getValue().length,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue()))));
            manifest = new AtlasManifest(1, "test", "test", 25, "test", List.of(1,1,1),
                    "https://example.invalid/terms", "Test fixture", assets);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                final byte[] bytes = data.get(exchange.getRequestURI().getPath().substring(1)).clone();
                if (corrupt.get()) bytes[0] ^= 1;
                final String range = exchange.getRequestHeaders().getFirst("Range");
                int offset = 0;
                if (range != null) {
                    ranges.incrementAndGet();
                    if (!ignoreRange.get()) offset = Integer.parseInt(range.substring(6, range.length() - 1));
                }
                if (offset > 0) exchange.getResponseHeaders().add("Content-Range",
                        "bytes " + (badRange.get() ? 0 : offset) + "-" + (bytes.length - 1) + "/" + bytes.length);
                exchange.sendResponseHeaders(offset > 0 ? 206 : 200, bytes.length - offset);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes, offset, interrupt.get() ? Math.min(12000, bytes.length - offset) : bytes.length - offset);
                } catch (IOException intentionallyInterrupted) { /* cancellation closes the peer */ }
            });
            server.start();
        }
        AtlasDownloadService service() {
            return new AtlasDownloadService(manifest, original -> URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + original.getPath()));
        }
        @Override public void close() { server.stop(0); }
    }
}
