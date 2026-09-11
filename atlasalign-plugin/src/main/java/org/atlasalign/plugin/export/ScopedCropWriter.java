package org.atlasalign.plugin.export;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.export.SourcePixelReader;

/** Keeps the explicit-plane contract separate from historical all-plane APIs. */
final class ScopedCropWriter {
    private ScopedCropWriter() { }
    static BioFormatsOmeTiffExporter.WriteReport write(
            final BioFormatsOmeTiffExporter writer, final boolean masked,
            final Path destination, final String name, final SourcePixelReader source,
            final SourceRegionFootprint footprint, final Optional<ExportSelection> selection,
            final BooleanSupplier cancelled, final DoubleConsumer progress) {
        if (selection.isPresent()) {
            return masked ? writer.writeMaskedSourceCrop(destination, name, source,
                    footprint, selection.orElseThrow(), cancelled, progress)
                    : writer.writeSourceCrop(destination, name, source, footprint,
                            selection.orElseThrow(), cancelled, progress);
        }
        return masked ? writer.writeMaskedSourceCrop(destination, name, source,
                footprint, cancelled, progress) : writer.writeSourceCrop(destination,
                        name, source, footprint, cancelled, progress);
    }
}
