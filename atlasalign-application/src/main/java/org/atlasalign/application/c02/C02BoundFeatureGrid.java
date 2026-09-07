package org.atlasalign.application.c02;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.AllenCoronalLevel;

/** One immutable feature grid bound to its C02 role and exact context. */
public record C02BoundFeatureGrid(
        C02SearchContext context,
        Role role,
        Optional<AllenCoronalLevel> atlasLevel,
        C02FeatureGrid grid) {

    public enum Role {
        TISSUE,
        ATLAS
    }

    public C02BoundFeatureGrid {
        context = Objects.requireNonNull(context, "context");
        role = Objects.requireNonNull(role, "role");
        atlasLevel = Objects.requireNonNull(atlasLevel, "atlasLevel");
        grid = Objects.requireNonNull(grid, "grid");
        if ((role == Role.ATLAS) != atlasLevel.isPresent()) {
            throw new IllegalArgumentException(
                    "Only a C02 atlas feature grid carries an AP level");
        }
    }

    public static C02BoundFeatureGrid tissue(
            final C02SearchContext context,
            final C02FeatureGrid grid) {
        return new C02BoundFeatureGrid(
                context, Role.TISSUE, Optional.empty(), grid);
    }

    public static C02BoundFeatureGrid atlas(
            final C02SearchContext context,
            final AllenCoronalLevel level,
            final C02FeatureGrid grid) {
        return new C02BoundFeatureGrid(
                context,
                Role.ATLAS,
                Optional.of(Objects.requireNonNull(level, "level")),
                grid);
    }

    public String contextSha256() {
        return context.identitySha256();
    }
}
