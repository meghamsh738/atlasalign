package org.atlasalign.deepslice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class DeepSliceIntegrity {

    private DeepSliceIntegrity() {
    }

    static String sha256(final Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return sha256(input);
        }
    }

    static String sha256(final byte[] bytes) {
        return HexFormat.of().formatHex(
                digest().digest(bytes));
    }

    private static String sha256(final InputStream input)
            throws IOException {
        final MessageDigest digest = digest();
        final byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "Java runtime has no SHA-256 provider", impossible);
        }
        return digest;
    }
}
