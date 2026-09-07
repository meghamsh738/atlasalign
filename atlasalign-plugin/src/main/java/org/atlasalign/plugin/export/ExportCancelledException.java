package org.atlasalign.plugin.export;

/** Internal control-flow exception; cancellation publishes no output. */
public final class ExportCancelledException extends RuntimeException {
    public ExportCancelledException() {
        super("Export cancelled; no files were published");
    }
}
