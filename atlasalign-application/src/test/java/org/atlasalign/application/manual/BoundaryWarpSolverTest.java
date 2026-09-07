package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ManualWarpPrecondition;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class BoundaryWarpSolverTest {

    private static final int WIDTH = 400;
    private static final int HEIGHT = 300;
    private static final String HASH = "a".repeat(64);
    private static final ManualHemisphereWarp2D.MidlineSegment MIDLINE =
            new ManualHemisphereWarp2D.MidlineSegment(
                    new Point2D(199.5, 0), new Point2D(199.5, 299));

    @Test
    void manualPairsMapExactlyWhileInfluenceStaysLocalAndOppositeSideIsIdentity() {
        final List<BoundaryFitMatch> matches = manualMatches();

        final BoundaryWarpCandidate candidate = new BoundaryWarpSolver()
                .solve(request(matches, List.of(), Optional.empty()));

        assertEquals(matches.size(), candidate.includedMatchCount());
        for (final ManualWarpControl control
                : candidate.replacementControls()) {
            assertPoint(control.targetPoint(), candidate.validatedWarp()
                    .apply(ManualHemisphereWarp2D.AtlasSide.LEFT,
                            control.sourcePoint()), 1e-9);
            assertEquals(ManualWarpControlOrigin
                    .ATLAS_TISSUE_BOUNDARY_PAIR, control.origin());
            assertEquals(BoundaryWarpSolver.GROUP_ID, control.groupId());
        }
        final Point2D deepInterior = new Point2D(155, 150);
        assertPoint(deepInterior, candidate.validatedWarp().apply(
                ManualHemisphereWarp2D.AtlasSide.LEFT, deepInterior), 1e-9);
        final Point2D oppositeSide = new Point2D(310, 150);
        assertPoint(oppositeSide, candidate.validatedWarp().apply(
                ManualHemisphereWarp2D.AtlasSide.RIGHT, oppositeSide), 1e-9);
        assertNotEquals(candidate.replacementControls().get(2).sourcePoint(),
                candidate.replacementControls().get(2).targetPoint());
    }

    @Test
    void replacingBorderGroupRetainsExistingInteriorControlsExactly() {
        final List<ManualWarpControl> interior = identityInteriorControls();
        final ManualHemisphereWarp2D prior = ManualHemisphereWarp2D.fit(
                interior,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ReviewSectionMode.FULL, MIDLINE, WIDTH, HEIGHT);

        final BoundaryWarpCandidate candidate = new BoundaryWarpSolver()
                .solve(request(manualMatches(), interior,
                        Optional.of(prior)));

        assertEquals(interior.size() + manualMatches().size(),
                candidate.validatedWarp().controls().size());
        assertTrue(candidate.validatedWarp().controls()
                .containsAll(interior));
        for (final ManualWarpControl control : interior) {
            assertPoint(control.targetPoint(), candidate.validatedWarp()
                    .apply(control.atlasSide(), control.sourcePoint()), 1e-9);
        }

        final List<BoundaryFitMatch> replacementMatches = manualMatches()
                .stream().map(match -> match.withTissuePoint(new Point2D(
                        match.tissuePreviewPoint().x() - 1,
                        match.tissuePreviewPoint().y())))
                .toList();
        final BoundaryWarpRequest next = request(replacementMatches,
                candidate.validatedWarp().controls(),
                Optional.of(candidate.validatedWarp()));
        final BoundaryWarpCandidate replacement = new BoundaryWarpSolver()
                .solve(next);

        assertEquals(interior.size() + replacementMatches.size(),
                replacement.validatedWarp().controls().size(),
                "Set Border must replace, not append, its group");
        assertEquals(replacementMatches.size(), replacement.validatedWarp()
                .controls().stream().filter(control -> control.groupId()
                        .equals(BoundaryWarpSolver.GROUP_ID)).count());
    }

    @Test
    void suggestionAndSolveAreDeterministic() {
        final BoundaryWarpSolver solver = new BoundaryWarpSolver();
        final BoundaryWarpRequest empty = request(
                List.of(), List.of(), Optional.empty());

        final BoundaryWarpRequest firstSuggestion = solver.suggest(empty);
        final BoundaryWarpRequest secondSuggestion = solver.suggest(empty);
        final BoundaryWarpCandidate first = solver.solve(firstSuggestion);
        final BoundaryWarpCandidate second = solver.solve(secondSuggestion);

        assertEquals(firstSuggestion.matches(), secondSuggestion.matches());
        assertTrue(first.includedMatchCount() >= 4);
        assertTrue(first.includedMatchCount() <= 24);
        assertEquals(first.inputHash(), second.inputHash());
        assertEquals(first.validatedWarp().diagnostics().contentSha256(),
                second.validatedWarp().diagnostics().contentSha256());
    }

    @Test
    void supportsEveryDenseBoundaryChoiceAndKeepsAmbiguousPointsVisible() {
        final List<Point2D> atlas = ellipsePoints(
                new Point2D(105, 150), 78, 122, 96);
        final List<Point2D> nearTissue = shifted(atlas, -1, 1);
        final BoundaryWarpSolver solver = new BoundaryWarpSolver();
        for (final int density : List.of(4, 6, 8, 12, 16, 24, 36, 48)) {
            final BoundaryWarpRequest suggested = solver.suggest(requestFor(
                    ManualHemisphereWarp2D.AtlasSide.LEFT,
                    AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                    ReviewSectionMode.FULL, atlas, nearTissue, List.of(),
                    List.of(), Optional.empty()), density);
            assertEquals(density, suggested.matches().size(),
                    "density=" + density);
            assertEquals(density, suggested.matches().stream()
                    .map(BoundaryFitMatch::tissuePreviewPoint)
                    .distinct().count(),
                    "dense monotonic suggestions must not reuse a tissue endpoint at density="
                            + density);
        }

        final List<Point2D> farTissue = shifted(atlas, 190, 0);
        final BoundaryWarpRequest ambiguous = solver.suggest(requestFor(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ReviewSectionMode.FULL, atlas, farTissue, List.of(),
                List.of(), Optional.empty()), 24);
        assertEquals(24, ambiguous.matches().size());
        assertTrue(ambiguous.matches().stream()
                .anyMatch(match -> !match.included()),
                "ambiguous suggestions stay visible instead of disappearing");
    }

    @Test
    void firstFinitePairProducesAnUnappliedExactEndpointPreview() {
        final BoundaryFitMatch only = match("one",
                new Point2D(100, 80), new Point2D(102, 79));
        final BoundaryWarpPreview preview = new BoundaryWarpSolver().preview(
                request(List.of(only), List.of(), Optional.empty()));

        assertPoint(only.tissuePreviewPoint(), preview.field().apply(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                only.atlasPreviewPoint()), 1e-9);
        final Point2D nearby = new Point2D(
                only.atlasPreviewPoint().x() + 8,
                only.atlasPreviewPoint().y());
        final Point2D mappedNearby = preview.field().apply(
                ManualHemisphereWarp2D.AtlasSide.LEFT, nearby);
        final double endpointDisplacement = Math.hypot(
                only.tissuePreviewPoint().x()
                        - only.atlasPreviewPoint().x(),
                only.tissuePreviewPoint().y()
                        - only.atlasPreviewPoint().y());
        final double nearbyDisplacement = Math.hypot(
                mappedNearby.x() - nearby.x(),
                mappedNearby.y() - nearby.y());
        assertTrue(nearbyDisplacement > 0
                && nearbyDisplacement < endpointDisplacement,
                "a one-pair ghost must taper inside its compact support");
        final Point2D opposite = new Point2D(170, 120);
        assertPoint(opposite, preview.field().apply(
                ManualHemisphereWarp2D.AtlasSide.RIGHT, opposite), 0);
        assertEquals(1, preview.request().matches().size());
    }

    @Test
    void reflectedAndDisjoinedRequestsKeepAnatomicalSidesIndependent() {
        final List<Point2D> reflectedAtlas = mirrored(atlasPoints());
        final List<Point2D> reflectedTissue = mirrored(tissuePoints());
        final BoundaryWarpCandidate reflected = new BoundaryWarpSolver()
                .solve(requestFor(
                        ManualHemisphereWarp2D.AtlasSide.LEFT,
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT,
                        ReviewSectionMode.FULL,
                        reflectedAtlas, reflectedTissue,
                        matches(reflectedAtlas, reflectedTissue),
                        List.of(), Optional.empty()));

        for (final ManualWarpControl control
                : reflected.replacementControls()) {
            assertEquals(ManualHemisphereWarp2D.AtlasSide.LEFT,
                    control.atlasSide());
            assertPoint(control.targetPoint(), reflected.validatedWarp()
                    .apply(ManualHemisphereWarp2D.AtlasSide.LEFT,
                            control.sourcePoint()), 1e-9);
        }
        final Point2D reflectedOpposite = new Point2D(80, 150);
        assertPoint(reflectedOpposite, reflected.validatedWarp().apply(
                ManualHemisphereWarp2D.AtlasSide.RIGHT,
                reflectedOpposite), 1e-9);

        final List<Point2D> disjoinedTissue = shifted(
                reflectedAtlas, 1.0, 0.0);
        final BoundaryWarpCandidate disjoined = new BoundaryWarpSolver()
                .solve(requestFor(
                        ManualHemisphereWarp2D.AtlasSide.RIGHT,
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ReviewSectionMode.DISJOINED,
                        reflectedAtlas, disjoinedTissue,
                        matches(reflectedAtlas, disjoinedTissue),
                        List.of(), Optional.empty()));

        for (final ManualWarpControl control
                : disjoined.replacementControls()) {
            assertEquals(ManualHemisphereWarp2D.AtlasSide.RIGHT,
                    control.atlasSide());
            assertPoint(control.targetPoint(), disjoined.validatedWarp()
                    .apply(ManualHemisphereWarp2D.AtlasSide.RIGHT,
                            control.sourcePoint()), 1e-9);
        }
        final Point2D disjoinedOpposite = new Point2D(90, 150);
        assertPoint(disjoinedOpposite, disjoined.validatedWarp().apply(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                disjoinedOpposite), 1e-9);
    }

    @Test
    void rejectsClusteredPairsAndExcessiveMovement() {
        final List<BoundaryFitMatch> clustered = List.of(
                match("cluster-1", new Point2D(90, 28),
                        new Point2D(90, 24)),
                match("cluster-2", new Point2D(95, 29),
                        new Point2D(95, 25)),
                match("cluster-3", new Point2D(100, 30),
                        new Point2D(100, 26)),
                match("cluster-4", new Point2D(105, 31),
                        new Point2D(105, 27)));
        final ManualWarpException coverage = assertThrows(
                ManualWarpException.class,
                () -> new BoundaryWarpSolver().solve(request(
                        clustered, List.of(), Optional.empty())));
        assertEquals(ManualWarpFailureKind.INVALID_CONTROL,
                coverage.kind());

        final List<BoundaryFitMatch> unsafe = new ArrayList<>(
                manualMatches());
        final BoundaryFitMatch first = unsafe.get(3);
        unsafe.set(3, first.withTissuePoint(new Point2D(195, 150)));
        final ManualWarpException displacement = assertThrows(
                ManualWarpException.class,
                () -> new BoundaryWarpSolver().solve(request(
                        unsafe, List.of(), Optional.empty())));
        assertEquals(ManualWarpFailureKind.EXCESSIVE_DISPLACEMENT,
                displacement.kind());
    }

    @Test
    void retainsLargeRequestedEndpointsAndFindsDeterministicSafeStep() {
        final List<BoundaryFitMatch> unsafe = new ArrayList<>(
                manualMatches());
        unsafe.set(3, unsafe.get(3).withTissuePoint(
                new Point2D(195, 150)));
        final BoundaryWarpSolver solver = new BoundaryWarpSolver();

        final BoundaryWarpCandidate first = solver.solveSafestStep(
                request(unsafe, List.of(), Optional.empty()));
        final BoundaryWarpCandidate second = solver.solveSafestStep(
                request(unsafe, List.of(), Optional.empty()));

        assertTrue(first.safeStepFraction() < 1);
        assertTrue(first.safeStepFraction()
                >= BoundaryWarpSolver.MINIMUM_SAFE_STEP_FRACTION);
        assertEquals(unsafe, first.matches(),
                "the reviewer-requested dots remain editable");
        assertNotEquals(unsafe, first.auditedMatches(),
                "the installed step records its separately audited endpoints");
        assertEquals(first.safeStepFraction(), second.safeStepFraction());
        assertEquals(first.inputHash(), second.inputHash());
        assertTrue(first.limitingSafetyReport().isPresent());
        assertNotEquals(ManualWarpSafetyGate.UNKNOWN,
                first.limitingSafetyReport().orElseThrow().gate(),
                "a partial step must name the gate that limited the full request");
        for (final ManualWarpControl control : first.replacementControls()) {
            assertPoint(control.targetPoint(), first.validatedWarp().apply(
                    control.atlasSide(), control.sourcePoint()), 1e-9);
        }
    }

    @Test
    void repeatedSafeStepsRebaseOnTheExactlyInstalledBoundary() {
        final List<BoundaryFitMatch> requested = new ArrayList<>(
                manualMatches());
        requested.set(3, requested.get(3).withTissuePoint(
                new Point2D(195, 150)));
        final BoundaryWarpSolver solver = new BoundaryWarpSolver();
        final BoundaryWarpCandidate first = solver.solveSafestStep(
                request(requested, List.of(), Optional.empty()));
        final BoundaryWarpRequest unchangedRemainder = request(requested,
                first.validatedWarp().controls(),
                Optional.of(first.validatedWarp()));
        final ManualWarpException noFurtherFraction = assertThrows(
                ManualWarpException.class,
                () -> solver.solveSafestStep(unchangedRemainder));
        assertEquals(ManualWarpSafetyGate.DISPLACEMENT,
                noFurtherFraction.safetyReport().orElseThrow().gate(),
                "a cumulative displacement limit cannot be bypassed by repeated clicks");

        final List<BoundaryFitMatch> nextRequested = new ArrayList<>(
                first.auditedMatches());
        nextRequested.set(2, nextRequested.get(2).withTissuePoint(
                new Point2D(nextRequested.get(2).tissuePreviewPoint().x() - 1,
                        nextRequested.get(2).tissuePreviewPoint().y())));
        final BoundaryWarpRequest rebased = request(nextRequested,
                first.validatedWarp().controls(),
                Optional.of(first.validatedWarp()));

        final BoundaryWarpCandidate second = solver.solveSafestStep(rebased);
        final BoundaryWarpCandidate replay = solver.solveSafestStep(rebased);

        assertEquals(second.safeStepFraction(), replay.safeStepFraction());
        assertEquals(second.inputHash(), replay.inputHash());
        assertEquals(second.auditedMatches(), replay.auditedMatches());
        for (final BoundaryWarpBaselinePoint baseline
                : rebased.baselinePoints()) {
            final ManualWarpControl installed = first.replacementControls()
                    .stream().filter(control -> control.id().equals(
                            baseline.controlId())).findFirst().orElseThrow();
            assertPoint(installed.sourcePoint(), baseline.sourcePoint(), 0);
            assertPoint(installed.targetPoint(), baseline.targetPoint(), 0);
            final BoundaryFitMatch wanted = nextRequested.stream()
                    .filter(match -> match.id().equals(baseline.matchId()))
                    .findFirst().orElseThrow();
            final BoundaryFitMatch audited = second.auditedMatches().stream()
                    .filter(match -> match.id().equals(baseline.matchId()))
                    .findFirst().orElseThrow();
            assertPoint(new Point2D(
                    baseline.targetPoint().x()
                            + second.safeStepFraction()
                            * (wanted.tissuePreviewPoint().x()
                            - baseline.targetPoint().x()),
                    baseline.targetPoint().y()
                            + second.safeStepFraction()
                            * (wanted.tissuePreviewPoint().y()
                            - baseline.targetPoint().y())),
                    audited.tissuePreviewPoint(), 1e-12);
        }
    }

    @Test
    void invalidBaselineRetainsTheUnderlyingDiagnosticCause() {
        final List<BoundaryFitMatch> duplicated = new ArrayList<>(manualMatches());
        duplicated.set(1, duplicated.get(1).withAtlasPoint(
                duplicated.get(0).atlasPreviewPoint()));
        final ManualWarpException rejected = assertThrows(ManualWarpException.class,
                () -> new BoundaryWarpSolver().solveSafestStep(
                        request(duplicated, List.of(), Optional.empty())));
        assertEquals(ManualWarpSafetyGate.CONTROL_TOPOLOGY,
                rejected.safetyReport().orElseThrow().gate());
        assertTrue(rejected.getCause() instanceof RuntimeException,
                "retain the underlying numerical/input cause for diagnosis");
        assertTrue(!rejected.technicalDetail().isBlank());
    }

    @Test
    void sameDensityResuggestionUsesTheExactInstalledOuterGroupAsBaseline() {
        final BoundaryWarpSolver solver = new BoundaryWarpSolver();
        final BoundaryWarpCandidate installed = solver.solve(
                request(manualMatches(), List.of(), Optional.empty()));
        final BoundaryWarpRequest fresh = request(List.of(),
                installed.validatedWarp().controls(),
                Optional.of(installed.validatedWarp()));

        final BoundaryWarpRequest resuggested = solver.suggest(fresh,
                installed.replacementControls().size());

        final List<ManualWarpControl> controls = installed
                .replacementControls().stream()
                .sorted(java.util.Comparator.comparing(
                        ManualWarpControl::id)).toList();
        final List<BoundaryWarpBaselinePoint> baseline = resuggested
                .baselinePoints();
        assertEquals(controls.size(), baseline.size());
        for (int index = 0; index < controls.size(); index++) {
            assertEquals(controls.get(index).id(),
                    baseline.get(index).controlId());
            assertPoint(controls.get(index).sourcePoint(),
                    baseline.get(index).sourcePoint(), 0);
            assertPoint(controls.get(index).targetPoint(),
                    baseline.get(index).targetPoint(), 0);
        }
    }

    private static BoundaryWarpRequest request(
            final List<BoundaryFitMatch> matches,
            final List<ManualWarpControl> priorControls,
            final Optional<ManualHemisphereWarp2D> priorWarp) {
        return requestFor(ManualHemisphereWarp2D.AtlasSide.LEFT,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ReviewSectionMode.FULL, atlasPoints(), tissuePoints(), matches,
                priorControls, priorWarp);
    }

    private static BoundaryWarpRequest requestFor(
            final ManualHemisphereWarp2D.AtlasSide side,
            final AtlasOrientation orientation,
            final ReviewSectionMode sectionMode,
            final List<Point2D> atlas,
            final List<Point2D> tissue,
            final List<BoundaryFitMatch> matches,
            final List<ManualWarpControl> priorControls,
            final Optional<ManualHemisphereWarp2D> priorWarp) {
        return new BoundaryWarpRequest(7, side, orientation, sectionMode,
                samples(atlas), samples(tissue), matches,
                priorControls, priorWarp, MIDLINE, WIDTH, HEIGHT,
                new ManualWarpPrecondition(HASH, HASH, Optional.empty(),
                        false, Optional.empty(), priorWarp.map(warp ->
                        warp.diagnostics().contentSha256())),
                HASH, HASH);
    }

    private static List<BoundaryFitMatch> manualMatches() {
        return matches(atlasPoints(), tissuePoints());
    }

    private static List<BoundaryFitMatch> matches(
            final List<Point2D> atlas,
            final List<Point2D> tissue) {
        final List<BoundaryFitMatch> result = new ArrayList<>();
        for (int index = 0; index < atlas.size(); index++) {
            result.add(match("manual-" + index,
                    atlas.get(index), tissue.get(index)));
        }
        return List.copyOf(result);
    }

    private static List<Point2D> mirrored(final List<Point2D> points) {
        return points.stream().map(point -> new Point2D(
                WIDTH - 1.0 - point.x(), point.y())).toList();
    }

    private static List<Point2D> shifted(
            final List<Point2D> points, final double dx, final double dy) {
        return points.stream().map(point -> new Point2D(
                point.x() + dx, point.y() + dy)).toList();
    }

    private static List<Point2D> ellipsePoints(
            final Point2D centre,
            final double radiusX,
            final double radiusY,
            final int count) {
        final List<Point2D> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            final double angle = 2 * Math.PI * index / count;
            result.add(new Point2D(
                    centre.x() + radiusX * Math.cos(angle),
                    centre.y() + radiusY * Math.sin(angle)));
        }
        return List.copyOf(result);
    }

    private static BoundaryFitMatch match(
            final String id, final Point2D atlas, final Point2D tissue) {
        return new BoundaryFitMatch(id, atlas, tissue,
                BoundaryFitMatchOrigin.USER_PLACED, true);
    }

    private static List<Point2D> atlasPoints() {
        return List.of(
                new Point2D(100, 28), new Point2D(58, 46),
                new Point2D(30, 92), new Point2D(20, 150),
                new Point2D(34, 216), new Point2D(70, 260),
                new Point2D(112, 272));
    }

    private static List<Point2D> tissuePoints() {
        return List.of(
                new Point2D(100, 24), new Point2D(55, 43),
                new Point2D(26, 90), new Point2D(16, 150),
                new Point2D(30, 219), new Point2D(68, 264),
                new Point2D(112, 276));
    }

    private static List<BoundaryFitSample> samples(
            final List<Point2D> points) {
        final Point2D centre = new Point2D(105, 150);
        return points.stream().map(point -> new BoundaryFitSample(point,
                new Point2D(point.x() - centre.x(),
                        point.y() - centre.y()))).toList();
    }

    private static List<ManualWarpControl> identityInteriorControls() {
        final List<Point2D> points = List.of(
                new Point2D(112, 92), new Point2D(148, 96),
                new Point2D(115, 202), new Point2D(150, 206));
        final List<ManualWarpControl> result = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            final Point2D point = points.get(index);
            result.add(new ManualWarpControl("grid-" + index,
                    ManualHemisphereWarp2D.AtlasSide.LEFT,
                    ManualWarpControlOrigin.REGULAR_INTERIOR_GRID,
                    "regular-interior-grid", point, point));
        }
        return List.copyOf(result);
    }

    private static void assertPoint(
            final Point2D expected, final Point2D actual,
            final double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(expected.y(), actual.y(), tolerance);
    }
}
