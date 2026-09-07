package org.atlasalign.deepslice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class DeepSliceInstallationRepositoryTest {

    private static final String WORKER = "print('unused')\n";

    @TempDir
    Path temporaryDirectory;

    @Test
    void opensOnlyACompleteTrustedInventory() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), WORKER);

        final VerifiedDeepSliceInstallation verified =
                new DeepSliceInstallationRepository().open(
                        installed.root(), installed.descriptor());

        assertEquals(installed.root().toAbsolutePath().normalize(),
                verified.root());
        assertEquals("test-1.2.8",
                verified.manifest().deepSliceVersion());
    }

    @Test
    void representsHistoricalProtocolV1ButRefusesItForTheV2CodePath()
            throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), WORKER, 1);

        assertEquals(1, installed.manifest().protocolVersion());
        assertEquals(1, installed.descriptor().protocolVersion());
        assertThrows(DeepSliceAdapterException.class,
                () -> new DeepSliceInstallationRepository().open(
                        installed.root(), installed.descriptor()));
    }

    @Test
    void rejectsAV1ManifestEvenWhenTheDescriptorClaimsV2()
            throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), WORKER, 1);
        final DeepSliceReleaseDescriptor descriptor =
                new DeepSliceReleaseDescriptor(
                        installed.descriptor().releaseId(),
                        installed.descriptor().manifestSizeBytes(),
                        installed.descriptor().manifestSha256(),
                        2,
                        installed.descriptor().pythonVersion(),
                        installed.descriptor().deepSliceVersion(),
                        installed.descriptor().tensorflowVersion(),
                        installed.descriptor().modelRelease(),
                        installed.descriptor().platformArchitecture());

        assertThrows(DeepSliceAdapterException.class,
                () -> new DeepSliceInstallationRepository().open(
                        installed.root(), descriptor));
    }

    @Test
    void rejectsChangedAsset() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), WORKER);
        Files.writeString(installed.worker(), "# changed\n");

        assertThrows(DeepSliceAdapterException.class,
                () -> new DeepSliceInstallationRepository().open(
                        installed.root(), installed.descriptor()));
    }

    @Test
    void rejectsChangedManifestEvenWhenItIsValidJson() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), WORKER);
        Files.writeString(
                installed.root().resolve(
                        DeepSliceInstallationRepository.MANIFEST_FILENAME),
                " {}\n");

        assertThrows(DeepSliceAdapterException.class,
                () -> new DeepSliceInstallationRepository().open(
                        installed.root(), installed.descriptor()));
    }

    @Test
    void rejectsUninventoriedFiles() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), WORKER);
        Files.writeString(installed.root().resolve("surprise.txt"), "extra");

        final DeepSliceAdapterException error = assertThrows(
                DeepSliceAdapterException.class,
                () -> new DeepSliceInstallationRepository().open(
                        installed.root(), installed.descriptor()));
        assertTrue(error.getMessage().contains("extra=[surprise.txt]"));
    }

    @Test
    void identifiesMissingInventoryFiles() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), WORKER);
        final String missing =
                installed.root().relativize(installed.worker()).toString();
        Files.delete(installed.worker());

        final DeepSliceAdapterException error = assertThrows(
                DeepSliceAdapterException.class,
                () -> new DeepSliceInstallationRepository().open(
                        installed.root(), installed.descriptor()));
        assertTrue(error.getMessage().contains(
                "missing=[" + missing + "]"));
    }

    @Test
    void rejectsSymbolicLinks() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), WORKER);
        createSymbolicLinkOrSkip(
                installed.root().resolve("unsafe-link"),
                installed.worker());

        assertThrows(DeepSliceAdapterException.class,
                () -> new DeepSliceInstallationRepository().open(
                        installed.root(), installed.descriptor()));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Hard-link detection uses the Unix nlink attribute")
    void rejectsHardLinkedAssets() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), WORKER);
        Files.createLink(
                temporaryDirectory.resolve("second-link"),
                installed.worker());

        assertThrows(DeepSliceAdapterException.class,
                () -> new DeepSliceInstallationRepository().open(
                        installed.root(), installed.descriptor()));
    }

    @Test
    void rejectsSymlinkedAncestors() throws IOException {
        final Path realParent = temporaryDirectory.resolve("real-parent");
        final var installed = DeepSliceTestInstallation.create(
                realParent.resolve("runtime"), WORKER);
        final Path alias = temporaryDirectory.resolve("alias-parent");
        createSymbolicLinkOrSkip(alias, realParent);

        assertThrows(DeepSliceAdapterException.class,
                () -> new DeepSliceInstallationRepository().open(
                        alias.resolve("runtime"),
                        installed.descriptor()));
    }

    @Test
    void rejectsDescriptorVersionMismatch() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), WORKER);
        final DeepSliceReleaseDescriptor wrong =
                new DeepSliceReleaseDescriptor(
                        "wrong-version",
                        installed.descriptor().manifestSizeBytes(),
                        installed.descriptor().manifestSha256(),
                        installed.descriptor().protocolVersion(),
                        installed.descriptor().pythonVersion(),
                        "wrong-deepslice",
                        installed.descriptor().tensorflowVersion(),
                        installed.descriptor().modelRelease(),
                        installed.descriptor().platformArchitecture());

        assertThrows(DeepSliceAdapterException.class,
                () -> new DeepSliceInstallationRepository().open(
                        installed.root(), wrong));
    }

    @Test
    void rejectsTraversalAndAmbiguousRelativePaths() {
        for (final String path : new String[] {
                "../worker.py", "bin/../worker.py", "./worker.py",
                "bin//worker.py", "worker.py/", "C:\\worker.py"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DeepSliceFileAsset(
                            path, 1, "a".repeat(64),
                            DeepSliceAssetRole.WORKER_SCRIPT, false));
        }
    }
    private static void createSymbolicLinkOrSkip(final Path link, final Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.AccessDeniedException unavailable) {
            Assumptions.assumeTrue(false, "Symbolic links are unsupported or not permitted: " + unavailable);
        } catch (java.nio.file.FileSystemException failure) {
            final String reason = String.valueOf(failure.getReason()).toLowerCase(java.util.Locale.ROOT);
            if (reason.contains("privilege") || reason.contains("not supported")) {
                Assumptions.assumeTrue(false, "Symbolic links are unsupported or not permitted: " + failure);
            }
            throw failure;
        }
    }

}
