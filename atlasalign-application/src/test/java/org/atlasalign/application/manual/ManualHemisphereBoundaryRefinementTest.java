package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.core.Point2D;
import org.atlasalign.application.manual.ManualHemisphereWarp2D.AtlasSide;
import org.atlasalign.application.manual.ManualHemisphereWarp2D.MidlineSegment;
import org.junit.jupiter.api.Test;

/** Coordinates only from the September 2026 near-limit applied-border regression.
 * No source image or tissue pixels are included. */
class ManualHemisphereBoundaryRefinementTest {
    private static final double[][] BORDER = {
            {1091.1219796416513, 1633.4086026769, 1090.6185131911252, 1666.4313048936622},
            {929.0158233686345, 1714.977396344625, 955.93255106601, 1713.6413478872469},
            {759.0883683229027, 1802.5530311060197, 763.984749920194, 1831.023619198582},
            {568.4498828998819, 1850.8386429064008, 563.7607020478353, 1880.5272596881478},
            {413.3788284757198, 1785.947151733394, 395.7083483149842, 1809.3048507358408},
            {307.07081064841884, 1646.8075831703482, 281.91012950296135, 1661.7020319332632},
            {231.1303659316926, 1502.7441656466294, 203.25057770355517, 1518.1158022534398},
            {170.22077077353958, 1359.402742878242, 141.40295420084286, 1364.6067855027027},
            {101.79575083609996, 1215.7003227321889, 85.28437631540862, 1217.6297189280474},
            {78.46327957437984, 1074.1638868521309, 69.28078253880608, 1073.1928848906523},
            {55.43668230608817, 926.2596125007362, 48.061042150182395, 927.0405773990875},
            {69.68133494080098, 786.5281635090068, 65.7857554434396, 785.8424299812835},
            {113.98768669266013, 648.2407040279405, 99.63383704883461, 643.1966576404028},
            {203.3865871202388, 512.1192288128689, 181.67405120056134, 499.95000455553173},
            {330.3626114442503, 377.80274048612614, 318.08417398464775, 365.77553418400214},
            {502.1253104505529, 252.02007489671428, 493.991931395837, 234.51057839765156},
            {671.7468915028563, 170.8122786066554, 664.2320510286742, 150.25491335298534},
            {840.1449765814458, 115.07583620194112, 834.6951555470959, 99.78497024999587},
            {1007.9313136731782, 72.07507073989913, 1007.5134048520597, 61.72058343034479},
            {1174.494154791197, 54.54565916320172, 1173.8236820113582, 43.98000448987048},
            {1341.0569959092156, 37.016247586504306, 1341.0314201081565, 26.688156018146174},
            {1528.024993411095, 65.14469744215728, 1523.5264734185187, 69.11834150986112}
    };
    private static final double[][] GRID = {
            {880.0, 80.0},
            {400.0, 1840.0},
            {1520.0, 1200.0},
            {80.0, 800.0},
            {1600.0, 400.0},
            {800.0, 960.0},
            {1040.0, 1680.0},
            {400.0, 320.0},
            {320.0, 1280.0},
            {1120.0, 560.0},
            {1120.0, 1280.0},
            {720.0, 1360.0},
            {1280.0, 80.0},
            {720.0, 560.0},
            {1440.0, 800.0},
            {480.0, 800.0},
            {1360.0, 1520.0},
            {720.0, 1760.0},
            {1120.0, 880.0},
            {160.0, 480.0},
            {80.0, 1120.0},
            {560.0, 1120.0},
            {160.0, 1520.0},
            {480.0, 1520.0},
            {640.0, 160.0},
            {1520.0, 160.0},
            {960.0, 320.0},
            {1360.0, 480.0},
            {400.0, 560.0},
            {320.0, 1040.0},
            {1200.0, 320.0},
            {880.0, 720.0},
            {1280.0, 1040.0},
            {960.0, 1120.0},
            {960.0, 1440.0},
            {1040.0, 160.0},
            {800.0, 240.0},
            {1360.0, 240.0},
            {560.0, 400.0},
            {880.0, 480.0},
            {1520.0, 560.0},
            {240.0, 640.0},
            {560.0, 640.0},
            {1280.0, 640.0},
            {720.0, 800.0},
            {240.0, 880.0},
            {1520.0, 960.0},
            {800.0, 1200.0}
    };

    @Test
    void fortyEightUnmovedPointsPreserveTheNearLimitAppliedBorderEverywhere() {
        final var boundary = fit(boundaryControls());
        assertEquals(2.9998409638, boundary.diagnostics().maximumAnisotropy(), 1e-8);
        final var controls = withGrid(boundary);
        final var refined = fit(controls);
        final var repeated = fit(controls);
        assertEquals(refined.diagnostics().contentSha256(),
                repeated.diagnostics().contentSha256());
        assertEquals(boundary.diagnostics().maximumAnisotropy(),
                refined.diagnostics().maximumAnisotropy(), 1e-8);
        assertEquals(boundary.diagnostics().minimumJacobianDeterminant(),
                refined.diagnostics().minimumJacobianDeterminant(), 1e-8);
        for (int y = 0; y < 2048; y += 31) {
            for (int x = 0; x < 1922; x += 29) {
                final Point2D p = new Point2D(x + 0.125, y + 0.375);
                assertPoint(boundary.apply(AtlasSide.LEFT, p),
                        refined.apply(AtlasSide.LEFT, p), 1e-9);
                assertPoint(p, refined.inverse(AtlasSide.LEFT,
                        refined.apply(AtlasSide.LEFT, p)), 1e-6);
                assertEquals(p, refined.apply(AtlasSide.RIGHT, p));
            }
        }
        for (var control : controls) {
            assertPoint(control.targetPoint(), refined.apply(
                    AtlasSide.LEFT, control.sourcePoint()), 1e-9);
        }
    }

    @Test
    void aSmallInteriorDragIsExactLocalInvertibleAndKeepsBorderControls() {
        final var baseline = fit(boundaryControls());
        final var controls = withGrid(baseline);
        final int selected = BORDER.length + 5;
        final var old = controls.get(selected);
        final Point2D moved = new Point2D(old.targetPoint().x() + 1,
                old.targetPoint().y());
        controls.set(selected, new ManualWarpControl(old.id(), old.atlasSide(),
                old.origin(), old.groupId(), old.sourcePoint(), moved));
        final var refined = fit(controls);
        assertPoint(moved, refined.apply(AtlasSide.LEFT, old.sourcePoint()), 1e-9);
        assertPoint(old.sourcePoint(), refined.inverse(AtlasSide.LEFT, moved), 1e-6);
        for (var control : boundaryControls()) {
            assertPoint(control.targetPoint(), refined.apply(
                    AtlasSide.LEFT, control.sourcePoint()), 1e-9);
        }
        final Point2D distant = new Point2D(160, 480);
        assertPoint(baseline.apply(AtlasSide.LEFT, distant),
                refined.apply(AtlasSide.LEFT, distant), 1e-9);
        assertEquals(distant, refined.apply(AtlasSide.RIGHT, distant));
        assertTrue(refined.diagnostics().maximumAnisotropy() <= 3.0);
        assertTrue(refined.diagnostics().minimumJacobianDeterminant() >= 0.20);
        for (int y = 700; y < 1200; y += 17) {
            for (int x = 550; x < 1050; x += 19) {
                final Point2D p = new Point2D(x, y);
                assertPoint(p, refined.inverse(AtlasSide.LEFT,
                        refined.apply(AtlasSide.LEFT, p)), 1e-6);
            }
        }
        controls.set(selected, new ManualWarpControl(old.id(), old.atlasSide(),
                old.origin(), old.groupId(), old.sourcePoint(),
                new Point2D(old.targetPoint().x() + 700, old.targetPoint().y())));
        assertThrows(ManualWarpException.class, () -> fit(controls));
    }

    private static List<ManualWarpControl> boundaryControls() {
        final var controls = new ArrayList<ManualWarpControl>();
        for (int i = 0; i < BORDER.length; i++) {
            final double[] p = BORDER[i];
            controls.add(new ManualWarpControl("border-" + i, AtlasSide.LEFT,
                    ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR,
                    "outer-boundary", new Point2D(p[0], p[1]),
                    new Point2D(p[2], p[3])));
        }
        return controls;
    }

    private static List<ManualWarpControl> withGrid(ManualHemisphereWarp2D baseline) {
        final var controls = new ArrayList<>(boundaryControls());
        for (int i = 0; i < GRID.length; i++) {
            final Point2D p = new Point2D(GRID[i][0], GRID[i][1]);
            controls.add(new ManualWarpControl("grid-" + i, AtlasSide.LEFT,
                    ManualWarpControlOrigin.REGULAR_INTERIOR_GRID,
                    "regular-interior-grid", p, baseline.apply(AtlasSide.LEFT, p)));
        }
        return controls;
    }

    private static ManualHemisphereWarp2D fit(List<ManualWarpControl> controls) {
        return ManualHemisphereWarp2D.fit(controls,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ReviewSectionMode.HALF,
                new MidlineSegment(new Point2D(1640.669140631892, -6.034902237722662),
                        new Point2D(1543.095336728212, 2025.305570118508)), 1922, 2048);
    }

    private static void assertPoint(Point2D expected, Point2D actual, double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(expected.y(), actual.y(), tolerance);
    }
}
