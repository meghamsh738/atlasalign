package org.atlasalign.application.c01;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.AllenCoronalLevel;

/** A feature grid bound to one immutable C01 context and scientific role. */
public record C01BoundFeatureGrid(
        C01SearchContext context,
        Role role,
        Optional<AllenCoronalLevel> atlasLevel,
        C01FeatureGrid grid) {

    public enum Role {
        TISSUE,
        ATLAS
    }

    public C01BoundFeatureGrid {
        context = Objects.requireNonNull(context, "context");
        role = Objects.requireNonNull(role, "role");
        atlasLevel = Objects.requireNonNull(atlasLevel, "atlasLevel");
        grid = Objects.requireNonNull(grid, "grid");
        if ((role == Role.ATLAS) != atlasLevel.isPresent()) {
            throw new IllegalArgumentException(
                    "Only an atlas feature grid carries an AP level");
        }
    }

    public static C01BoundFeatureGrid tissue(
            final C01SearchContext context,
            final C01FeatureGrid grid) {
        return new C01BoundFeatureGrid(
                context, Role.TISSUE, Optional.empty(), grid);
    }

    public static C01BoundFeatureGrid atlas(
            final C01SearchContext context,
            final AllenCoronalLevel level,
            final C01FeatureGrid grid) {
        return new C01BoundFeatureGrid(
                context,
                Role.ATLAS,
                Optional.of(Objects.requireNonNull(level, "level")),
                grid);
    }

    public String contextSha256() {
        return context.identitySha256();
    }
}
