package org.atlasalign.deepslice;

import java.util.List;
import org.atlasalign.application.DeepSliceUnavailableException;

/**
 * Wraps an argv-only worker command in an operating-system network boundary.
 */
@FunctionalInterface
public interface NetworkIsolation {

    List<String> isolate(List<String> command)
            throws DeepSliceUnavailableException;
}
