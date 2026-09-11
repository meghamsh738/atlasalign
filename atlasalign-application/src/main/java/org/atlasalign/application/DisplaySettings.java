package org.atlasalign.application;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.atlasalign.core.SourceImageMetadata;

/** Display-only channel settings; never an export or registration specification. */
public record DisplaySettings(Mode mode, int selectedChannel, int slice, int frame,
        List<Channel> channels) {
    public enum Mode { SINGLE, COMPOSITE }
    public enum Lut {
        WHITE(0xffffff), GREEN(0x00ff00), MAGENTA(0xff00ff), CYAN(0x00ffff),
        RED(0xff0000), YELLOW(0xffff00), BLUE(0x4080ff);
        private final int rgb;
        Lut(final int rgb) { this.rgb = rgb; }
        public int rgb() { return rgb; }
    }
    public record Channel(int index, boolean visible, Lut lut,
            double minimum, double maximum, boolean automaticContrast) {
        public Channel {
            Objects.requireNonNull(lut, "lut");
            if (index < 1 || !Double.isFinite(minimum) || !Double.isFinite(maximum) || maximum <= minimum) {
                throw new IllegalArgumentException("Display contrast requires finite, increasing limits");
            }
        }
    }
    public DisplaySettings {
        Objects.requireNonNull(mode, "mode");
        channels = List.copyOf(channels);
        if (selectedChannel < 1 || selectedChannel > channels.size() || slice < 1 || frame < 1) {
            throw new IllegalArgumentException("Invalid display C/Z/T");
        }
        for (int index = 0; index < channels.size(); index++) {
            if (channels.get(index).index() != index + 1) {
                throw new IllegalArgumentException("Display settings must retain every source channel in order");
            }
        }
    }
    public static DisplaySettings defaults(final SourceImageMetadata source, final RegistrationInput registration) {
        registration.validateAgainst(source);
        return new DisplaySettings(Mode.SINGLE, registration.channel(), registration.slice(), registration.frame(),
                IntStream.rangeClosed(1, source.channels()).mapToObj(index -> new Channel(index, true,
                        Lut.values()[(index - 1) % Lut.values().length], 0, 1, true)).toList());
    }
    public void validateAgainst(final SourceImageMetadata source) {
        if (channels.size() != source.channels() || slice > source.slices() || frame > source.frames()) {
            throw new IllegalArgumentException("Display settings do not match the source image");
        }
    }
    public boolean inspectionMode(final RegistrationInput input) {
        return slice != input.slice() || frame != input.frame();
    }
}
