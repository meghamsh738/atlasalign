package org.atlasalign.application;

/**
 * Production adapters must recapture the source and re-open the verified atlas
 * inside this call immediately before acceptance.
 */
@FunctionalInterface
public interface ReviewAcceptanceVerifier {

    ReviewAcceptanceVerification verify();
}
