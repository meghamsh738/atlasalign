package org.atlasalign.abba;

/**
 * Read-only result describing whether the pinned ABBA adapter surface is
 * available and compatible with the methods AtlasAlign Lite requires.
 */
public record AbbaAvailability(boolean available, String detail) {
}
