package org.atlasalign.atlas;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class AtlasIntegrity {

    private AtlasIntegrity() {
    }

    static String sha256(final Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "Java runtime has no SHA-256 provider", impossible);
        }
        final byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(path)) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    digest.update(buffer, 0, count);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
