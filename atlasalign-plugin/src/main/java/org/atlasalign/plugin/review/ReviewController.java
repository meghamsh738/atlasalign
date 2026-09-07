package org.atlasalign.plugin.review;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.atlasalign.application.AcceptedAlignmentSnapshot;
import org.atlasalign.application.AlignmentReviewContent;
import org.atlasalign.application.AlignmentReviewSession;
import org.atlasalign.application.AlignmentReviewState;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasAnatomicalSide;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.LandmarkPair;
import org.atlasalign.application.LandmarkRole;
import org.atlasalign.application.HalfAtlasCoverage;
import org.atlasalign.application.ManualWarpPrecondition;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewAcceptanceException;
import org.atlasalign.application.ReviewAcceptanceBlockReason;
import org.atlasalign.application.ReviewAcceptanceVerifier;
import org.atlasalign.application.ReviewEdit;
import org.atlasalign.application.ReviewedTissueSupport;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.application.ReviewWorkflowMode;
import org.atlasalign.application.TissueMaskEnvelope;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.BoundaryFitAnchor;
import org.atlasalign.application.manual.BoundaryFitCandidate;
import org.atlasalign.application.manual.BoundaryFitDraft;
import org.atlasalign.application.manual.BoundaryFitException;
import org.atlasalign.application.manual.BoundaryFitMatch;
import org.atlasalign.application.manual.BoundaryFitMatchOrigin;
import org.atlasalign.application.manual.BoundaryFitModel;
import org.atlasalign.application.manual.BoundaryFitPreview;
import org.atlasalign.application.manual.BoundaryFitRequest;
import org.atlasalign.application.manual.BoundaryFitSample;
import org.atlasalign.application.manual.BoundaryFitSolver;
import org.atlasalign.application.manual.BoundaryWarpCandidate;
import org.atlasalign.application.manual.BoundaryWarpBaselinePoint;
import org.atlasalign.application.manual.BoundaryWarpPreview;
import org.atlasalign.application.manual.BoundaryWarpRequest;
import org.atlasalign.application.manual.BoundaryWarpSolver;
import org.atlasalign.application.manual.ManualSidePlacement2D;
import org.atlasalign.application.manual.ManualWarpControl;
import org.atlasalign.application.manual.ManualWarpControlOrigin;
import org.atlasalign.application.manual.ManualWarpException;
import org.atlasalign.application.manual.ManualWarpSafetyGate;
import org.atlasalign.application.manual.ManualWarpSafetyReport;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;

/**
 * Headless interaction controller. All scientific state transitions are
 * delegated to {@link AlignmentReviewSession}; this class only coordinates
 * views and asynchronous atlas-plane reads.
 */
public final class ReviewController implements AutoCloseable {

    private static final Pattern CLICKED_LANDMARK_ID =
            Pattern.compile("clicked-(\\d+)");
    private static final int MAXIMUM_UNSAFE_DRAG_CLAMP_ITERATIONS = 16;

    private final AlignmentReviewSession session;
    private final ReviewPreview preview;
    private final AtlasPlaneSource planeSource;
    private final Optional<AtlasRegionCatalog> regionCatalog;
    private final ReviewAcceptanceVerifier acceptanceVerifier;
    private final Executor planeExecutor;
    private final Executor manualWarpExecutor;
    private final Executor viewExecutor;
    private final Runnable executorShutdown;
    private final AtomicLong planeRequestToken = new AtomicLong();
    private final AtomicLong manualWarpRequestToken = new AtomicLong();
    private final AtomicLong tissueSupportRequestToken = new AtomicLong();
    private final AtomicLong boundaryFitRequestToken = new AtomicLong();
    private final AtomicLong boundaryWarpRequestToken = new AtomicLong();
    private final AtomicLong structureAdjustmentRequestToken =
            new AtomicLong();
    private final Map<AtlasPlaneRequest, AtlasCoronalPlane> planeCache =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<AtlasPlaneRequest,
                                AtlasCoronalPlane> eldest) {
                    return size() > 16;
                }
            };
    private final BoundaryFitSolver boundaryFitSolver =
            new BoundaryFitSolver();
    private final BoundaryWarpSolver boundaryWarpSolver =
            new BoundaryWarpSolver();

    private ReviewView view;
    private AtlasCoronalPlane atlasPlane;
    private boolean atlasPlaneLoading;
    private String atlasPlaneError;
    private boolean showAtlasAnatomy;
    private Optional<SelectedAtlasRegion> selectedAtlasRegion =
            Optional.empty();
    private Optional<SelectedAtlasContour> selectedAtlasContour =
            Optional.empty();
    private boolean warningsAcknowledged;
    private BoundaryFitViewState boundaryFitState =
            BoundaryFitViewState.inactive();
    private long boundaryFitBaseRevision = -1;
    private boolean boundaryFitRefitSuggested;
    private BoundaryWarpViewState boundaryWarpState =
            BoundaryWarpViewState.inactive();
    private long boundaryWarpBaseRevision = -1;
    private boolean boundaryWarpRecheckSuggested;
    private boolean boundaryWarpOppositeRemnantAvailable;
    private boolean boundaryWarpOppositeRemnantIncluded;
    private long boundaryWarpOppositeRemnantRevision = -1;
    private Optional<String> manualWarpStatusMessage = Optional.empty();
    private StructureAdjustmentViewState structureAdjustmentState =
            StructureAdjustmentViewState.inactive();
    private long structureAdjustmentBaseRevision = -1;
    private StructureAdjustmentAuditJob pendingStructureAdjustmentAudit;
    private boolean structureAdjustmentAuditScheduled;
    private boolean closed;

    public ReviewController(
            final AlignmentReviewSession session,
            final ReviewPreview preview,
            final AtlasPlaneSource planeSource,
            final ReviewAcceptanceVerifier acceptanceVerifier,
            final Executor planeExecutor,
            final Executor viewExecutor) {
        this(
                session,
                preview,
                planeSource,
                acceptanceVerifier,
                planeExecutor,
                planeExecutor,
                viewExecutor,
                () -> {
                });
    }

    public static ReviewController forSwing(
            final AlignmentReviewSession session,
            final ReviewPreview preview,
            final AtlasPlaneSource planeSource,
            final ReviewAcceptanceVerifier acceptanceVerifier) {
        final ThreadFactory threads = runnable -> {
            final Thread thread = new Thread(
                    runnable, "atlasalign-atlas-plane-loader");
            thread.setDaemon(true);
            return thread;
        };
        final ExecutorService loader =
                Executors.newSingleThreadExecutor(threads);
        final ThreadFactory warpThreads = runnable -> {
            final Thread thread = new Thread(
                    runnable, "atlasalign-manual-mesh-solver");
            thread.setDaemon(true);
            return thread;
        };
        final ExecutorService warpSolver =
                Executors.newSingleThreadExecutor(warpThreads);
        return new ReviewController(
                session,
                preview,
                planeSource,
                acceptanceVerifier,
                loader,
                warpSolver,
                javax.swing.SwingUtilities::invokeLater,
                () -> {
                    loader.shutdownNow();
                    warpSolver.shutdownNow();
                });
    }

    ReviewController(
            final AlignmentReviewSession session,
            final ReviewPreview preview,
            final AtlasPlaneSource planeSource,
            final ReviewAcceptanceVerifier acceptanceVerifier,
            final Executor planeExecutor,
            final Executor manualWarpExecutor,
            final Executor viewExecutor,
            final Runnable executorShutdown) {
        this.session = Objects.requireNonNull(session, "session");
        this.preview = Objects.requireNonNull(preview, "preview");
        final var expectedPreview = session.state().basis()
                .previewDimensions();
        if (preview.width() != expectedPreview.width()
                || preview.height() != expectedPreview.height()) {
            throw new IllegalArgumentException(
                    "Review preview dimensions must match the immutable review basis");
        }
        this.planeSource = Objects.requireNonNull(
                planeSource, "planeSource");
        regionCatalog = planeSource instanceof AtlasRegionCatalog catalog
                ? Optional.of(catalog) : Optional.empty();
        this.acceptanceVerifier = Objects.requireNonNull(
                acceptanceVerifier, "acceptanceVerifier");
        this.planeExecutor = Objects.requireNonNull(
                planeExecutor, "planeExecutor");
        this.manualWarpExecutor = Objects.requireNonNull(
                manualWarpExecutor, "manualWarpExecutor");
        this.viewExecutor = Objects.requireNonNull(
                viewExecutor, "viewExecutor");
        this.executorShutdown = Objects.requireNonNull(
                executorShutdown, "executorShutdown");
    }

    public synchronized void attach(final ReviewView reviewView) {
        requireOpen();
        view = Objects.requireNonNull(reviewView, "reviewView");
        publish();
        requestAtlasPlane(session.state().content().coronalLevel());
    }

    public synchronized AlignmentReviewState state() {
        return session.state();
    }

    /** Returns and clears one concise completion notice for the Swing status. */
    public synchronized Optional<String> takeManualWarpStatusMessage() {
        final Optional<String> message = manualWarpStatusMessage;
        manualWarpStatusMessage = Optional.empty();
        return message;
    }

    public synchronized StructureAdjustmentViewState
            structureAdjustmentState() {
        return structureAdjustmentState;
    }

    /** Builds numbered atlas anchors for one guided manual coarse fit. */
    public synchronized void startBoundaryFitMatching(
            final BoundaryFitModel model,
            final ManualHemisphereWarp2D.AtlasSide activeSide) {
        requireOpen();
        final AlignmentReviewState captured = session.state();
        final AtlasCoronalPlane capturedPlane = requireCurrentAtlasPlane();
        final long token = boundaryFitRequestToken.incrementAndGet();
        boundaryFitBaseRevision = captured.contentRevision();
        boundaryFitState = new BoundaryFitViewState(
                true, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("Preparing numbered atlas border points…"),
                boundaryFitRefitSuggested);
        publish();
        manualWarpExecutor.execute(() -> {
            BoundaryFitDraft draft = null;
            RuntimeException error = null;
            try {
                draft = BoundaryFitRequestFactory.createGuidedDraft(
                        captured, capturedPlane,
                        Objects.requireNonNull(model, "model"),
                        Objects.requireNonNull(activeSide, "activeSide"));
            } catch (final RuntimeException failure) {
                error = failure;
            }
            final BoundaryFitDraft completed = draft;
            final RuntimeException completedError = error;
            viewExecutor.execute(() -> completeBoundaryFitStart(
                    token, captured.contentRevision(), completed,
                    completedError));
        });
    }

    private synchronized void completeBoundaryFitStart(
            final long token,
            final long expectedRevision,
            final BoundaryFitDraft draft,
            final RuntimeException error) {
        if (closed || token != boundaryFitRequestToken.get()) {
            return;
        }
        if (session.state().contentRevision() != expectedRevision) {
            boundaryFitState = staleBoundaryFitState();
            boundaryFitBaseRevision = -1;
            publish();
            return;
        }
        if (error != null) {
            boundaryFitState = new BoundaryFitViewState(
                    false, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(boundaryFitMessage(error)),
                    boundaryFitRefitSuggested);
            boundaryFitBaseRevision = -1;
            publish();
            return;
        }
        final BoundaryFitDraft completed = Objects.requireNonNull(
                draft, "draft");
        boundaryFitState = new BoundaryFitViewState(
                false, Optional.of(completed), Optional.empty(), Optional.empty(),
                Optional.of(boundaryFitPrompt(completed)),
                boundaryFitRefitSuggested);
        publish();
    }

    /** Re-solves a draft after completing or editing manual pairs. */
    private synchronized void solveBoundaryFitDraft(
            final BoundaryFitDraft draft) {
        if (draft.includedCompletedCount() == 0) {
            boundaryFitRequestToken.incrementAndGet();
            boundaryFitState = new BoundaryFitViewState(
                    false, Optional.of(draft), Optional.empty(), Optional.empty(),
                    Optional.of(boundaryFitPrompt(draft)),
                    boundaryFitRefitSuggested);
            publish();
            return;
        }
        final long token = boundaryFitRequestToken.incrementAndGet();
        final BoundaryFitRequest request = draft.request().withMatches(
                draft.completedMatches());
        boundaryFitBaseRevision = request.contentRevision();
        boundaryFitState = new BoundaryFitViewState(
                true, Optional.of(draft), boundaryFitState.preview(),
                Optional.empty(),
                Optional.of("Updating the border preview…"),
                boundaryFitRefitSuggested);
        publish();
        manualWarpExecutor.execute(() -> {
            BoundaryFitPreview preview = null;
            BoundaryFitCandidate candidate = null;
            RuntimeException error = null;
            try {
                preview = boundaryFitSolver.preview(request);
                if (draft.includedCompletedCount()
                        >= BoundaryFitSolver.MINIMUM_INCLUDED_MATCHES) {
                    candidate = boundaryFitSolver.solve(request);
                }
            } catch (final RuntimeException failure) {
                error = failure;
            }
            final BoundaryFitPreview completedPreview = preview;
            final BoundaryFitCandidate completed = candidate;
            final RuntimeException completedError = error;
            viewExecutor.execute(() -> completeBoundaryFitSolve(
                    token, request.contentRevision(), draft,
                    completedPreview, completed, completedError));
        });
    }

    private synchronized void completeBoundaryFitSolve(
            final long token,
            final long expectedRevision,
            final BoundaryFitDraft draft,
            final BoundaryFitPreview preview,
            final BoundaryFitCandidate candidate,
            final RuntimeException error) {
        if (closed || token != boundaryFitRequestToken.get()) {
            return;
        }
        if (session.state().contentRevision() != expectedRevision) {
            boundaryFitState = staleBoundaryFitState();
            boundaryFitRefitSuggested = true;
            boundaryFitBaseRevision = -1;
            publish();
            return;
        }
        if (error != null) {
            boundaryFitState = new BoundaryFitViewState(
                    false, Optional.of(draft), Optional.ofNullable(preview),
                    Optional.empty(),
                    Optional.of(boundaryFitMessage(error)),
                    boundaryFitRefitSuggested);
            publish();
            return;
        }
        if (candidate == null) {
            boundaryFitState = new BoundaryFitViewState(
                    false, Optional.of(draft), Optional.ofNullable(preview),
                    Optional.empty(),
                    Optional.of(progressiveBoundaryFitMessage(draft)),
                    boundaryFitRefitSuggested);
            publish();
            return;
        }
        try {
            requireBoundaryCandidateSafe(
                    candidate);
            boundaryFitState = new BoundaryFitViewState(
                    false, Optional.of(draft), Optional.ofNullable(preview),
                    Optional.of(candidate),
                    Optional.of(candidate.includedMatchCount()
                            + " matches ready — Apply or keep editing"),
                    boundaryFitRefitSuggested);
        } catch (final RuntimeException unsafe) {
            boundaryFitState = new BoundaryFitViewState(
                    false, Optional.of(draft), Optional.ofNullable(preview),
                    Optional.empty(),
                    Optional.of(boundaryFitMessage(unsafe)),
                    boundaryFitRefitSuggested);
        }
        publish();
    }

    /** Switches between proportional and independent safe X/Y fitting. */
    public synchronized void setBoundaryFitModel(
            final BoundaryFitModel model) {
        requireOpen();
        final BoundaryFitDraft draft = requireBoundaryFitDraft().withModel(
                Objects.requireNonNull(model, "model"));
        solveBoundaryFitDraft(draft);
    }

    public synchronized void selectBoundaryFitAnchor(
            final String identifier) {
        final BoundaryFitDraft draft = requireBoundaryFitDraft()
                .withActiveAnchor(identifier);
        boundaryFitState = new BoundaryFitViewState(
                boundaryFitState.loading(), Optional.of(draft),
                boundaryFitState.preview(), boundaryFitState.candidate(),
                boundaryFitState.message(),
                boundaryFitRefitSuggested);
        publish();
    }

    public synchronized void setBoundaryFitTissuePoint(
            final String identifier,
            final Point2D requestedPoint) {
        BoundaryFitDraft draft = requireBoundaryFitDraft();
        final BoundaryFitAnchor current = boundaryFitAnchor(
                draft, identifier);
        final Point2D tissue = requirePreviewEndpoint(
                draft.request().previewWidth(),
                draft.request().previewHeight(), requestedPoint,
                "Tissue match endpoint");
        draft = draft.withAnchor(current.withTissuePoint(tissue))
                .selectNextIncompleteAfter(identifier);
        solveBoundaryFitDraft(draft);
    }

    public synchronized void moveBoundaryFitMatch(
            final String identifier,
            final BoundaryFitEndpoint endpoint,
            final Point2D requestedPoint) {
        final BoundaryFitDraft draft = requireBoundaryFitDraft();
        final BoundaryFitRequest request = draft.request();
        final Point2D checked = endpoint == BoundaryFitEndpoint.ATLAS
                ? nearestBoundaryPoint(request.atlasBoundary(),
                        Objects.requireNonNull(
                                requestedPoint, "requestedPoint"))
                : requirePreviewEndpoint(request.previewWidth(),
                        request.previewHeight(), requestedPoint,
                        "Tissue match endpoint");
        final BoundaryFitAnchor anchor = boundaryFitAnchor(
                draft, identifier);
        final BoundaryFitAnchor updated = endpoint
                == BoundaryFitEndpoint.ATLAS
                ? anchor.withAtlasPoints(
                        BoundaryFitRequestFactory.rawAtlasPlanePoint(
                                session.state(), request.targetSide(),
                                checked),
                        checked)
                : anchor.withTissuePoint(checked);
        final BoundaryFitDraft next = draft.withAnchor(updated);
        if (next == draft) {
            return;
        }
        solveBoundaryFitDraft(next);
    }

    public synchronized void setBoundaryFitAnchorIncluded(
            final String identifier,
            final boolean included) {
        BoundaryFitDraft draft = requireBoundaryFitDraft();
        final BoundaryFitAnchor anchor = boundaryFitAnchor(
                draft, identifier);
        draft = draft.withAnchor(anchor.withIncluded(included));
        if (!included) {
            draft = draft.selectNextIncompleteAfter(identifier);
        } else if (anchor.tissuePreviewPoint().isEmpty()) {
            draft = draft.withActiveAnchor(identifier);
        }
        solveBoundaryFitDraft(draft);
    }

    public synchronized void clearBoundaryFitAnchor(
            final String identifier) {
        final BoundaryFitDraft draft = requireBoundaryFitDraft();
        final BoundaryFitAnchor anchor = boundaryFitAnchor(
                draft, identifier);
        solveBoundaryFitDraft(draft.withAnchor(
                anchor.withoutTissuePoint().withIncluded(true))
                .withActiveAnchor(identifier));
    }

    /** Applies the current ghost placement as exactly one review revision. */
    public synchronized void applyBoundaryFit() {
        requireOpen();
        final BoundaryFitCandidate candidate = requireBoundaryFitCandidate();
        requireBoundaryCandidateSafe(candidate);
        final ReviewEdit.ApplyAssistedBoundaryFit edit =
                boundaryFitEdit(candidate);
        if (!session.applyIfCurrentRevision(
                candidate.contentRevision(), edit)) {
            cancelBoundaryFitForStaleState();
            return;
        }
        boundaryFitRequestToken.incrementAndGet();
        boundaryFitState = BoundaryFitViewState.inactive();
        boundaryFitBaseRevision = -1;
        boundaryFitRefitSuggested = true;
        publish();
    }

    public synchronized void cancelBoundaryFit() {
        requireOpen();
        boundaryFitRequestToken.incrementAndGet();
        boundaryFitState = new BoundaryFitViewState(
                false, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(),
                boundaryFitRefitSuggested);
        boundaryFitBaseRevision = -1;
        publish();
    }

    public synchronized BoundaryFitViewState boundaryFitState() {
        return boundaryFitState;
    }

    /** Starts an empty, manual-first atlas-border to tissue-border draft. */
    public synchronized void startBoundaryWarp(
            final ManualHemisphereWarp2D.AtlasSide activeSide) {
        startBoundaryWarp(activeSide,
                BoundaryWarpSolver.DEFAULT_SUGGESTED_MATCHES,
                session.state().content().halfAtlasCoverage()
                        .includesOppositeRemnant());
    }

    /** Starts one dense editable draft before attempting any nonlinear solve. */
    public synchronized void startBoundaryWarp(
            final ManualHemisphereWarp2D.AtlasSide activeSide,
            final int density) {
        startBoundaryWarp(activeSide, density,
                session.state().content().halfAtlasCoverage()
                        .includesOppositeRemnant());
    }

    /** Starts a draft with an explicitly requested Half remnant side. */
    public synchronized void startBoundaryWarp(
            final ManualHemisphereWarp2D.AtlasSide activeSide,
            final int density,
            final boolean includeOppositeHalfRemnant) {
        requireOpen();
        if (density < BoundaryWarpSolver.MINIMUM_INCLUDED_MATCHES
                || density > BoundaryWarpSolver.MAXIMUM_MATCHES) {
            throw new IllegalArgumentException(
                    "Boundary density must be from 4 to 48");
        }
        final AlignmentReviewState captured = session.state();
        final boolean persistedOppositeRemnant = captured.content()
                .reviewSectionMode() == ReviewSectionMode.HALF
                && captured.content().halfAtlasCoverage()
                        .includesOppositeRemnant();
        if (captured.content().reviewSectionMode() == ReviewSectionMode.HALF
                && includeOppositeHalfRemnant
                        != persistedOppositeRemnant) {
            throw new IllegalStateException(
                    "Change Include opposite-side remnant before starting Border.");
        }
        final AtlasCoronalPlane capturedPlane = requireCurrentAtlasPlane();
        final long token = boundaryWarpRequestToken.incrementAndGet();
        boundaryWarpBaseRevision = captured.contentRevision();
        boundaryWarpState = new BoundaryWarpViewState(
                true, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("Preparing the atlas and tissue outer borders…"),
                boundaryWarpRecheckSuggested);
        publish();
        manualWarpExecutor.execute(() -> {
            BoundaryWarpRequest request = null;
            BoundaryWarpRequest secondary = null;
            boolean remnantAvailable = false;
            boolean remnantIncluded = false;
            RuntimeException failure = null;
            try {
                final ReviewSectionMode mode = captured.content()
                        .reviewSectionMode();
                final ManualHemisphereWarp2D.AtlasSide primarySide =
                        mode == ReviewSectionMode.HALF
                                ? PlacedAtlasSideSupport.visibleHalfSide(
                                        captured)
                                : Objects.requireNonNull(
                                        activeSide, "activeSide");
                request = BoundaryFitRequestFactory.createBoundaryWarp(
                        captured, capturedPlane, primarySide,
                        List.of());
                request = boundaryWarpSolver.suggest(request, density);
                try {
                    final ManualHemisphereWarp2D.AtlasSide other =
                            oppositeSide(primarySide);
                    if (mode == ReviewSectionMode.HALF) {
                        remnantAvailable = PlacedAtlasSideSupport.evaluate(
                                captured, other, 24).remnantEligible();
                    }
                    if (mode != ReviewSectionMode.HALF
                            || persistedOppositeRemnant
                                    && remnantAvailable) {
                        BoundaryWarpRequest proposedSecondary =
                                BoundaryFitRequestFactory.createBoundaryWarp(
                                        captured, capturedPlane, other,
                                        List.of());
                        proposedSecondary = boundaryWarpSolver.suggest(
                                proposedSecondary, density);
                        secondary = proposedSecondary;
                        remnantIncluded = mode == ReviewSectionMode.HALF;
                    }
                } catch (final RuntimeException unsupportedOtherSide) {
                    // The requested side remains usable. Half sections may
                    // legitimately have no supported contralateral remnant.
                }
            } catch (final RuntimeException error) {
                failure = error;
            }
            final BoundaryWarpRequest completed = request;
            final BoundaryWarpRequest completedSecondary = secondary;
            final boolean completedRemnantAvailable = remnantAvailable;
            final boolean completedRemnantIncluded = remnantIncluded;
            final RuntimeException completedFailure = failure;
            viewExecutor.execute(() -> completeBoundaryWarpStart(
                    token, captured.contentRevision(), completed,
                    completedSecondary,
                    completedRemnantAvailable,
                    completedRemnantIncluded,
                    completedFailure));
        });
    }

    private synchronized void startBoundaryWarpSingle(
            final ManualHemisphereWarp2D.AtlasSide activeSide,
            final List<BoundaryFitMatch> preservedMatches) {
        startBoundaryWarpSingle(
                activeSide, preservedMatches, List.of(), List.of(), null);
    }

    private synchronized void startBoundaryWarpSingle(
            final ManualHemisphereWarp2D.AtlasSide activeSide,
            final List<BoundaryFitMatch> preservedMatches,
            final List<BoundaryFitMatch> lastAuditedMatches,
            final List<BoundaryWarpBaselinePoint> preservedBaseline) {
        startBoundaryWarpSingle(activeSide, preservedMatches,
                lastAuditedMatches, preservedBaseline, null);
    }

    private synchronized void startBoundaryWarpSingle(
            final ManualHemisphereWarp2D.AtlasSide activeSide,
            final List<BoundaryFitMatch> preservedMatches,
            final List<BoundaryFitMatch> lastAuditedMatches,
            final List<BoundaryWarpBaselinePoint> preservedBaseline,
            final BoundaryWarpRequest preservedSecondary) {
        final AlignmentReviewState captured = session.state();
        final AtlasCoronalPlane capturedPlane = requireCurrentAtlasPlane();
        final long token = boundaryWarpRequestToken.incrementAndGet();
        final boolean persistedRemnant = captured.content()
                .reviewSectionMode() == ReviewSectionMode.HALF
                && captured.content().halfAtlasCoverage()
                        .includesOppositeRemnant();
        final boolean remnantAvailable = persistedRemnant
                || boundaryWarpOppositeRemnantAvailable();
        final boolean remnantIncluded = persistedRemnant;
        boundaryWarpBaseRevision = captured.contentRevision();
        boundaryWarpState = new BoundaryWarpViewState(
                true, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("Revalidating the remaining atlas border points…"),
                boundaryWarpRecheckSuggested);
        publish();
        manualWarpExecutor.execute(() -> {
            BoundaryWarpRequest request = null;
            BoundaryWarpRequest secondary = null;
            RuntimeException failure = null;
            try {
                request = BoundaryFitRequestFactory.createBoundaryWarp(
                        captured, capturedPlane, activeSide, List.of());
                if (!preservedMatches.isEmpty()) {
                    request = rebaseBoundaryRequest(request,
                            preservedMatches, lastAuditedMatches,
                            preservedBaseline);
                }
                if (preservedSecondary != null) {
                    secondary = BoundaryFitRequestFactory.createBoundaryWarp(
                            captured, capturedPlane,
                            preservedSecondary.targetSide(), List.of());
                    secondary = rebaseBoundaryRequest(secondary,
                            preservedSecondary.matches(), List.of(),
                            preservedSecondary.baselinePoints());
                }
            } catch (final RuntimeException error) {
                failure = error;
            }
            final BoundaryWarpRequest completed = request;
            final BoundaryWarpRequest completedSecondary = secondary;
            final RuntimeException completedFailure = failure;
            viewExecutor.execute(() -> completeBoundaryWarpStart(
                    token, captured.contentRevision(), completed,
                    completedSecondary,
                    remnantAvailable, remnantIncluded,
                    completedFailure));
        });
    }

    private synchronized void completeBoundaryWarpStart(
            final long token,
            final long expectedRevision,
            final BoundaryWarpRequest request,
            final BoundaryWarpRequest secondaryRequest,
            final boolean oppositeRemnantAvailable,
            final boolean oppositeRemnantIncluded,
            final RuntimeException failure) {
        if (closed || token != boundaryWarpRequestToken.get()) {
            return;
        }
        if (session.state().contentRevision() != expectedRevision) {
            boundaryWarpState = staleBoundaryWarpState();
            boundaryWarpBaseRevision = -1;
            publish();
            return;
        }
        if (failure != null) {
            boundaryWarpState = new BoundaryWarpViewState(
                    false, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.of(boundaryFitMessage(failure)),
                    boundaryWarpRecheckSuggested);
            boundaryWarpBaseRevision = -1;
            publish();
            return;
        }
        final BoundaryWarpRequest completed = Objects.requireNonNull(request);
        boundaryWarpOppositeRemnantAvailable = oppositeRemnantAvailable;
        boundaryWarpOppositeRemnantIncluded = oppositeRemnantIncluded;
        boundaryWarpOppositeRemnantRevision = expectedRevision;
        final long included = completed.matches().stream()
                .filter(BoundaryFitMatch::included).count();
        boundaryWarpState = new BoundaryWarpViewState(
                false,
                BoundaryWarpSideState.dirtyDraft(completed, 0),
                secondaryRequest == null
                        ? BoundaryWarpSideState.empty()
                        : BoundaryWarpSideState.dirtyDraft(
                                secondaryRequest, 0),
                Optional.of(completed.matches().size()
                        + " draft points ready • " + included
                        + " included — Calculate border preview"),
                boundaryWarpRecheckSuggested);
        publish();
    }

    /** Replaces the draft with deterministic editable suggestions. */
    public synchronized void suggestBoundaryWarpPairs() {
        suggestBoundaryWarpPairs(
                BoundaryWarpSolver.DEFAULT_SUGGESTED_MATCHES);
    }

    public synchronized void suggestBoundaryWarpPairs(final int density) {
        final BoundaryWarpRequest request = requireBoundaryWarpDraft();
        final BoundaryWarpRequest secondary = boundaryWarpState
                .secondaryDraft().orElse(null);
        final long token = boundaryWarpRequestToken.incrementAndGet();
        boundaryWarpState = new BoundaryWarpViewState(
                true, boundaryWarpState.primary(),
                boundaryWarpState.secondary(),
                Optional.of("Suggesting editable outer-border pairs…"),
                boundaryWarpRecheckSuggested);
        publish();
        manualWarpExecutor.execute(() -> {
            BoundaryWarpRequest suggested = null;
            RuntimeException failure = null;
            try {
                suggested = boundaryWarpSolver.suggest(request, density);
            } catch (final RuntimeException error) {
                failure = error;
            }
            final BoundaryWarpRequest completedRequest = suggested;
            final RuntimeException completedFailure = failure;
            viewExecutor.execute(() -> completeBoundaryWarpStart(
                    token, request.contentRevision(), completedRequest,
                    secondary,
                    boundaryWarpOppositeRemnantAvailable,
                    boundaryWarpOppositeRemnantIncluded,
                    completedFailure));
        });
    }

    private synchronized void solveBoundaryWarpDraft(
            final BoundaryWarpRequest request,
            final BoundaryWarpViewState prior) {
        final long token = boundaryWarpRequestToken.incrementAndGet();
        boundaryWarpState = new BoundaryWarpViewState(
                true, prior.primary(), prior.secondary(),
                Optional.of("Checking the nonlinear border preview…"),
                boundaryWarpRecheckSuggested);
        publish();
        manualWarpExecutor.execute(() -> {
            BoundaryWarpPreview preview = null;
            BoundaryWarpCandidate candidate = null;
            RuntimeException failure = null;
            try {
                preview = boundaryWarpSolver.preview(request);
                if (request.matches().stream()
                        .filter(BoundaryFitMatch::included).count()
                        >= BoundaryWarpSolver.MINIMUM_INCLUDED_MATCHES) {
                    candidate = boundaryWarpSolver.solveSafestStep(request);
                }
            } catch (final RuntimeException error) {
                failure = error;
            }
            final BoundaryWarpPreview completedPreview = preview;
            final BoundaryWarpCandidate completedCandidate = candidate;
            final RuntimeException completedFailure = failure;
            viewExecutor.execute(() -> completeBoundaryWarpSolve(
                    token, request.contentRevision(), request,
                    completedPreview, completedCandidate,
                    completedFailure, prior));
        });
    }

    private synchronized void completeBoundaryWarpSolve(
            final long token,
            final long expectedRevision,
            final BoundaryWarpRequest request,
            final BoundaryWarpPreview preview,
            final BoundaryWarpCandidate candidate,
            final RuntimeException failure,
            final BoundaryWarpViewState prior) {
        if (closed || token != boundaryWarpRequestToken.get()) {
            return;
        }
        if (session.state().contentRevision() != expectedRevision) {
            boundaryWarpState = staleBoundaryWarpState();
            boundaryWarpBaseRevision = -1;
            publish();
            return;
        }
        if (failure != null) {
            final Optional<ManualWarpSafetyReport> safetyReport =
                    manualWarpSafetyReport(failure);
            boundaryWarpState = new BoundaryWarpViewState(
                    false, BoundaryWarpSideState.calculated(request,
                            null, null, safetyReport.orElse(null)),
                    prior.secondary(),
                    Optional.of(safetyReport.map(
                            ReviewController::boundaryWarpSafetyFailureMessage)
                            .orElseGet(() -> boundaryFitMessage(failure))),
                    boundaryWarpRecheckSuggested);
            publish();
            return;
        }
        if (candidate == null) {
            final long included = request.matches().stream()
                    .filter(BoundaryFitMatch::included).count();
            boundaryWarpState = new BoundaryWarpViewState(
                    false, BoundaryWarpSideState.calculated(request,
                            preview, null, null), prior.secondary(),
                    Optional.of(included
                    + "/4 included — add or Use points, then Calculate again"),
                    boundaryWarpRecheckSuggested);
            publish();
            return;
        }
        try {
            requireBoundaryWarpCandidateSafe(
                    candidate);
            boundaryWarpState = new BoundaryWarpViewState(
                    false, BoundaryWarpSideState.calculated(request,
                            preview, candidate,
                            candidate.limitingSafetyReport().orElse(null)),
                    prior.secondary(),
                    Optional.of(
                    candidate.completesRequestedWarp()
                            ? candidate.includedMatchCount()
                                    + " border pairs ready — Apply or keep editing"
                            : safeStepMessage(candidate)),
                    boundaryWarpRecheckSuggested);
        } catch (final RuntimeException unsafe) {
            final Optional<ManualWarpSafetyReport> safetyReport =
                    manualWarpSafetyReport(unsafe);
            boundaryWarpState = new BoundaryWarpViewState(
                    false, BoundaryWarpSideState.calculated(request,
                            null, null, safetyReport.orElse(null)),
                    prior.secondary(),
                    Optional.of(safetyReport.map(
                            ReviewController::boundaryWarpSafetyFailureMessage)
                            .orElseGet(() -> boundaryFitMessage(unsafe))),
                    boundaryWarpRecheckSuggested);
        }
        publish();
    }

    public synchronized void moveBoundaryWarpMatch(
            final String identifier,
            final BoundaryFitEndpoint endpoint,
            final Point2D requestedPoint) {
        final BoundaryWarpRequest request = requireBoundaryWarpDraft();
        final boolean known = request.matches().stream().anyMatch(
                match -> match.id().equals(identifier));
        if (!known) {
            throw new IllegalArgumentException(
                    "Unknown manual border pair: " + identifier);
        }
        final Point2D checked = endpoint == BoundaryFitEndpoint.ATLAS
                ? nearestBoundaryPoint(request.atlasBoundary(),
                        Objects.requireNonNull(
                                requestedPoint, "requestedPoint"))
                : requirePreviewEndpoint(request.previewWidth(),
                        request.previewHeight(), requestedPoint,
                        "Tissue border endpoint");
        final List<BoundaryFitMatch> matches = request.matches().stream()
                .map(match -> match.id().equals(identifier)
                        ? endpoint == BoundaryFitEndpoint.ATLAS
                        ? match.withAtlasPoint(checked)
                        : match.withTissuePoint(checked)
                        : match).toList();
        if (matches.equals(request.matches())) {
            return;
        }
        updateBoundaryWarpDraft(request.withMatches(matches));
    }

    /** Completes or retargets a border pair with one finite tissue endpoint. */
    public synchronized void completeBoundaryWarpMatch(
            final String identifier,
            final Point2D requestedTissuePoint) {
        final BoundaryWarpRequest request = requireBoundaryWarpDraft();
        final boolean known = request.matches().stream().anyMatch(
                match -> match.id().equals(identifier));
        if (!known) {
            throw new IllegalArgumentException(
                    "Unknown manual border pair: " + identifier);
        }
        final Point2D tissue = requirePreviewEndpoint(
                request.previewWidth(), request.previewHeight(),
                requestedTissuePoint, "Tissue border endpoint");
        final List<BoundaryFitMatch> matches = request.matches().stream()
                .map(match -> match.id().equals(identifier)
                        ? match.withTissuePoint(tissue)
                        : match).toList();
        if (matches.equals(request.matches())) {
            return;
        }
        updateBoundaryWarpDraft(request.withMatches(matches));
    }

    public synchronized void addBoundaryWarpMatch(
            final Point2D requestedAtlasPoint,
            final Point2D requestedTissuePoint) {
        final BoundaryWarpRequest request = requireBoundaryWarpDraft();
        if (request.matches().size() >= BoundaryWarpSolver.MAXIMUM_MATCHES) {
            throw new ManualWarpException(
                    org.atlasalign.application.manual.ManualWarpFailureKind
                            .CONTROL_LIMIT,
                    "This side already has 48 border pairs. Remove one before adding another.",
                    "Manual outer-border match limit reached");
        }
        final Point2D atlasPoint = nearestBoundaryPoint(
                request.atlasBoundary(), requestedAtlasPoint);
        final Point2D tissuePoint = requirePreviewEndpoint(
                request.previewWidth(), request.previewHeight(),
                requestedTissuePoint, "Tissue border endpoint");
        int next = 1;
        final java.util.Set<String> ids = request.matches().stream()
                .map(BoundaryFitMatch::id)
                .collect(java.util.stream.Collectors.toSet());
        final String prefix = "border-" + request.targetSide().name()
                .toLowerCase(java.util.Locale.ROOT) + "-user-";
        while (ids.contains(String.format(java.util.Locale.ROOT,
                prefix + "%02d", next))) {
            next++;
        }
        final List<BoundaryFitMatch> matches = new java.util.ArrayList<>(
                request.matches());
        matches.add(new BoundaryFitMatch(String.format(
                java.util.Locale.ROOT, prefix + "%02d", next),
                atlasPoint, tissuePoint,
                BoundaryFitMatchOrigin.USER_PLACED, true));
        updateBoundaryWarpDraft(request.withMatches(matches));
    }

    public synchronized void setBoundaryWarpMatchIncluded(
            final String identifier,
            final boolean included) {
        final BoundaryWarpRequest request = requireBoundaryWarpDraft();
        final List<BoundaryFitMatch> matches = request.matches().stream()
                .map(match -> match.id().equals(identifier)
                        ? match.withIncluded(included) : match).toList();
        if (matches.equals(request.matches())) {
            throw new IllegalArgumentException(
                    "Manual border pair already has that state or was not found.");
        }
        updateBoundaryWarpDraft(request.withMatches(matches));
    }

    public synchronized void removeBoundaryWarpMatch(
            final String identifier) {
        final BoundaryWarpRequest request = requireBoundaryWarpDraft();
        final List<BoundaryFitMatch> matches = request.matches().stream()
                .filter(match -> !match.id().equals(identifier)).toList();
        if (matches.size() == request.matches().size()) {
            throw new IllegalArgumentException(
                    "Unknown manual border pair: " + identifier);
        }
        updateBoundaryWarpDraft(request.withMatches(matches));
    }

    private void updateBoundaryWarpDraft(
            final BoundaryWarpRequest request) {
        final BoundaryWarpViewState prior = boundaryWarpState;
        boundaryWarpRequestToken.incrementAndGet();
        final int pending = prior.primary().pendingEditCount() + 1;
        boundaryWarpState = new BoundaryWarpViewState(false,
                BoundaryWarpSideState.dirtyDraft(request, pending),
                prior.secondary(), Optional.of(pending
                        + (pending == 1 ? " change pending" : " changes pending")
                        + " — Calculate border preview"),
                boundaryWarpRecheckSuggested);
        publish();
    }

    /** Runs exactly one nonlinear Border solve for the current dirty draft. */
    public synchronized void calculateBoundaryWarpPreview() {
        requireOpen();
        if (boundaryWarpState.loading()) {
            throw new IllegalStateException(
                    "Wait for the current Border calculation to finish.");
        }
        final BoundaryWarpRequest request = requireBoundaryWarpDraft();
        final long included = request.matches().stream()
                .filter(BoundaryFitMatch::included).count();
        if (included < BoundaryWarpSolver.MINIMUM_INCLUDED_MATCHES) {
            throw new IllegalStateException(
                    "Use at least four distributed Border points before calculating.");
        }
        solveBoundaryWarpDraft(request, boundaryWarpState);
    }

    /** Applies the safe nonlinear border field as exactly one revision. */
    public synchronized void applyBoundaryWarp() {
        requireOpen();
        final BoundaryWarpRequest nextSide = boundaryWarpState
                .secondaryDraft().orElse(null);
        final BoundaryWarpCandidate candidate = boundaryWarpState.candidate()
                .orElseThrow(() -> new IllegalStateException(
                        "Calculate a safe Border preview before applying."));
        if (boundaryWarpState.dirty()
                || !candidate.request().equals(requireBoundaryWarpDraft())) {
            throw new IllegalStateException(
                    "Border points changed; Calculate border preview again before applying.");
        }
        requireBoundaryWarpCandidateSafe(candidate);
        final ReviewEdit.ApplyManualBoundaryWarp edit =
                boundaryWarpEdit(candidate);
        if (!session.applyIfCurrentRevision(
                candidate.contentRevision(), edit)) {
            boundaryWarpState = staleBoundaryWarpState();
            boundaryWarpBaseRevision = -1;
            publish();
            return;
        }
        boundaryWarpRequestToken.incrementAndGet();
        boundaryWarpState = BoundaryWarpViewState.inactive();
        boundaryWarpBaseRevision = -1;
        boundaryWarpRecheckSuggested = false;
        if (!candidate.completesRequestedWarp()) {
            startBoundaryWarpSingle(candidate.request().targetSide(),
                    candidate.matches(), candidate.auditedMatches(),
                    candidate.request().baselinePoints(),
                    nextSide);
        } else if (nextSide == null) {
            clearOppositeRemnantEligibility();
            publish();
        } else {
            startBoundaryWarpSingle(nextSide.targetSide(),
                    nextSide.matches(), List.of(),
                    nextSide.baselinePoints());
        }
    }

    private static BoundaryWarpRequest rebaseBoundaryRequest(
            final BoundaryWarpRequest freshRequest,
            final List<BoundaryFitMatch> requestedMatches,
            final List<BoundaryFitMatch> lastAuditedMatches,
            final List<BoundaryWarpBaselinePoint> preservedBaseline) {
        final List<BoundaryFitMatch> rebasedMatches = rebaseBoundaryMatches(
                freshRequest, requestedMatches, lastAuditedMatches);
        final BoundaryWarpRequest derived = freshRequest.withMatches(
                rebasedMatches);
        if (preservedBaseline.isEmpty()) {
            return derived;
        }
        final java.util.Map<String, BoundaryWarpBaselinePoint> priorByMatch =
                preservedBaseline.stream().collect(
                        java.util.stream.Collectors.toMap(
                                BoundaryWarpBaselinePoint::matchId,
                                java.util.function.Function.identity()));
        final java.util.Map<String, ManualWarpControl> installedById =
                freshRequest.priorControls().stream().collect(
                        java.util.stream.Collectors.toMap(
                                ManualWarpControl::id,
                                java.util.function.Function.identity()));
        final List<BoundaryWarpBaselinePoint> exactBaseline =
                rebasedMatches.stream().map(match -> {
                    final BoundaryWarpBaselinePoint preserved =
                            priorByMatch.get(match.id());
                    final ManualWarpControl installed = preserved == null
                            ? null : installedById.get(
                                    preserved.controlId());
                    if (installed != null
                            && installed.atlasSide()
                                    == freshRequest.targetSide()
                            && installed.groupId().equals(
                                    BoundaryWarpSolver.GROUP_ID)) {
                        return new BoundaryWarpBaselinePoint(match.id(),
                                installed.id(), installed.sourcePoint(),
                                installed.targetPoint());
                    }
                    return derived.baselineFor(match.id());
                }).toList();
        return freshRequest.withMatchesAndBaseline(
                rebasedMatches, exactBaseline);
    }

    private static List<BoundaryFitMatch> rebaseBoundaryMatches(
            final BoundaryWarpRequest rebasedRequest,
            final List<BoundaryFitMatch> requestedMatches,
            final List<BoundaryFitMatch> lastAuditedMatches) {
        final java.util.Map<String, BoundaryFitMatch> auditedById =
                lastAuditedMatches.stream().collect(
                        java.util.stream.Collectors.toMap(
                                BoundaryFitMatch::id,
                                java.util.function.Function.identity()));
        return requestedMatches.stream().map(requested -> {
            final BoundaryFitMatch audited = auditedById.get(requested.id());
            final Point2D reference = audited == null
                    ? requested.atlasPreviewPoint()
                    : audited.tissuePreviewPoint();
            return requested.withAtlasPoint(nearestBoundaryPoint(
                    rebasedRequest.atlasBoundary(), reference));
        }).toList();
    }

    public synchronized void cancelBoundaryWarp() {
        requireOpen();
        boundaryWarpRequestToken.incrementAndGet();
        boundaryWarpState = BoundaryWarpViewState.inactive();
        boundaryWarpBaseRevision = -1;
        clearOppositeRemnantEligibility();
        publish();
    }

    public synchronized BoundaryWarpViewState boundaryWarpState() {
        return boundaryWarpState;
    }

    public synchronized boolean boundaryWarpOppositeRemnantAvailable() {
        return oppositeRemnantEligibilityIsCurrent()
                && boundaryWarpOppositeRemnantAvailable;
    }

    public synchronized boolean boundaryWarpOppositeRemnantIncluded() {
        return oppositeRemnantEligibilityIsCurrent()
                && boundaryWarpOppositeRemnantIncluded;
    }

    /** Makes an already visible inactive-side border draft the editable side. */
    public synchronized void activateBoundaryWarpSide(
            final ManualHemisphereWarp2D.AtlasSide side) {
        requireOpen();
        final BoundaryWarpRequest primary = boundaryWarpState.draft()
                .orElse(null);
        if (primary != null && primary.targetSide() == side) {
            return;
        }
        final BoundaryWarpRequest secondary = boundaryWarpState
                .secondaryDraft().orElseThrow(() ->
                        new IllegalArgumentException(
                                "No visible border draft exists for " + side));
        if (secondary.targetSide() != side) {
            throw new IllegalArgumentException(
                    "No visible border draft exists for " + side);
        }
        boundaryWarpRequestToken.incrementAndGet();
        final BoundaryWarpViewState swapped = new BoundaryWarpViewState(
                false, boundaryWarpState.secondary(),
                primary == null ? BoundaryWarpSideState.empty()
                        : boundaryWarpState.primary(),
                Optional.of("Editing atlas "
                        + side.name().toLowerCase(java.util.Locale.ROOT)
                        + " border points"
                        + (boundaryWarpState.secondary().dirty()
                                ? " — Calculate preview when ready" : "")),
                boundaryWarpRecheckSuggested);
        boundaryWarpState = swapped;
        publish();
    }

    public synchronized void enterManualRefinement() {
        requireOpen();
        session.apply(new ReviewEdit.EnterManualRefinement());
        publish();
    }

    public synchronized boolean canMakeAtlasUpright() {
        if (!session.state().content().workflowMode().permitsManualEdits()) {
            return false;
        }
        try {
            return !requireUprightCandidate().equals(session.state().content());
        } catch (final RuntimeException unavailable) {
            return false;
        }
    }

    public synchronized void makeAtlasUpright() {
        requireOpen();
        final AlignmentReviewContent candidate = requireUprightCandidate();
        if (!candidate.equals(session.state().content())) {
            apply(new ReviewEdit.MakeAtlasUpright());
        }
    }

    private AlignmentReviewContent requireUprightCandidate() {
        final AlignmentReviewState current = session.state();
        final AlignmentReviewContent candidate = new ReviewEdit.MakeAtlasUpright()
                .apply(current.content(), current.basis());
        // Straightening intentionally changes inherited shear, but does not
        // waive the existing stretch or reachable-workspace limits.
        requireSafeJoinedAdjustment(candidate.manualPreviewAdjustment());
        requireJoinedIntersectsWorkspace(new AlignmentReviewState(
                current.basis(), candidate, current.contentRevision() + 1));
        return candidate;
    }

    /** Validate the manual starting placement before opening a native review. */
    public static void requireSafeInitialManualPlacement(
            final AlignmentReviewState state) {
        if (state.content().workflowMode() == ReviewWorkflowMode.MANUAL_ONLY) {
            requireSafeJoinedAdjustment(state.content().manualPreviewAdjustment());
            requireJoinedIntersectsWorkspace(state);
        }
    }

    public synchronized void translate(
            final double deltaPreviewX,
            final double deltaPreviewY) {
        applyJoinedPlacement(new ReviewEdit.Translate(
                deltaPreviewX, deltaPreviewY));
    }

    public synchronized void rotateDegrees(final double degrees) {
        if (!Double.isFinite(degrees)) {
            throw new IllegalArgumentException(
                    "Rotation must be finite");
        }
        rotateRadians(Math.toRadians(degrees), previewCenter());
    }

    public synchronized void scaleUniform(final double factor) {
        scaleUniform(factor, previewCenter());
    }

    /**
     * Applies one explicit, undoable rotation about a reviewer-selected
     * preview-space pivot. The pivot belongs to the temporary registration
     * preview; it never changes source-image pixels or metadata.
     */
    public synchronized void rotateRadians(
            final double radians,
            final Point2D previewPivot) {
        if (!Double.isFinite(radians)) {
            throw new IllegalArgumentException(
                    "Rotation must be finite");
        }
        applyJoinedPlacement(new ReviewEdit.Rotate(radians,
                Objects.requireNonNull(previewPivot, "previewPivot")));
    }

    /** Applies one explicit, undoable uniform scale about a preview pivot. */
    public synchronized void scaleUniform(
            final double factor,
            final Point2D previewPivot) {
        applyJoinedPlacement(new ReviewEdit.Scale(factor,
                Objects.requireNonNull(previewPivot, "previewPivot")));
    }

    /**
     * Applies one explicit, undoable positive scale along rotated preview
     * axes. This remains global reviewer placement, never evidence.
     */
    public synchronized void scaleAxes(
            final double scaleX,
            final double scaleY,
            final double axisRadians,
            final Point2D previewPivot) {
        applyJoinedPlacement(new ReviewEdit.ScaleAxes(
                scaleX, scaleY, axisRadians,
                Objects.requireNonNull(previewPivot, "previewPivot")));
    }

    /** Read-only live-drag check; an unsafe sample leaves the last safe one. */
    public synchronized boolean canPreviewTranslate(
            final double deltaPreviewX,
            final double deltaPreviewY) {
        return canPreviewJoinedPlacement(() -> new ReviewEdit.Translate(
                deltaPreviewX, deltaPreviewY));
    }

    /** Read-only live-drag check; an unsafe sample leaves the last safe one. */
    public synchronized boolean canPreviewScale(
            final double factor,
            final Point2D previewPivot) {
        return canPreviewJoinedPlacement(() -> new ReviewEdit.Scale(
                factor, Objects.requireNonNull(previewPivot,
                        "previewPivot")));
    }

    /** Read-only live-drag check for rotated two-axis scaling. */
    public synchronized boolean canPreviewScaleAxes(
            final double scaleX,
            final double scaleY,
            final double axisRadians,
            final Point2D previewPivot) {
        return canPreviewJoinedPlacement(() -> new ReviewEdit.ScaleAxes(
                scaleX, scaleY, axisRadians,
                Objects.requireNonNull(previewPivot, "previewPivot")));
    }

    /** Read-only live-drag check; an unsafe sample leaves the last safe one. */
    public synchronized boolean canPreviewRotate(
            final double radians,
            final Point2D previewPivot) {
        return canPreviewJoinedPlacement(() -> new ReviewEdit.Rotate(
                radians, Objects.requireNonNull(previewPivot,
                        "previewPivot")));
    }

    private boolean canPreviewJoinedPlacement(
            final Supplier<ReviewEdit> candidateEdit) {
        try {
            requireJoinedPlacementCandidate(
                    Objects.requireNonNull(candidateEdit,
                            "candidateEdit").get());
            return true;
        } catch (final RuntimeException unsafe) {
            return false;
        }
    }

    private void applyJoinedPlacement(final ReviewEdit edit) {
        requireOpen();
        requireJoinedPlacementCandidate(edit);
        session.apply(edit);
        publish();
    }

    private void requireJoinedPlacementCandidate(final ReviewEdit edit) {
        final AlignmentReviewState current = session.state();
        if (current.content().reviewSectionMode()
                == ReviewSectionMode.DISJOINED) {
            throw new IllegalStateException(
                    "Disjoined placement must target one atlas half.");
        }
        if (current.content().hemisphereWarp().isPresent()
                || current.content().localWarp().isPresent()) {
            throw new IllegalStateException(
                    "Clear warp or Undo local changes before repositioning the whole atlas.");
        }
        final AlignmentReviewContent candidateContent =
                Objects.requireNonNull(edit, "edit").apply(
                        current.content(), current.basis());
        requireSafeJoinedAdjustment(
                candidateContent.manualPreviewAdjustment());
        final AlignmentReviewState candidate;
        try {
            candidate = new AlignmentReviewState(
                    current.basis(), candidateContent,
                    current.contentRevision() + 1);
        } catch (final IllegalArgumentException invalidTransform) {
            throw new ManualWarpException(
                    org.atlasalign.application.manual.ManualWarpFailureKind.EXCESSIVE_STRETCH,
                    "That resize would squash or stretch the atlas too far, so it stayed at its last safe shape.",
                    "Reviewed joined placement failed the immutable state safety gate: "
                            + invalidTransform.getMessage());
        }
        requireSafeJoinedPlacement(current, candidate);
    }

    /** Moves one raw atlas half without changing its opposite side. */
    public synchronized void translateManualSide(
            final ManualHemisphereWarp2D.AtlasSide side,
            final double deltaX,
            final double deltaY) {
        if (deltaX == 0 && deltaY == 0) {
            return;
        }
        applyManualSideDelta(side, new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, deltaX, 0, 1, deltaY));
    }

    public synchronized boolean canPreviewTranslateManualSide(
            final ManualHemisphereWarp2D.AtlasSide side,
            final double deltaX,
            final double deltaY) {
        return canPreviewManualSideDelta(side, new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, deltaX, 0, 1, deltaY));
    }

    /** Rotates one raw atlas half about an on-canvas preview-space pivot. */
    public synchronized void rotateManualSide(
            final ManualHemisphereWarp2D.AtlasSide side,
            final double radians,
            final Point2D pivot) {
        if (!Double.isFinite(radians)) {
            throw new IllegalArgumentException("Rotation must be finite");
        }
        if (radians == 0) {
            return;
        }
        final Point2D centre = Objects.requireNonNull(pivot, "pivot");
        final double cosine = Math.cos(radians);
        final double sine = Math.sin(radians);
        applyManualSideDelta(side, new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                cosine, -sine,
                centre.x() - cosine * centre.x() + sine * centre.y(),
                sine, cosine,
                centre.y() - sine * centre.x() - cosine * centre.y()));
    }

    public synchronized boolean canPreviewRotateManualSide(
            final ManualHemisphereWarp2D.AtlasSide side,
            final double radians,
            final Point2D pivot) {
        if (!Double.isFinite(radians)) {
            return false;
        }
        final Point2D centre = Objects.requireNonNull(pivot, "pivot");
        final double cosine = Math.cos(radians);
        final double sine = Math.sin(radians);
        return canPreviewManualSideDelta(side, new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                cosine, -sine,
                centre.x() - cosine * centre.x() + sine * centre.y(),
                sine, cosine,
                centre.y() - sine * centre.x() - cosine * centre.y()));
    }

    /** Uniformly scales one raw atlas half about an on-canvas pivot. */
    public synchronized void scaleManualSide(
            final ManualHemisphereWarp2D.AtlasSide side,
            final double factor,
            final Point2D pivot) {
        if (!Double.isFinite(factor) || factor <= 0) {
            throw new IllegalArgumentException(
                    "Scale must be a positive finite value");
        }
        if (factor == 1) {
            return;
        }
        final Point2D centre = Objects.requireNonNull(pivot, "pivot");
        applyManualSideDelta(side, new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                factor, 0, centre.x() * (1 - factor),
                0, factor, centre.y() * (1 - factor)));
    }

    public synchronized boolean canPreviewScaleManualSide(
            final ManualHemisphereWarp2D.AtlasSide side,
            final double factor,
            final Point2D pivot) {
        if (!Double.isFinite(factor) || factor <= 0) {
            return false;
        }
        final Point2D centre = Objects.requireNonNull(pivot, "pivot");
        return canPreviewManualSideDelta(side, new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                factor, 0, centre.x() * (1 - factor),
                0, factor, centre.y() * (1 - factor)));
    }

    /** Scales one Disjoined atlas half along rotated preview-space axes. */
    public synchronized void scaleManualSideAxes(
            final ManualHemisphereWarp2D.AtlasSide side,
            final double scaleX,
            final double scaleY,
            final double axisRadians,
            final Point2D pivot) {
        if (scaleX == 1 && scaleY == 1) {
            return;
        }
        applyManualSideDelta(side, axesScaleDelta(
                scaleX, scaleY, axisRadians, pivot));
    }

    public synchronized boolean canPreviewScaleManualSideAxes(
            final ManualHemisphereWarp2D.AtlasSide side,
            final double scaleX,
            final double scaleY,
            final double axisRadians,
            final Point2D pivot) {
        if (scaleX == 1 && scaleY == 1) {
            return true;
        }
        try {
            return canPreviewManualSideDelta(side, axesScaleDelta(
                    scaleX, scaleY, axisRadians, pivot));
        } catch (final RuntimeException unsafe) {
            return false;
        }
    }

    private static AffineTransform2D axesScaleDelta(
            final double scaleX,
            final double scaleY,
            final double axisRadians,
            final Point2D pivot) {
        if (!Double.isFinite(scaleX) || !Double.isFinite(scaleY)
                || !Double.isFinite(axisRadians)
                || scaleX <= 0 || scaleY <= 0) {
            throw new IllegalArgumentException(
                    "Axis scale factors must be positive and finite");
        }
        final Point2D centre = Objects.requireNonNull(pivot, "pivot");
        final double cosine = Math.cos(axisRadians);
        final double sine = Math.sin(axisRadians);
        final double m00 = cosine * cosine * scaleX
                + sine * sine * scaleY;
        final double m01 = cosine * sine * (scaleX - scaleY);
        final double m10 = m01;
        final double m11 = sine * sine * scaleX
                + cosine * cosine * scaleY;
        return new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                m00, m01,
                centre.x() - m00 * centre.x() - m01 * centre.y(),
                m10, m11,
                centre.y() - m10 * centre.x() - m11 * centre.y());
    }

    private boolean canPreviewManualSideDelta(
            final ManualHemisphereWarp2D.AtlasSide side,
            final AffineTransform2D delta) {
        try {
            requireManualSidePlacementCandidate(side, delta);
            return true;
        } catch (final RuntimeException unsafe) {
            return false;
        }
    }

    private void applyManualSideDelta(
            final ManualHemisphereWarp2D.AtlasSide side,
            final AffineTransform2D delta) {
        requireOpen();
        final ManualHemisphereWarp2D.AtlasSide checkedSide = Objects
                .requireNonNull(side, "side");
        final ManualSidePlacement2D updated =
                requireManualSidePlacementCandidate(checkedSide, delta);
        manualWarpRequestToken.incrementAndGet();
        apply(new ReviewEdit.SetManualSidePlacement(checkedSide, updated));
    }

    private ManualSidePlacement2D requireManualSidePlacementCandidate(
            final ManualHemisphereWarp2D.AtlasSide side,
            final AffineTransform2D delta) {
        final AlignmentReviewState current = session.state();
        if (current.content().reviewSectionMode()
                != ReviewSectionMode.DISJOINED) {
            throw new IllegalStateException(
                    "Independent half placement is available only in Disjoined mode.");
        }
        if (current.content().hemisphereWarp().isPresent()
                || current.content().localWarp().isPresent()) {
            throw new IllegalStateException(
                    "Clear warp or Undo local changes before repositioning the atlas half.");
        }
        final ManualHemisphereWarp2D.AtlasSide checkedSide = Objects
                .requireNonNull(side, "side");
        final ManualSidePlacement2D placement = current.content()
                .manualSidePlacement();
        final ManualSidePlacement2D updated = placement.withTransform(
                checkedSide, placement.transform(checkedSide).andThen(
                        Objects.requireNonNull(delta, "delta")));
        final AffineTransform2D shared = current.preOutlineAtlasToPreview();
        requirePreservedPlacedAxes(
                shared, shared.andThen(updated.transform(checkedSide)),
                "Disjoined " + checkedSide + " placement");
        requireSideIntersectsWorkspace(current, checkedSide, updated);
        return updated;
    }

    private static void requireSafeJoinedPlacement(
            final AlignmentReviewState reference,
            final AlignmentReviewState candidate) {
        final AffineTransform2D adjustment = candidate.content()
                .manualPreviewAdjustment();
        requireSafeJoinedAdjustment(adjustment);
        requirePreservedPlacedAxes(
                reference.preOutlineAtlasToPreview(),
                candidate.preOutlineAtlasToPreview(),
                "Joined placement");

        requireJoinedIntersectsWorkspace(candidate);
    }

    private static void requireJoinedIntersectsWorkspace(
            final AlignmentReviewState candidate) {
        final double atlasWidth = candidate.basis().atlas().atlasPlaneWidth();
        final double atlasHeight = candidate.basis().atlas().atlasPlaneHeight();
        final java.util.List<Point2D> atlasCorners = java.util.List.of(
                new Point2D(0, 0), new Point2D(atlasWidth - 1, 0),
                new Point2D(atlasWidth - 1, atlasHeight - 1),
                new Point2D(0, atlasHeight - 1));
        double minimumX = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        for (final Point2D atlas : atlasCorners) {
            final Point2D placed = candidate
                    .mapAtlasBeforeHemisphereWarp(atlas);
            minimumX = Math.min(minimumX, placed.x());
            maximumX = Math.max(maximumX, placed.x());
            minimumY = Math.min(minimumY, placed.y());
            maximumY = Math.max(maximumY, placed.y());
        }
        final double previewWidth = candidate.basis()
                .previewDimensions().width();
        final double previewHeight = candidate.basis()
                .previewDimensions().height();
        final double previewDiagonal = Math.hypot(
                previewWidth, previewHeight);
        final double padding = 0.10 * previewDiagonal;
        if (maximumX < -padding
                || minimumX > previewWidth - 1 + padding
                || maximumY < -padding
                || minimumY > previewHeight - 1 + padding) {
            throw new ManualWarpException(
                    org.atlasalign.application.manual.ManualWarpFailureKind.OUTSIDE_WORKSPACE,
                    "That move would take the atlas outside the editable image, so it stayed at its last safe placement.",
                    "Joined atlas bounds escaped the padded preview workspace");
        }
    }

    private static void requireSafeJoinedAdjustment(
            final AffineTransform2D adjustment) {
        final double firstSquared = adjustment.m00() * adjustment.m00()
                + adjustment.m10() * adjustment.m10();
        final double secondSquared = adjustment.m01() * adjustment.m01()
                + adjustment.m11() * adjustment.m11();
        final double determinant = adjustment.determinant();
        final double trace = firstSquared + secondSquared;
        final double discriminant = Math.sqrt(Math.max(0,
                trace * trace
                        - 4 * determinant * determinant));
        final double maximumScale = Math.sqrt(
                (trace + discriminant) * 0.5);
        final double minimumScale = Math.sqrt(Math.max(0,
                (trace - discriminant) * 0.5));
        if (!Double.isFinite(minimumScale)
                || !Double.isFinite(maximumScale)
                || adjustment.determinant() < 0.20
                || minimumScale < 0.30 || maximumScale > 3.0
                || maximumScale / minimumScale > 3.0) {
            throw new ManualWarpException(
                    org.atlasalign.application.manual.ManualWarpFailureKind.EXCESSIVE_STRETCH,
                    "That resize would squash or stretch the atlas too far, so it stayed at its last safe shape.",
                    "Joined placement must be orientation-preserving, have determinant at least 0.20, singular values from 0.30 to 3.0, and anisotropy at most 3.0");
        }
    }

    private static void requirePreservedPlacedAxes(
            final AffineTransform2D reference,
            final AffineTransform2D candidate,
            final String context) {
        try {
            AlignmentReviewState.requirePreservedAtlasAxisShear(
                    reference, candidate, context);
        } catch (final IllegalArgumentException changedShear) {
            throw new ManualWarpException(
                    org.atlasalign.application.manual.ManualWarpFailureKind.EXCESSIVE_STRETCH,
                    "That resize would skew the atlas, so it stayed at its last safe shape.",
                    changedShear.getMessage());
        }
    }

    private void requireSideIntersectsWorkspace(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide side,
            final ManualSidePlacement2D placement) {
        final double width = state.basis().atlas().atlasPlaneWidth();
        final double height = state.basis().atlas().atlasPlaneHeight();
        final double middle = (width - 1) * 0.5;
        final double firstX = side == ManualHemisphereWarp2D.AtlasSide.LEFT
                ? 0 : middle;
        final double lastX = side == ManualHemisphereWarp2D.AtlasSide.LEFT
                ? middle : width - 1;
        double minimumX = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        double maximumDisplacement = 0;
        final java.util.List<Point2D> placedCorners =
                new java.util.ArrayList<>(4);
        for (final Point2D atlas : java.util.List.of(
                new Point2D(firstX, 0), new Point2D(lastX, 0),
                new Point2D(lastX, height - 1),
                new Point2D(firstX, height - 1))) {
            final Point2D baseline = state.mapAtlasBeforeSidePlacement(atlas);
            final Point2D mapped = placement.apply(side, baseline);
            placedCorners.add(mapped);
            minimumX = Math.min(minimumX, mapped.x());
            maximumX = Math.max(maximumX, mapped.x());
            minimumY = Math.min(minimumY, mapped.y());
            maximumY = Math.max(maximumY, mapped.y());
            maximumDisplacement = Math.max(maximumDisplacement,
                    Math.hypot(mapped.x() - baseline.x(),
                            mapped.y() - baseline.y()));
        }
        final double previewWidth = state.basis().previewDimensions().width();
        final double previewHeight = state.basis().previewDimensions().height();
        final double previewDiagonal = Math.hypot(
                previewWidth, previewHeight);
        if (maximumDisplacement > 0.25 * previewDiagonal) {
            throw new ManualWarpException(
                    org.atlasalign.application.manual.ManualWarpFailureKind.EXCESSIVE_DISPLACEMENT,
                    "That half moved too far from its starting position, so it stayed at the last safe placement.",
                    "maximumSidePlacementDisplacement="
                            + maximumDisplacement + "; previewDiagonal="
                            + previewDiagonal);
        }
        final double padding = 0.10 * previewDiagonal;
        if (maximumX < -padding || minimumX > previewWidth - 1 + padding
                || maximumY < -padding
                || minimumY > previewHeight - 1 + padding) {
            throw new ManualWarpException(
                    org.atlasalign.application.manual.ManualWarpFailureKind.OUTSIDE_WORKSPACE,
                    "That half would leave the editable image, so it stayed at its last safe position.",
                    "Disjoined side bounds escaped the padded preview workspace");
        }
        final Optional<BinaryMask> reviewedSupport = state.content()
                .reviewedTissueSupport()
                .map(ReviewedTissueSupport::supportMask);
        final Optional<BinaryMask> placementSupport = reviewedSupport.isPresent()
                ? reviewedSupport
                : state.basis().segmentation().map(segmentation ->
                        new TissueMaskEnvelope()
                                .fillInteriorHoles(segmentation.mask()));
        placementSupport.ifPresent(support -> {
            if (!placedHalfIntersectsTissueSupport(
                    placedCorners, support)) {
                throw new ManualWarpException(
                    org.atlasalign.application.manual.ManualWarpFailureKind.OUTSIDE_WORKSPACE,
                    "That half no longer overlaps the reviewed tissue crop, so it stayed at its last safe position.",
                    "Disjoined half quadrilateral has no reviewed tissue-support intersection");
            }
        });
    }

    private static boolean placedHalfIntersectsTissueSupport(
            final java.util.List<Point2D> corners,
            final BinaryMask support) {
        if (support.isEmpty()) {
            return false;
        }
        final java.awt.geom.Path2D.Double polygon =
                new java.awt.geom.Path2D.Double();
        polygon.moveTo(corners.get(0).x(), corners.get(0).y());
        for (int index = 1; index < corners.size(); index++) {
            polygon.lineTo(corners.get(index).x(), corners.get(index).y());
        }
        polygon.closePath();
        final var polygonBounds = polygon.getBounds2D();
        final var supportBounds = support.bounds();
        final int minimumX = Math.max(supportBounds.minimumX(),
                Math.max(0, (int) Math.floor(polygonBounds.getMinX())));
        final int maximumX = Math.min(supportBounds.maximumX(),
                Math.min(support.width() - 1,
                        (int) Math.ceil(polygonBounds.getMaxX())));
        final int minimumY = Math.max(supportBounds.minimumY(),
                Math.max(0, (int) Math.floor(polygonBounds.getMinY())));
        final int maximumY = Math.min(supportBounds.maximumY(),
                Math.min(support.height() - 1,
                        (int) Math.ceil(polygonBounds.getMaxY())));
        if (minimumX > maximumX || minimumY > maximumY) {
            return false;
        }
        final java.util.BitSet bits = support.copyBits();
        for (int y = minimumY; y <= maximumY; y++) {
            final int rowStart = y * support.width() + minimumX;
            final int rowEnd = y * support.width() + maximumX + 1;
            for (int bit = bits.nextSetBit(rowStart);
                    bit >= 0 && bit < rowEnd;
                    bit = bits.nextSetBit(bit + 1)) {
                final int x = bit - y * support.width();
                if (polygon.contains(x + 0.5, y + 0.5)) {
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized void setOrientation(
            final AtlasOrientation orientation) {
        if (session.state().content().orientation()
                == Objects.requireNonNull(orientation, "orientation")) {
            return;
        }
        apply(new ReviewEdit.SetAtlasOrientation(orientation));
    }

    public synchronized void setObservedHemisphere(
            final ObservedAnatomicalHemisphere hemisphere) {
        apply(new ReviewEdit.SetObservedHemisphere(hemisphere));
    }

    /** Persists the reviewer-approved Half atlas-side footprint. */
    public synchronized void setHalfAtlasCoverage(
            final HalfAtlasCoverage coverage) {
        requireOpen();
        final HalfAtlasCoverage checked = Objects.requireNonNull(
                coverage, "coverage");
        final var current = session.state().content();
        if (current.halfAtlasCoverage() == checked) {
            return;
        }
        if (checked.includesOppositeRemnant()
                && !boundaryWarpOppositeRemnantAvailable()) {
            throw new IllegalStateException(
                    "The current placement has no distributed opposite-side tissue remnant to include.");
        }
        boundaryFitRequestToken.incrementAndGet();
        boundaryWarpRequestToken.incrementAndGet();
        boundaryFitState = staleBoundaryFitState();
        boundaryFitBaseRevision = -1;
        boundaryWarpState = staleBoundaryWarpState();
        boundaryWarpBaseRevision = -1;
        session.apply(new ReviewEdit.SetHalfAtlasCoverage(checked));
        publish();
    }

    /** Resolves a fixed Phase 5 target from the already verified ontology. */
    public synchronized Optional<SelectedAtlasRegion>
            resolveExactAtlasRegion(final String acronym) {
        requireOpen();
        return regionCatalog.flatMap(
                catalog -> catalog.resolveExactAcronym(acronym));
    }

    public synchronized java.util.List<SelectedAtlasRegion>
            searchAtlasRegions(final String query, final int maximumResults) {
        requireOpen();
        if (maximumResults <= 0) {
            throw new IllegalArgumentException(
                    "Maximum ontology search results must be positive");
        }
        return regionCatalog.map(catalog -> catalog.search(
                Objects.requireNonNull(query, "query"), maximumResults))
                .orElse(java.util.List.of());
    }

    public synchronized java.util.List<org.atlasalign.atlas.AtlasRegion> atlasHierarchy() {
        return regionCatalog.map(AtlasRegionCatalog::hierarchy).orElse(java.util.List.of());
    }

    /**
     * Loads a contour-only coronal reference without changing the current
     * review level, tilt, transform, history, or automatic proposal.
     * The callback is delivered on the configured view executor.
     */
    public synchronized void loadNeutralAtlasReference(
            final int zeroBasedAnteriorPosteriorIndex,
            final java.util.function.Consumer<AtlasCoronalPlane> onLoaded,
            final java.util.function.Consumer<String> onFailure) {
        loadNeutralAtlasReference(
                zeroBasedAnteriorPosteriorIndex,
                AtlasPlaneTilt.CORONAL,
                onLoaded,
                onFailure);
    }

    /**
     * Loads one reviewer-requested oblique atlas reference without changing
     * the review state or automatic proposal. This is display/search input
     * only; choosing it remains an explicit later manual action.
     */
    public synchronized void loadNeutralAtlasReference(
            final int zeroBasedAnteriorPosteriorIndex,
            final AtlasPlaneTilt tilt,
            final java.util.function.Consumer<AtlasCoronalPlane> onLoaded,
            final java.util.function.Consumer<String> onFailure) {
        requireOpen();
        final AllenCoronalLevel level = new AllenCoronalLevel(
                zeroBasedAnteriorPosteriorIndex);
        final AtlasPlaneTilt requestedTilt = Objects.requireNonNull(
                tilt, "tilt");
        final java.util.function.Consumer<AtlasCoronalPlane> loadedCallback =
                Objects.requireNonNull(onLoaded, "onLoaded");
        final java.util.function.Consumer<String> failureCallback =
                Objects.requireNonNull(onFailure, "onFailure");
        final AtlasPlaneRequest request = new AtlasPlaneRequest(
                level, requestedTilt, false);
        planeExecutor.execute(() -> {
            try {
                final AtlasCoronalPlane loaded = Objects.requireNonNull(
                        planeSource.load(request),
                        "Atlas plane source returned null");
                if (loaded.zeroBasedAnteriorPosteriorIndex()
                        != zeroBasedAnteriorPosteriorIndex) {
                    throw new IllegalStateException(
                            "Atlas plane source returned the wrong reference level");
                }
                viewExecutor.execute(() -> {
                    synchronized (ReviewController.this) {
                        if (closed) {
                            return;
                        }
                    }
                    loadedCallback.accept(loaded);
                });
            } catch (final RuntimeException error) {
                viewExecutor.execute(() -> {
                    synchronized (ReviewController.this) {
                        if (closed) {
                            return;
                        }
                    }
                    failureCallback.accept(messageOf(error));
                });
            }
        });
    }

    public synchronized void selectAtlasRegionExactAcronym(
            final String acronym) {
        requireOpen();
        final SelectedAtlasRegion resolved = resolveExactAtlasRegion(acronym)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Verified atlas ontology has no exact region "
                                + acronym));
        if (selectedAtlasRegion.equals(Optional.of(resolved))) {
            return;
        }
        if (structureAdjustmentState.active()) {
            throw new IllegalStateException(
                    "Apply, Cancel, or Skip the current Structure request before selecting another anatomy target.");
        }
        discardStructureAdjustmentDraft();
        selectedAtlasRegion = Optional.of(resolved);
        selectedAtlasContour = atlasPlane == null
                ? Optional.empty()
                : Optional.of(SelectedAtlasContour.from(
                        atlasPlane, resolved));
        publish();
    }

    public synchronized void clearSelectedAtlasRegion() {
        requireOpen();
        if (selectedAtlasRegion.isEmpty()) {
            return;
        }
        if (structureAdjustmentState.active()) {
            throw new IllegalStateException(
                    "Apply, Cancel, or Skip the current Structure request before clearing the anatomy target.");
        }
        discardStructureAdjustmentDraft();
        selectedAtlasRegion = Optional.empty();
        selectedAtlasContour = Optional.empty();
        publish();
    }

    /**
     * Seeds draggable tissue endpoints along the selected atlas boundary as
     * one undoable review edit. Atlas endpoints stay fixed on that contour;
     * the reviewer drags the matching tissue endpoints before fitting.
     */
    public synchronized java.util.List<String> addSelectedTargetHandles(
            final int requestedCount) {
        throw new IllegalArgumentException(
                "Choose atlas left or atlas right explicitly; manual warp controls are side-local geometry, not landmarks");
    }

    /**
     * Seeds verified-boundary handles on exactly one atlas anatomical side.
     * The side remains atlas-relative under explicit display reflection.
     */
    public synchronized java.util.List<String> addSelectedTargetHandles(
            final int requestedCount,
            final AtlasAnatomicalSide side) {
        return replaceSelectedTargetHandles(requestedCount, side);
    }

    /**
     * Replaces, rather than accumulates, the selected target's exact-plane
     * handles on one atlas-anatomical side. The replacement is one immutable,
     * undoable review edit; generic points, CHECK points, the opposite side,
     * and other planes are preserved.
     */
    public synchronized java.util.List<String> replaceSelectedTargetHandles(
            final int requestedCount,
            final AtlasAnatomicalSide side) {
        if (Objects.requireNonNull(side, "side")
                == AtlasAnatomicalSide.MIDLINE) {
            throw new IllegalArgumentException(
                    "The anatomical midline is fixed in hemisphere refinement; use corpus callosum as a visual or CHECK reference");
        }
        return replaceSelectedStructureControls(requestedCount,
                side == AtlasAnatomicalSide.ATLAS_LEFT
                        ? ManualHemisphereWarp2D.AtlasSide.LEFT
                        : ManualHemisphereWarp2D.AtlasSide.RIGHT);
    }

    /**
     * Replaces one selected-structure control group on one anatomical side.
     * Mesh construction and all safety audits run away from Swing's EDT; the
     * immutable result is installed only if every captured identity is still
     * current.
     */
    public synchronized java.util.List<String>
            replaceSelectedStructureControls(
            final int requestedCount,
            final ManualHemisphereWarp2D.AtlasSide atlasSide) {
        final AlignmentReviewState captured = session.state();
        requireIncludedManualControlSide(captured, atlasSide);
        final SelectedAtlasContour contour = requireHandleSeedContour();
        requireRefinementControlCount(requestedCount);
        final AtlasAnatomicalSide legacySide = atlasSide
                == ManualHemisphereWarp2D.AtlasSide.LEFT
                ? AtlasAnatomicalSide.ATLAS_LEFT
                : AtlasAnatomicalSide.ATLAS_RIGHT;
        final String structureAcronym = contour.region().acronym();
        final java.util.List<ManualWarpControl> retained =
                retainedControlsExceptStructure(captured, atlasSide,
                        structureAcronym);
        final java.util.Set<Point2D> occupiedSources = retained.stream()
                .filter(control -> control.atlasSide() == atlasSide)
                .map(ManualWarpControl::sourcePoint)
                .collect(java.util.stream.Collectors.toSet());
        final java.util.List<Point2D> atlasPoints =
                unoccupiedPrincipalExteriorPoints(
                        contour, requestedCount, legacySide, captured,
                        occupiedSources);
        if (atlasPoints.size() != requestedCount) {
            throw new IllegalArgumentException(
                    "Selected atlas target has only " + atlasPoints.size()
                            + " unique boundary controls on " + atlasSide);
        }
        final String groupId = structureGroupId(structureAcronym);
        final java.util.List<ManualWarpControl> replacements =
                new java.util.ArrayList<>(requestedCount);
        long next = nextManualControlSuffix();
        for (final Point2D atlasPoint : atlasPoints) {
            final Point2D source = captured
                    .mapAtlasBeforeHemisphereWarp(atlasPoint);
            final Point2D target = captured.mapAtlasToPreview(atlasPoint);
            replacements.add(new ManualWarpControl(
                    "manual-control-" + (++next), atlasSide,
                    ManualWarpControlOrigin.STRUCTURE_GUIDE,
                    groupId, structureAcronym, source, target));
        }
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(captured);
        final java.util.Map<String, Integer> components =
                structureComponentAssignments(contour, legacySide,
                        replacements, atlasPoints);
        final java.util.List<StructureOutlinePath> outlinePaths =
                structureOutlinePaths(contour, legacySide, atlasPoints,
                        replacements);
        final java.util.List<ManualWarpControl> all =
                new java.util.ArrayList<>(retained);
        all.addAll(replacements);
        requireSideCapacity(all);
        final StructureAdjustmentDraft draft = newStructureAdjustmentDraft(
                captured.contentRevision(), precondition, atlasSide,
                structureAcronym, contour.boundarySha256(), replacements,
                components, outlinePaths, 1);
        structureAdjustmentBaseRevision = captured.contentRevision();
        setDirtyStructureAdjustmentDraft(draft,
                "1 change pending — Calculate structure preview");
        return replacements.stream().map(ManualWarpControl::id).toList();
    }

    /**
     * Keeps structure controls distributed around the complete principal
     * components even when the side already contains border or interior
     * controls. Sampling {@code requestedCount + occupiedSources.size()} and
     * truncating the result biases every replacement toward the beginning of
     * each ordered loop because unrelated controls almost never coincide with
     * those candidates. Start with the exact requested density instead. Only
     * a genuine source-point collision activates a denser deterministic pass,
     * which is then evenly reduced across its complete ordered result.
     */
    private static java.util.List<Point2D>
            unoccupiedPrincipalExteriorPoints(
            final SelectedAtlasContour contour,
            final int requestedCount,
            final AtlasAnatomicalSide side,
            final AlignmentReviewState state,
            final java.util.Set<Point2D> occupiedSources) {
        final java.util.List<Point2D> requested = contour
                .samplePrincipalExteriorPoints(requestedCount, side);
        final java.util.List<Point2D> eligibleRequested = requested.stream()
                .filter(point -> !occupiedSources.contains(
                        state.mapAtlasBeforeHemisphereWarp(point)))
                .distinct().toList();
        if (eligibleRequested.size() == requestedCount) {
            return eligibleRequested;
        }

        final int candidateCount = Math.max(requestedCount * 8,
                requestedCount + occupiedSources.size() * 2);
        final java.util.List<Point2D> eligible = contour
                .samplePrincipalExteriorPoints(candidateCount, side).stream()
                .filter(point -> !occupiedSources.contains(
                        state.mapAtlasBeforeHemisphereWarp(point)))
                .distinct().toList();
        if (eligible.size() < requestedCount) {
            return eligible;
        }
        final java.util.List<Point2D> result =
                new java.util.ArrayList<>(requestedCount);
        for (int sample = 0; sample < requestedCount; sample++) {
            final int index = Math.min(eligible.size() - 1,
                    (int) Math.floor((sample + 0.5)
                            * eligible.size() / requestedCount));
            result.add(eligible.get(index));
        }
        return java.util.List.copyOf(result);
    }

    /** Applies freely editable dot targets without running the warp solver. */
    public synchronized void transformSelectedStructureControls(
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final java.util.List<String> controlIds,
            final java.util.List<Point2D> requestedTargets) {
        requireManualControlContext();
        final AlignmentReviewState captured = session.state();
        requireIncludedManualControlSide(captured, atlasSide);
        final String acronym = requireHandleSeedContour().region().acronym();
        final StructureAdjustmentDraft currentDraft =
                requireOrAdoptStructureAdjustmentDraft(
                        captured, atlasSide, acronym);
        final java.util.List<String> ids = java.util.List.copyOf(
                Objects.requireNonNull(controlIds, "controlIds"));
        final java.util.List<Point2D> targets = java.util.List.copyOf(
                Objects.requireNonNull(requestedTargets,
                        "requestedTargets"));
        if (ids.isEmpty() || targets.size() != ids.size()
                || new java.util.HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException(
                    "A structure edit must provide one target for every moved structure control");
        }
        final java.util.Map<String, Point2D> requestedById =
                new java.util.LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            requestedById.put(ids.get(index), targets.get(index));
        }
        final java.util.Set<String> currentIds = currentDraft
                .requestedControls().stream()
                .map(ManualWarpControl::id)
                .collect(java.util.stream.Collectors.toSet());
        if (!currentIds.containsAll(requestedById.keySet())) {
            throw new IllegalArgumentException(
                    "Structure requested controls changed before the gesture completed");
        }
        final java.util.List<ManualWarpControl> absoluteTargets =
                new java.util.ArrayList<>(
                        currentDraft.requestedControls().size());
        boolean changed = false;
        for (final ManualWarpControl control
                : currentDraft.requestedControls()) {
            final Point2D requested = requestedById.get(control.id());
            if (requested == null || requested.equals(control.targetPoint())) {
                absoluteTargets.add(control);
                continue;
            }
            absoluteTargets.add(copyControlWithTarget(control, requested));
            changed = true;
        }
        if (!changed) {
            return;
        }
        final java.util.Map<String, Point2D> offsets =
                offsetsForAbsoluteTargets(currentDraft, absoluteTargets,
                        currentDraft.thicknessPercent(),
                        currentDraft.bladeGapPercent());
        final StructureAdjustmentDraft updated =
                structureDraftWithAbsoluteTargets(currentDraft,
                        absoluteTargets, currentDraft.thicknessPercent(),
                        currentDraft.bladeGapPercent(), offsets,
                        currentDraft.pendingEditCount() + 1);
        setDirtyStructureAdjustmentDraft(updated,
                pendingStructureChangesMessage(updated));
    }

    /** Updates both sliders from immutable starts without running a solve. */
    public synchronized void setStructureAdjustmentSliders(
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final int thicknessPercent,
            final int bladeGapPercent) {
        requireManualControlContext();
        if (thicknessPercent < 75 || thicknessPercent > 300
                || bladeGapPercent < -50 || bladeGapPercent > 100) {
            throw new IllegalArgumentException(
                    "ROI thickness must be 75–300% and Blade gap must be −50–100%.");
        }
        final AlignmentReviewState captured = session.state();
        requireIncludedManualControlSide(captured, atlasSide);
        final String acronym = requireHandleSeedContour().region().acronym();
        final StructureAdjustmentDraft draft =
                requireOrAdoptStructureAdjustmentDraft(
                        captured, atlasSide, acronym);
        if (bladeGapPercent != 0 && !draft.hasTwoPrincipalComponents()) {
            throw new IllegalStateException(
                    "Blade gap needs two recognized structure blade groups on this atlas side.");
        }
        if (thicknessPercent == draft.thicknessPercent()
                && bladeGapPercent == draft.bladeGapPercent()) {
            return;
        }
        final StructureAdjustmentDraft updated = withStructureRequest(
                draft, thicknessPercent, bladeGapPercent,
                draft.manualOffsetsByControlId(),
                draft.pendingEditCount() + 1);
        setDirtyStructureAdjustmentDraft(updated,
                pendingStructureChangesMessage(updated));
    }

    /** Installs the exact audited Structure candidate as one Undo revision. */
    public synchronized void applyStructureChanges() {
        requireOpen();
        final StructureAdjustmentDraft draft = structureAdjustmentState
                .draft().orElseThrow(() -> new IllegalStateException(
                        "Set points or edit the selected structure first."));
        final StructureAdjustmentCandidate candidate =
                structureAdjustmentState.candidate().orElseThrow(() ->
                        new IllegalStateException(
                                "Calculate a valid Structure preview before applying."));
        if (structureAdjustmentState.loading()
                || !candidate.draftHash().equals(draft.inputHash())
                || session.state().contentRevision()
                        != draft.contentRevision()) {
            throw new IllegalStateException(
                    "The requested shape changed; calculate it again before applying.");
        }
        draft.precondition().requireMatches(session.state().content(),
                session.state().basis());
        final java.util.List<String> installedIds = structureControls(
                session.state(), draft.atlasSide(),
                draft.structureAcronym()).stream()
                .map(ManualWarpControl::id).toList();
        final java.util.List<String> baselineIds = draft.baselineControls()
                .stream().map(ManualWarpControl::id).toList();
        final ReviewEdit edit = installedIds.equals(baselineIds)
                ? new ReviewEdit.TransformStructureWarpControlGroup(
                        draft.atlasSide(), draft.structureAcronym(),
                        candidate.validControls(),
                        draft.precondition(), candidate.auditedWarp())
                : new ReviewEdit.ReplaceStructureWarpControlGroup(
                        draft.atlasSide(), draft.structureAcronym(),
                        candidate.validControls(), draft.precondition(),
                        candidate.auditedWarp());
        if (!session.applyIfCurrentRevision(draft.contentRevision(), edit)) {
            throw new IllegalStateException(
                    "The review changed before Structure Apply; preview again.");
        }
        if (candidate.completesRequest()) {
            invalidateStructureAdjustmentAudits();
            structureAdjustmentState = StructureAdjustmentViewState
                    .inactive();
            structureAdjustmentBaseRevision = -1;
            manualWarpStatusMessage = Optional.of(
                    "Applied Structure changes as one audited Undo step.");
            publish();
            return;
        }

        final AlignmentReviewState rebased = session.state();
        final StructureAdjustmentDraft remaining = rebaseStructureDraft(
                draft, candidate.validControls(), rebased);
        structureAdjustmentBaseRevision = rebased.contentRevision();
        final long completeUnits = candidate.retainedFractionByUnitId()
                .values().stream().filter(value -> value >= 1 - 1e-12)
                .count();
        manualWarpStatusMessage = Optional.of(
                "Applied the calculated valid preview; " + completeUnits
                        + " of " + draft.units().size()
                        + " dot units reached the complete request. The remainder is still editable.");
        setDirtyStructureAdjustmentDraft(remaining,
                pendingStructureChangesMessage(remaining));
    }

    /** Runs the one explicit complete-field calculation for the exact draft. */
    public synchronized void calculateStructureAdjustmentPreview() {
        requireOpen();
        final StructureAdjustmentDraft draft = structureAdjustmentState
                .draft().orElseThrow(() -> new IllegalStateException(
                        "Set Structure points before calculating a preview."));
        if (structureAdjustmentState.loading()) {
            return;
        }
        final AlignmentReviewState captured = session.state();
        if (captured.contentRevision() != draft.contentRevision()) {
            throw new IllegalStateException(
                    "Review geometry changed; create the Structure request again.");
        }
        submitStructureAdjustmentAudit(captured, draft);
    }

    /** Makes the locally limited valid result the editable request, no edit. */
    public synchronized void useValidStructurePreviewAsDraft() {
        requireOpen();
        final StructureAdjustmentDraft draft = structureAdjustmentState
                .draft().orElseThrow(() -> new IllegalStateException(
                        "No Structure request is active."));
        final StructureAdjustmentCandidate candidate =
                structureAdjustmentState.candidate().orElseThrow(() ->
                        new IllegalStateException(
                                "Calculate a valid Structure preview first."));
        if (structureAdjustmentState.loading()
                || !candidate.draftHash().equals(draft.inputHash())) {
            throw new IllegalStateException(
                    "The requested shape changed; calculate it again first.");
        }
        final java.util.Map<String, Point2D> offsets =
                offsetsForAbsoluteTargets(draft, candidate.validControls(),
                        draft.thicknessPercent(), draft.bladeGapPercent());
        final StructureAdjustmentDraft adopted =
                structureDraftWithAbsoluteTargets(draft,
                        candidate.validControls(), draft.thicknessPercent(),
                        draft.bladeGapPercent(), offsets, 0);
        final StructureAdjustmentCandidate rebound =
                new StructureAdjustmentCandidate(adopted.inputHash(),
                        candidate.validControls(), candidate.auditedWarp(),
                        candidate.retainedFractionByUnitId().keySet().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        java.util.function.Function.identity(),
                                        ignored -> 1.0,
                                        (first, second) -> first,
                                        java.util.LinkedHashMap::new)),
                        java.util.List.of(), Optional.empty());
        structureAdjustmentState = new StructureAdjustmentViewState(false,
                Optional.of(adopted), Optional.of(rebound),
                java.util.List.of(), Optional.of(
                        "Calculated valid preview is now the requested shape; Apply or keep editing."));
        publish();
    }

    /** Resets only the highlighted pair or unpaired dot to its amber start. */
    public synchronized void resetHighlightedStructureUnit() {
        requireOpen();
        final StructureAdjustmentDraft draft = structureAdjustmentState
                .draft().orElseThrow(() -> new IllegalStateException(
                        "No Structure request is active."));
        final String unitId = structureAdjustmentState.highlightedUnitId()
                .orElseThrow(() -> new IllegalStateException(
                        "Calculate first to identify a limiting pair or dot."));
        final StructureAdjustmentUnit unit = draft.unit(unitId);
        final java.util.List<ManualWarpControl> absoluteTargets =
                new java.util.ArrayList<>(draft.requestedControls());
        final java.util.Map<String, ManualWarpControl> starts =
                controlsById(draft.baselineControls());
        for (int index = 0; index < absoluteTargets.size(); index++) {
            final ManualWarpControl current = absoluteTargets.get(index);
            if (unit.controlIds().contains(current.id())) {
                absoluteTargets.set(index, copyControlWithTarget(current,
                        starts.get(current.id()).targetPoint()));
            }
        }
        final java.util.Map<String, Point2D> offsets =
                offsetsForAbsoluteTargets(draft, absoluteTargets,
                        draft.thicknessPercent(), draft.bladeGapPercent());
        final StructureAdjustmentDraft reset =
                structureDraftWithAbsoluteTargets(draft, absoluteTargets,
                        draft.thicknessPercent(), draft.bladeGapPercent(),
                        offsets, draft.pendingEditCount() + 1);
        setDirtyStructureAdjustmentDraft(reset,
                unit.displayLabel() + " reset — Calculate structure preview");
    }

    /** Discards every unapplied Structure point/slider edit. */
    public synchronized void cancelStructureChanges() {
        requireOpen();
        if (!structureAdjustmentState.active()) {
            return;
        }
        discardStructureAdjustmentDraft();
        manualWarpStatusMessage = Optional.of(
                "Canceled unapplied Structure changes; installed export geometry is unchanged.");
        publish();
    }

    /** Same discard semantics as Cancel, used by stage-aware Skip. */
    public synchronized void skipStructureChanges() {
        requireOpen();
        if (structureAdjustmentState.active()) {
            discardStructureAdjustmentDraft();
        }
        manualWarpStatusMessage = Optional.of(
                "Structure skipped — installed warp and export geometry retained.");
        publish();
    }

    /** Clears only the selected acronym's controls on the active side. */
    public synchronized void clearSelectedStructureControls(
            final ManualHemisphereWarp2D.AtlasSide atlasSide) {
        requireManualControlContext();
        if (structureAdjustmentState.active()) {
            throw new IllegalStateException(
                    "Apply, Cancel, or Skip the current Structure request before clearing its installed controls.");
        }
        final AlignmentReviewState captured = session.state();
        requireIncludedManualControlSide(captured, atlasSide);
        final String acronym = requireHandleSeedContour().region().acronym();
        if (structureControls(captured, atlasSide, acronym).isEmpty()) {
            throw new IllegalArgumentException(
                    "The selected structure has no controls to clear.");
        }
        final java.util.List<ManualWarpControl> retained =
                retainedControlsExceptStructure(captured, atlasSide, acronym);
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(captured);
        submitManualWarpSolve(captured, precondition, retained,
                Optional.empty(), outcome ->
                        new ReviewEdit.ClearStructureWarpControlGroup(
                                atlasSide, acronym, precondition,
                                outcome.warp()));
    }

    /** Replaces the deterministic regular-interior control group on one side. */
    public synchronized java.util.List<String> replaceInteriorGridControls(
            final int requestedCount,
            final ManualHemisphereWarp2D.AtlasSide atlasSide) {
        requireManualControlContext();
        requireRefinementControlCount(requestedCount);
        final AlignmentReviewState captured = session.state();
        requireIncludedManualControlSide(captured, atlasSide);
        final java.util.List<Point2D> sources = interiorControlPoints(
                captured, atlasSide,
                requestedCount);
        final String groupId = "regular-interior-grid";
        final java.util.List<ManualWarpControl> replacements =
                new java.util.ArrayList<>(requestedCount);
        long next = nextManualControlSuffix();
        for (final Point2D source : sources) {
            final Point2D target = captured.content().hemisphereWarp()
                    .map(warp -> warp.apply(atlasSide, source))
                    .orElse(source);
            replacements.add(new ManualWarpControl(
                    "manual-control-" + (++next), atlasSide,
                    ManualWarpControlOrigin.REGULAR_INTERIOR_GRID,
                    groupId, "", source, target));
        }
        final java.util.List<ManualWarpControl> all =
                retainedControlsExceptGroup(captured, atlasSide, groupId);
        all.addAll(replacements);
        requireSideCapacity(all);
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(captured);
        submitManualWarpSolve(captured, precondition, all,
                Optional.empty(), outcome ->
                        new ReviewEdit.ReplaceManualWarpControlGroup(
                                atlasSide, groupId, replacements,
                                precondition, outcome.warp().orElseThrow()));
        return replacements.stream().map(ManualWarpControl::id).toList();
    }

    /**
     * Atomically seeds or replaces the regular grid on every tissue-supported
     * anatomical side. Full and Disjoined reviews normally receive 24 per
     * side; a narrow Half remnant deterministically falls back through the
     * supported density choices instead of hiding that side.
     */
    public synchronized java.util.List<String>
            replaceInteriorGridControlsForSupportedSides(
            final int preferredCount) {
        requireManualControlContext();
        requireRefinementControlCount(preferredCount);
        final AlignmentReviewState captured = session.state();
        final BilateralGridProposal proposal = bilateralGridProposal(
                captured, preferredCount);
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(captured);
        submitManualWarpSolve(captured, precondition, proposal.allControls(),
                Optional.empty(), outcome ->
                        new ReviewEdit.ReplaceAllManualWarpControlsAndInstall(
                                outcome.controls(), precondition,
                                outcome.warp().orElseThrow()));
        return proposal.replacements().stream()
                .map(ManualWarpControl::id).toList();
    }

    private static BilateralGridProposal bilateralGridProposal(
            final AlignmentReviewState captured,
            final int preferredCount) {
        final String groupId = "regular-interior-grid";
        final java.util.List<ManualWarpControl> all =
                new java.util.ArrayList<>();
        captured.content().hemisphereWarp().ifPresent(warp ->
                all.addAll(warp.controls().stream()
                        .filter(control -> !control.groupId().equals(groupId))
                        .toList()));
        final java.util.List<ManualWarpControl> replacements =
                new java.util.ArrayList<>();
        long next = nextManualControlSuffix(captured);
        for (final ManualHemisphereWarp2D.AtlasSide side
                : includedManualControlSides(captured)) {
            final java.util.List<Point2D> sources =
                    bestSupportedInteriorControlPoints(
                            captured, side, preferredCount);
            for (final Point2D source : sources) {
                final String identifier = "manual-control-" + (++next);
                final Point2D target = captured.content().hemisphereWarp()
                        .map(warp -> warp.apply(side, source))
                        .orElse(source);
                replacements.add(new ManualWarpControl(
                        identifier, side,
                        ManualWarpControlOrigin.REGULAR_INTERIOR_GRID,
                        groupId, "", source, target));
            }
        }
        if (replacements.isEmpty()) {
            throw new ManualWarpException(
                    org.atlasalign.application.manual.ManualWarpFailureKind.INVALID_CONTROL,
                    "The tissue crop is too narrow for four safe grid points. Re-suggest or edit the crop, then try again.",
                    "No image side supplied four regular interior controls");
        }
        all.addAll(replacements);
        requireSideCapacity(all);
        return new BilateralGridProposal(all, replacements);
    }

    /**
     * Replaces one side's editable tissue-boundary suggestion. The first run
     * samples copied-preview contrast; later density changes resample the
     * reviewer's currently edited boundary rather than discarding it.
     */
    public synchronized java.util.List<String> replaceBoundaryControls(
            final int requestedCount,
            final ManualHemisphereWarp2D.AtlasSide atlasSide) {
        requireManualControlContext();
        requireBoundaryControlCount(requestedCount);
        final AlignmentReviewState captured = session.state();
        requireIncludedManualControlSide(captured, atlasSide);
        final String groupId = "tissue-boundary";
        final java.util.List<ManualWarpControl> existing = captured.content()
                .hemisphereWarp().map(warp -> warp.controls().stream()
                        .filter(control -> control.atlasSide() == atlasSide
                                && control.groupId().equals(groupId))
                        .toList()).orElse(java.util.List.of());
        final java.util.List<Point2D> sources;
        final java.util.List<Point2D> targets;
        if (existing.size() >= 2) {
            sources = resamplePolyline(existing.stream()
                    .map(ManualWarpControl::sourcePoint).toList(),
                    requestedCount);
            targets = resamplePolyline(existing.stream()
                    .map(ManualWarpControl::targetPoint).toList(),
                    requestedCount);
        } else {
            sources = suggestedBoundaryPoints(captured, atlasSide,
                    requestedCount);
            targets = sources;
        }
        final java.util.List<ManualWarpControl> replacements =
                new java.util.ArrayList<>(requestedCount);
        long next = nextManualControlSuffix();
        for (int index = 0; index < requestedCount; index++) {
            replacements.add(new ManualWarpControl(
                    "manual-control-" + (++next), atlasSide,
                    ManualWarpControlOrigin.TISSUE_BOUNDARY, groupId, "",
                    sources.get(index), targets.get(index)));
        }
        final java.util.List<ManualWarpControl> all =
                retainedControlsExceptGroup(captured, atlasSide, groupId);
        all.addAll(replacements);
        requireSideCapacity(all);
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(captured);
        submitManualWarpSolve(captured, precondition, all,
                Optional.empty(), outcome ->
                        new ReviewEdit.ReplaceManualWarpControlGroup(
                                atlasSide, groupId, replacements,
                                precondition, outcome.warp().orElseThrow()));
        return replacements.stream().map(ManualWarpControl::id).toList();
    }

    /** Rebuilds both editable boundary groups from copied-image contrast. */
    public synchronized java.util.List<String> resuggestBoundaryControls(
            final int requestedCountPerSide) {
        requireManualControlContext();
        requireBoundaryControlCount(requestedCountPerSide);
        final AlignmentReviewState captured = session.state();
        final java.util.List<ManualWarpControl> replacement =
                new java.util.ArrayList<>();
        captured.content().hemisphereWarp().ifPresent(warp ->
                replacement.addAll(warp.controls().stream()
                        .filter(control -> !control.groupId().equals(
                                "tissue-boundary"))
                        .toList()));
        long next = nextManualControlSuffix();
        final java.util.List<String> identifiers = new java.util.ArrayList<>();
        for (final ManualHemisphereWarp2D.AtlasSide side
                : includedManualControlSides(captured)) {
            final java.util.List<Point2D> suggested;
            try {
                suggested = suggestedBoundaryPoints(
                        captured, side, requestedCountPerSide);
            } catch (final IllegalArgumentException absentSide) {
                // A literal half section may have no usable tissue on one
                // image side. Keep the available side instead of failing the
                // complete contrast suggestion.
                continue;
            }
            for (final Point2D point : suggested) {
                final String id = "manual-control-" + (++next);
                identifiers.add(id);
                replacement.add(new ManualWarpControl(
                        id, side, ManualWarpControlOrigin.TISSUE_BOUNDARY,
                        "tissue-boundary", "", point, point));
            }
        }
        if (identifiers.isEmpty()) {
            throw new ManualWarpException(
                    org.atlasalign.application.manual.ManualWarpFailureKind.INVALID_CONTROL,
                    "The copied image did not provide enough tissue edge for four boundary dots. Adjust the image view or add interior points instead.",
                    "No image side supplied the requested contrast boundary density");
        }
        requireSideCapacity(replacement);
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(captured);
        submitManualWarpSolve(captured, precondition, replacement,
                Optional.empty(), outcome ->
                        new ReviewEdit.ReplaceAllManualWarpControlsAndInstall(
                                replacement, precondition,
                                outcome.warp().orElseThrow()));
        return java.util.List.copyOf(identifiers);
    }

    /**
     * Starts local refinement on explicit user demand. Opening the review no
     * longer installs a field: the reviewer first gets a joined coarse
     * placement stage, and choosing Points seeds the default bilateral grid as
     * one asynchronous, undoable revision.
     *
     * @return true when a new default-grid request was submitted
     */
    public synchronized boolean startLocalWarpWithDefaultGridIfNeeded() {
        return startLocalWarpWithDefaultGridIfNeeded(
                session.state().content().halfAtlasCoverage()
                        .includesOppositeRemnant());
    }

    public synchronized boolean startLocalWarpWithDefaultGridIfNeeded(
            final boolean includeOppositeHalfRemnant) {
        requireOpen();
        final AlignmentReviewState captured = session.state();
        final boolean persistedOppositeRemnant = captured.content()
                .reviewSectionMode() == ReviewSectionMode.HALF
                && captured.content().halfAtlasCoverage()
                        .includesOppositeRemnant();
        if (captured.content().reviewSectionMode() == ReviewSectionMode.HALF
                && includeOppositeHalfRemnant
                        != persistedOppositeRemnant) {
            throw new IllegalStateException(
                    "Change Include opposite-side remnant before starting Interior.");
        }
        if (!captured.content().workflowMode().permitsManualEdits()
                || captured.content().localWarp().isPresent()
                || !captured.content().orientation().confirmed()
                || captured.basis().segmentation().isEmpty()) {
            return false;
        }
        final BilateralGridProposal proposal = missingRegularGridProposal(
                captured, 24, persistedOppositeRemnant);
        if (proposal.replacements().isEmpty()) {
            return false;
        }
        final long requestToken = manualWarpRequestToken.incrementAndGet();
        final long revision = captured.contentRevision();
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(captured);
        final AtlasOrientation orientation = captured.content().orientation();
        final ReviewSectionMode reviewSectionMode = captured.content()
                .reviewSectionMode();
        final ManualHemisphereWarp2D.MidlineSegment mappedMidline =
                mappedAtlasMidline(captured);
        final int previewWidth = captured.basis().previewDimensions().width();
        final int previewHeight = captured.basis().previewDimensions().height();
        manualWarpExecutor.execute(() -> {
            ManualWarpSolveOutcome outcome = null;
            RuntimeException failure = null;
            try {
                outcome = solveManualWarp(proposal.allControls(), orientation,
                        reviewSectionMode, mappedMidline, previewWidth,
                        previewHeight, Optional.empty());
            } catch (final RuntimeException error) {
                failure = error;
            }
            final ManualWarpSolveOutcome completedOutcome = outcome;
            final RuntimeException completedFailure = failure;
            viewExecutor.execute(() -> completeManualWarpSolve(
                    requestToken, revision, precondition, completedOutcome,
                    completedFailure,
                    completed ->
                            new ReviewEdit
                                    .ReplaceAllManualWarpControlsAndInstall(
                                    completed.controls(), precondition,
                                    completed.warp().orElseThrow())));
        });
        return true;
    }

    private static BilateralGridProposal missingRegularGridProposal(
            final AlignmentReviewState captured,
            final int preferredCount,
            final boolean includeOppositeHalfRemnant) {
        final String groupId = "regular-interior-grid";
        final java.util.List<ManualWarpControl> all =
                new java.util.ArrayList<>(captured.content()
                        .hemisphereWarp()
                        .map(ManualHemisphereWarp2D::controls)
                        .orElse(java.util.List.of()));
        final java.util.List<ManualWarpControl> replacements =
                new java.util.ArrayList<>();
        long next = nextManualControlSuffix(captured);
        final ReviewSectionMode mode = captured.content().reviewSectionMode();
        final ManualHemisphereWarp2D.AtlasSide visibleHalfSide =
                mode == ReviewSectionMode.HALF
                        ? PlacedAtlasSideSupport.visibleHalfSide(captured)
                        : null;
        for (final ManualHemisphereWarp2D.AtlasSide side
                : ManualHemisphereWarp2D.AtlasSide.values()) {
            if (mode == ReviewSectionMode.HALF
                    && side != visibleHalfSide
                    && !includeOppositeHalfRemnant) {
                continue;
            }
            final boolean alreadyPresent = all.stream().anyMatch(control ->
                    control.atlasSide() == side
                            && control.groupId().equals(groupId));
            if (alreadyPresent) {
                continue;
            }
            final java.util.List<Point2D> sources =
                    bestSupportedInteriorControlPoints(
                            captured, side, preferredCount);
            for (final Point2D source : sources) {
                final Point2D target = captured.content().hemisphereWarp()
                        .map(warp -> warp.apply(side, source))
                        .orElse(source);
                replacements.add(new ManualWarpControl(
                        "manual-control-" + (++next), side,
                        ManualWarpControlOrigin.REGULAR_INTERIOR_GRID,
                        groupId, "", source, target));
            }
        }
        all.addAll(replacements);
        if (!replacements.isEmpty()) {
            requireSideCapacity(all);
        }
        return new BilateralGridProposal(all, replacements);
    }

    /** Adds one identity-positioned user control inside an already active side. */
    public synchronized String addManualWarpControl(
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final Point2D tissuePoint) {
        requireManualControlContext();
        final AlignmentReviewState captured = session.state();
        requireIncludedManualControlSide(captured, atlasSide);
        final ManualHemisphereWarp2D prior = captured.content()
                .hemisphereWarp().orElseThrow(() ->
                        new IllegalStateException(
                                "Set at least four controls on this side before adding one point"));
        if (!prior.hasControls(atlasSide)) {
            throw new IllegalStateException(
                    "Set at least four controls on this side before adding one point");
        }
        final Point2D target = Objects.requireNonNull(
                tissuePoint, "tissuePoint");
        final Point2D source = prior.inverse(atlasSide, target);
        final String id = "manual-control-"
                + (nextManualControlSuffix() + 1);
        final ManualWarpControl added = new ManualWarpControl(
                id, atlasSide,
                ManualWarpControlOrigin.USER_PLACED_INTERIOR,
                "user-placed-interior", "", source, target);
        final java.util.List<ManualWarpControl> all =
                new java.util.ArrayList<>(prior.controls());
        all.add(added);
        requireSideCapacity(all);
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(captured);
        submitManualWarpSolve(captured, precondition, all,
                Optional.empty(), outcome -> new ReviewEdit.AddManualWarpControl(
                        added, precondition, outcome.warp().orElseThrow()));
        return id;
    }

    /** Moves one endpoint; an unsafe request is clamped to the last safe solve. */
    public synchronized void moveManualWarpControlAndRefit(
            final String identifier,
            final Point2D requestedTarget) {
        requireManualControlContext();
        final AlignmentReviewState captured = session.state();
        final ManualHemisphereWarp2D prior = captured.content()
                .hemisphereWarp().orElseThrow(() ->
                        new IllegalStateException(
                                "No reviewer-controlled manual warp is active"));
        final ManualWarpControl moving = prior.controls().stream()
                .filter(control -> control.id().equals(identifier))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Unknown manual-warp control: " + identifier));
        final Point2D requested = Objects.requireNonNull(
                requestedTarget, "requestedTarget");
        if (moving.targetPoint().equals(requested)) {
            return;
        }
        final java.util.List<ManualWarpControl> requestedControls =
                replaceControlTarget(prior.controls(), identifier, requested);
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(captured);
        submitManualWarpSolve(captured, precondition, requestedControls,
                Optional.of(new ClampRequest(java.util.List.of(
                        new ControlTargetMove(identifier,
                                moving.targetPoint(), requested)))), outcome -> {
                    final Point2D installedTarget = outcome.controls().stream()
                            .filter(control -> control.id().equals(identifier))
                            .findFirst().orElseThrow().targetPoint();
                    return new ReviewEdit.MoveManualWarpControlAndInstall(
                            identifier, installedTarget, precondition,
                            outcome.warp().orElseThrow());
                });
    }

    /** Clears every control and deformation on one side, retaining the other. */
    public synchronized void resetManualWarpSide(
            final ManualHemisphereWarp2D.AtlasSide atlasSide) {
        requireManualControlContext();
        final AlignmentReviewState captured = session.state();
        final Optional<ManualHemisphereWarp2D> optional = captured.content()
                .hemisphereWarp();
        if (optional.isEmpty()) {
            final ManualSidePlacement2D current = captured.content()
                    .manualSidePlacement();
            final ManualSidePlacement2D reset = current.withTransform(
                    atlasSide, ManualSidePlacement2D.identity()
                            .transform(atlasSide));
            if (reset.equals(current)) {
                throw new IllegalArgumentException(
                        "The selected side is already reset");
            }
            apply(new ReviewEdit.SetManualSidePlacement(atlasSide, reset));
            return;
        }
        final ManualHemisphereWarp2D prior = optional.orElseThrow();
        if (!prior.hasControls(atlasSide)) {
            throw new IllegalArgumentException(
                    "The selected side has no manual-warp controls");
        }
        final java.util.List<ManualWarpControl> retained = prior.controls()
                .stream().filter(control -> control.atlasSide() != atlasSide)
                .toList();
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(captured);
        submitManualWarpSolve(captured, precondition, retained,
                Optional.empty(), outcome ->
                        new ReviewEdit.ResetManualWarpSide(
                                atlasSide, precondition, outcome.warp()));
    }

    private void submitManualWarpSolve(
            final AlignmentReviewState captured,
            final ManualWarpPrecondition precondition,
            final java.util.List<ManualWarpControl> requestedControls,
            final Optional<ClampRequest> clampRequest,
            final Function<ManualWarpSolveOutcome, ReviewEdit> editFactory) {
        manualWarpStatusMessage = Optional.empty();
        final long requestToken = manualWarpRequestToken.incrementAndGet();
        final long revision = captured.contentRevision();
        final AtlasOrientation orientation = captured.content().orientation();
        final ReviewSectionMode reviewSectionMode = captured.content()
                .reviewSectionMode();
        final ManualHemisphereWarp2D.MidlineSegment mappedMidline =
                mappedAtlasMidline(captured);
        final int previewWidth = captured.basis().previewDimensions().width();
        final int previewHeight = captured.basis().previewDimensions().height();
        final java.util.List<ManualWarpControl> immutableControls =
                java.util.List.copyOf(requestedControls);
        manualWarpExecutor.execute(() -> {
            ManualWarpSolveOutcome outcome = null;
            RuntimeException failure = null;
            try {
                outcome = solveManualWarp(immutableControls, orientation,
                        reviewSectionMode, mappedMidline,
                        previewWidth, previewHeight, clampRequest);
            } catch (final RuntimeException error) {
                failure = error;
            }
            final ManualWarpSolveOutcome completed = outcome;
            final RuntimeException completedFailure = failure;
            viewExecutor.execute(() -> completeManualWarpSolve(
                    requestToken, revision, precondition, completed,
                    completedFailure, editFactory));
        });
    }

    private static ManualWarpSolveOutcome solveManualWarp(
            final java.util.List<ManualWarpControl> requestedControls,
            final AtlasOrientation orientation,
            final ReviewSectionMode reviewSectionMode,
            final ManualHemisphereWarp2D.MidlineSegment mappedMidline,
            final int previewWidth,
            final int previewHeight,
            final Optional<ClampRequest> clampRequest) {
        if (requestedControls.isEmpty()) {
            return new ManualWarpSolveOutcome(requestedControls,
                    Optional.empty(), 1.0);
        }
        try {
            return new ManualWarpSolveOutcome(requestedControls,
                    Optional.of(ManualHemisphereWarp2D.fit(
                            requestedControls, orientation, reviewSectionMode,
                            mappedMidline, previewWidth, previewHeight)), 1.0);
        } catch (final RuntimeException unsafeRequested) {
            if (clampRequest.isEmpty()) {
                throw unsafeRequested;
            }
            final ClampRequest clamp = clampRequest.orElseThrow();
            double safeFraction = 0;
            double unsafeFraction = 1;
            ManualHemisphereWarp2D lastSafe = null;
            java.util.List<ManualWarpControl> lastSafeControls = null;
            // Deterministic bisection finds the largest audited fraction to
            // 1/65536 of the requested movement. Every retained candidate
            // still passes the complete shared-field scientific audit.
            for (int iteration = 0;
                    iteration < MAXIMUM_UNSAFE_DRAG_CLAMP_ITERATIONS;
                    iteration++) {
                // Establish one deliberately conservative non-zero candidate
                // before spending the remaining solves on bisection. This
                // avoids wasting the whole release budget on unsafe midpoints
                // when the valid interval is close to the prior handle.
                final double fraction = iteration == 0
                        ? 1.0 / 32.0
                        : 0.5 * (safeFraction + unsafeFraction);
                final java.util.List<ManualWarpControl> candidate =
                        interpolateControlTargets(requestedControls,
                                clamp.moves(), fraction);
                try {
                    final ManualHemisphereWarp2D fitted =
                            ManualHemisphereWarp2D.fit(
                                    candidate, orientation, reviewSectionMode,
                                    mappedMidline,
                                    previewWidth, previewHeight);
                    safeFraction = fraction;
                    lastSafe = fitted;
                    lastSafeControls = candidate;
                } catch (final RuntimeException stillUnsafe) {
                    unsafeFraction = fraction;
                }
            }
            if (lastSafe == null || safeFraction <= 1e-5) {
                throw unsafeRequested;
            }
            return new ManualWarpSolveOutcome(lastSafeControls,
                    Optional.of(lastSafe), safeFraction);
        }
    }

    private synchronized void completeManualWarpSolve(
            final long requestToken,
            final long expectedRevision,
            final ManualWarpPrecondition precondition,
            final ManualWarpSolveOutcome outcome,
            final RuntimeException failure,
            final Function<ManualWarpSolveOutcome, ReviewEdit> editFactory) {
        if (closed || requestToken != manualWarpRequestToken.get()
                || session.state().contentRevision() != expectedRevision) {
            return;
        }
        if (failure != null) {
            reportManualWarpFailure(failure);
            return;
        }
        try {
            // A second explicit hash check precedes the reducer's identical
            // fail-closed check, so no stale field can enter history even if
            // a caller bypasses this controller in the future.
            precondition.requireMatches(
                    session.state().content(), session.state().basis());
            if (!session.applyIfCurrentRevision(expectedRevision,
                    editFactory.apply(Objects.requireNonNull(outcome)))) {
                return;
            }
            manualWarpStatusMessage = Optional.of(outcome.appliedFraction()
                    < 1.0
                    ? String.format(java.util.Locale.ROOT,
                            "Applied %.1f%% of the requested movement at the largest audited safe step.",
                            outcome.appliedFraction() * 100)
                    : "Installed the requested manual warp as one audited Undo step.");
            publish();
        } catch (final RuntimeException staleOrInvalid) {
            reportManualWarpFailure(staleOrInvalid);
        }
    }

    void reportManualWarpFailure(final RuntimeException failure) {
        showError("Manual warp not applied",
                manualWarpUserMessage(failure));
        // The request may have put the panel into a transient "Preparing…"
        // state before the off-thread audit failed. The scientific content is
        // intentionally unchanged, but the view still needs a fresh immutable
        // model so it can restore the normal controls.
        publish();
    }

    private static java.util.List<ManualWarpControl> replaceControlTarget(
            final java.util.List<ManualWarpControl> controls,
            final String identifier,
            final Point2D target) {
        final java.util.List<ManualWarpControl> replaced =
                new java.util.ArrayList<>(controls.size());
        boolean found = false;
        for (final ManualWarpControl control : controls) {
            if (!control.id().equals(identifier)) {
                replaced.add(control);
                continue;
            }
            replaced.add(new ManualWarpControl(
                    control.id(), control.atlasSide(), control.origin(),
                    control.groupId(), control.structureAcronym(),
                    control.sourcePoint(), target));
            found = true;
        }
        if (!found) {
            throw new IllegalArgumentException(
                    "Unknown manual-warp control: " + identifier);
        }
        return java.util.List.copyOf(replaced);
    }

    private static java.util.List<ManualWarpControl>
            interpolateControlTargets(
            final java.util.List<ManualWarpControl> controls,
            final java.util.List<ControlTargetMove> moves,
            final double fraction) {
        final java.util.Map<String, ControlTargetMove> byId = moves.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ControlTargetMove::controlId,
                        java.util.function.Function.identity()));
        final java.util.List<ManualWarpControl> interpolated =
                new java.util.ArrayList<>(controls.size());
        final java.util.Set<String> found = new java.util.HashSet<>();
        for (final ManualWarpControl control : controls) {
            final ControlTargetMove move = byId.get(control.id());
            if (move == null) {
                interpolated.add(control);
                continue;
            }
            found.add(control.id());
            interpolated.add(new ManualWarpControl(
                    control.id(), control.atlasSide(), control.origin(),
                    control.groupId(), control.structureAcronym(),
                    control.sourcePoint(), interpolate(move.originalTarget(),
                            move.requestedTarget(), fraction)));
        }
        if (!found.equals(byId.keySet())) {
            throw new IllegalArgumentException(
                    "A requested manual-warp group changed before solving");
        }
        return java.util.List.copyOf(interpolated);
    }

    private synchronized void submitStructureAdjustmentAudit(
            final AlignmentReviewState captured,
            final StructureAdjustmentDraft draft) {
        final java.util.List<ManualWarpControl> all =
                retainedControlsExceptStructure(captured,
                        draft.atlasSide(), draft.structureAcronym());
        all.addAll(draft.requestedControls());
        requireSideCapacity(all);
        final long token = structureAdjustmentRequestToken.incrementAndGet();
        structureAdjustmentBaseRevision = captured.contentRevision();
        structureAdjustmentState = new StructureAdjustmentViewState(
                true, Optional.of(draft), Optional.empty(),
                java.util.List.of(), Optional.of(
                        "Calculating a valid shared-warp preview…"));
        publish();
        final java.util.List<ManualWarpControl> immutable =
                java.util.List.copyOf(all);
        final AtlasOrientation orientation = captured.content().orientation();
        final ReviewSectionMode mode = captured.content().reviewSectionMode();
        final ManualHemisphereWarp2D.MidlineSegment midline =
                mappedAtlasMidline(captured);
        final int width = captured.basis().previewDimensions().width();
        final int height = captured.basis().previewDimensions().height();
        pendingStructureAdjustmentAudit = new StructureAdjustmentAuditJob(
                token, draft, immutable, orientation, mode, midline,
                width, height);
        if (!structureAdjustmentAuditScheduled) {
            structureAdjustmentAuditScheduled = true;
            manualWarpExecutor.execute(
                    this::runLatestStructureAdjustmentAudit);
        }
    }

    /**
     * Solves only the newest pending Structure request. A gesture or slider
     * edit may arrive while an earlier solve is running; in that case every
     * superseded request collapses into one latest job instead of building a
     * serial backlog on the shared mesh executor.
     */
    private void runLatestStructureAdjustmentAudit() {
        final StructureAdjustmentAuditJob job;
        synchronized (this) {
            job = pendingStructureAdjustmentAudit;
            pendingStructureAdjustmentAudit = null;
            if (job == null) {
                structureAdjustmentAuditScheduled = false;
                return;
            }
        }
        StructureCalculationOutcome calculated;
        try {
            calculated = calculateStructureAdjustment(job);
        } catch (final RuntimeException unexpected) {
            final ManualWarpSafetyReport report = structureSafetyReport(
                    unexpected, job.draft());
            calculated = new StructureCalculationOutcome(job.controls(),
                    Optional.empty(), java.util.Map.of(),
                    java.util.List.of(), Optional.empty(),
                    Optional.of(report));
        }
        final StructureCalculationOutcome completed = calculated;
        viewExecutor.execute(() -> completeStructureAdjustmentAudit(
                job.token(), job.draft(), completed));

        final boolean runAgain;
        synchronized (this) {
            runAgain = pendingStructureAdjustmentAudit != null;
            if (!runAgain) {
                structureAdjustmentAuditScheduled = false;
            }
        }
        if (runAgain) {
            manualWarpExecutor.execute(
                    this::runLatestStructureAdjustmentAudit);
        }
    }

    private synchronized void completeStructureAdjustmentAudit(
            final long token,
            final StructureAdjustmentDraft draft,
            final StructureCalculationOutcome outcome) {
        if (closed || token != structureAdjustmentRequestToken.get()) {
            return;
        }
        if (session.state().contentRevision() != draft.contentRevision()
                || structureAdjustmentState.draft().stream().noneMatch(
                        current -> current.inputHash().equals(
                                draft.inputHash()))) {
            discardStructureAdjustmentDraft();
            structureAdjustmentState = new StructureAdjustmentViewState(
                    false, Optional.empty(), Optional.empty(), Optional.of(
                            "Structure inputs changed; set or edit its points again."));
            publish();
            return;
        }
        try {
            draft.precondition().requireMatches(session.state().content(),
                    session.state().basis());
            final StructureCalculationOutcome checked =
                    Objects.requireNonNull(outcome, "outcome");
            if (checked.warp().isEmpty()) {
                final String label = checked.highlightedUnitId()
                        .map(draft::unit)
                        .map(StructureAdjustmentUnit::displayLabel)
                        .orElse("Highlighted movement");
                final String detail = checked.terminalReport()
                        .map(ReviewController::manualWarpSafetySummary)
                        .orElse("the complete shared field has no valid candidate");
                structureAdjustmentState = new StructureAdjustmentViewState(
                        false, Optional.of(draft), Optional.empty(),
                        checked.limits(), Optional.of(label
                                + " must be reduced before a calculated valid preview can be made — "
                                + detail + ". Requested shape preserved."));
                publish();
                return;
            }
            final java.util.List<ManualWarpControl> validGroup =
                    structureControls(checked.controls(), draft.atlasSide(),
                            draft.structureAcronym());
            final StructureAdjustmentCandidate candidate =
                    new StructureAdjustmentCandidate(draft.inputHash(),
                            validGroup, checked.warp().orElseThrow(),
                            checked.retainedFractions(), checked.limits(),
                            checked.highlightedUnitId());
            final String message = candidate.completesRequest()
                    ? "Calculated valid preview matches the complete requested shape — Apply or keep editing."
                    : structurePartialResultMessage(draft, candidate);
            structureAdjustmentState = new StructureAdjustmentViewState(
                    false, Optional.of(draft), Optional.of(candidate),
                    candidate.limitingReports(), Optional.of(message));
        } catch (final RuntimeException staleOrInvalid) {
            structureAdjustmentState = new StructureAdjustmentViewState(
                    false, Optional.of(draft), Optional.empty(), Optional.of(
                            manualWarpUserMessage(staleOrInvalid)
                                    + " Requested shape remains editable."));
        }
        publish();
    }

    /**
     * Calculates a valid complete shared field while reducing only the pair
     * or unpaired dot nearest each limiting mesh location. Unrelated units
     * remain at their fully requested targets throughout the search.
     */
    private static StructureCalculationOutcome
            calculateStructureAdjustment(
            final StructureAdjustmentAuditJob job) {
        final StructureAdjustmentDraft draft = job.draft();
        final java.util.Map<String, Double> fractions =
                new java.util.LinkedHashMap<>();
        draft.units().forEach(unit -> fractions.put(unit.id(), 1.0));
        final java.util.List<StructureAdjustmentLimit> limits =
                new java.util.ArrayList<>();
        java.util.List<ManualWarpControl> controls = job.controls();
        try {
            final ManualHemisphereWarp2D fitted = fitStructureControls(
                    controls, job);
            return new StructureCalculationOutcome(controls,
                    Optional.of(fitted), fractions, limits,
                    Optional.empty(), Optional.empty());
        } catch (final RuntimeException completeFailure) {
            ManualWarpSafetyReport report = structureSafetyReport(
                    completeFailure, draft);
            final java.util.List<StructureAdjustmentUnit> implicated =
                    new java.util.ArrayList<>();
            ManualHemisphereWarp2D validAnchor = null;
            java.util.List<ManualWarpControl> validControls = null;

            while (implicated.size() < draft.units().size()) {
                final StructureAdjustmentUnit unit = nearestReducibleUnit(
                        draft, report, implicated);
                if (unit == null) {
                    break;
                }
                implicated.add(unit);
                limits.add(new StructureAdjustmentLimit(unit.id(), report));
                fractions.put(unit.id(), 0.0);
                controls = structureControlsAtFractions(
                        job.controls(), draft, fractions);
                try {
                    validAnchor = fitStructureControls(controls, job);
                    validControls = controls;
                    break;
                } catch (final RuntimeException stillInvalid) {
                    report = structureSafetyReport(stillInvalid, draft);
                }
            }

            if (validAnchor == null) {
                return new StructureCalculationOutcome(job.controls(),
                        Optional.empty(), fractions, limits,
                        limits.stream().findFirst()
                                .map(StructureAdjustmentLimit::unitId),
                        Optional.of(report));
            }

            ManualHemisphereWarp2D lastValidWarp = validAnchor;
            java.util.List<ManualWarpControl> lastValidControls =
                    validControls;
            ManualWarpSafetyReport latestFailure = report;
            for (final StructureAdjustmentUnit unit : implicated) {
                double validFraction = fractions.get(unit.id());
                double invalidFraction = 1.0;
                for (int probe = 0;
                        probe < MAXIMUM_UNSAFE_DRAG_CLAMP_ITERATIONS;
                        probe++) {
                    final double candidateFraction =
                            0.5 * (validFraction + invalidFraction);
                    fractions.put(unit.id(), candidateFraction);
                    final java.util.List<ManualWarpControl> candidate =
                            structureControlsAtFractions(
                                    job.controls(), draft, fractions);
                    try {
                        final ManualHemisphereWarp2D fitted =
                                fitStructureControls(candidate, job);
                        validFraction = candidateFraction;
                        lastValidWarp = fitted;
                        lastValidControls = candidate;
                    } catch (final RuntimeException invalid) {
                        invalidFraction = candidateFraction;
                        latestFailure = structureSafetyReport(invalid, draft);
                    }
                }
                fractions.put(unit.id(), validFraction);
            }
            final double requestedMovement = totalStructureMovement(
                    draft.baselineControls(), draft.requestedControls());
            final java.util.List<ManualWarpControl> validGroup =
                    structureControls(lastValidControls, draft.atlasSide(),
                            draft.structureAcronym());
            final double retainedMovement = totalStructureMovement(
                    draft.baselineControls(), validGroup);
            if (requestedMovement > 1e-5 && retainedMovement <= 1e-5) {
                return new StructureCalculationOutcome(job.controls(),
                        Optional.empty(), fractions, limits,
                        limits.stream().findFirst()
                                .map(StructureAdjustmentLimit::unitId),
                        Optional.of(latestFailure));
            }
            return new StructureCalculationOutcome(lastValidControls,
                    Optional.of(lastValidWarp), fractions, limits,
                    limits.stream().findFirst()
                            .map(StructureAdjustmentLimit::unitId),
                    Optional.of(latestFailure));
        }
    }

    private static ManualHemisphereWarp2D fitStructureControls(
            final java.util.List<ManualWarpControl> controls,
            final StructureAdjustmentAuditJob job) {
        return ManualHemisphereWarp2D.fit(controls, job.orientation(),
                job.mode(), job.midline(), job.width(), job.height());
    }

    private static java.util.List<ManualWarpControl>
            structureControlsAtFractions(
            final java.util.List<ManualWarpControl> allRequested,
            final StructureAdjustmentDraft draft,
            final java.util.Map<String, Double> fractions) {
        final java.util.Map<String, ManualWarpControl> starts = controlsById(
                draft.baselineControls());
        final java.util.Map<String, StructureAdjustmentUnit> byControl =
                new java.util.HashMap<>();
        for (final StructureAdjustmentUnit unit : draft.units()) {
            unit.controlIds().forEach(id -> byControl.put(id, unit));
        }
        final java.util.List<ManualWarpControl> result =
                new java.util.ArrayList<>(allRequested.size());
        for (final ManualWarpControl requested : allRequested) {
            final StructureAdjustmentUnit unit = byControl.get(
                    requested.id());
            if (unit == null) {
                result.add(requested);
                continue;
            }
            final ManualWarpControl start = starts.get(requested.id());
            result.add(copyControlWithTarget(requested, interpolate(
                    start.targetPoint(), requested.targetPoint(),
                    fractions.get(unit.id()))));
        }
        return java.util.List.copyOf(result);
    }

    private static StructureAdjustmentUnit nearestReducibleUnit(
            final StructureAdjustmentDraft draft,
            final ManualWarpSafetyReport report,
            final java.util.List<StructureAdjustmentUnit> excluded) {
        final java.util.Set<String> excludedIds = excluded.stream()
                .map(StructureAdjustmentUnit::id)
                .collect(java.util.stream.Collectors.toSet());
        final Point2D location = report.meshLocation().orElseGet(() ->
                maximumMovedStructureLocation(draft));
        final java.util.Map<String, ManualWarpControl> starts = controlsById(
                draft.baselineControls());
        final java.util.Map<String, ManualWarpControl> requested =
                controlsById(draft.requestedControls());
        return draft.units().stream()
                .filter(unit -> !excludedIds.contains(unit.id()))
                .filter(unit -> unit.controlIds().stream().anyMatch(id ->
                        !starts.get(id).targetPoint().equals(
                                requested.get(id).targetPoint())))
                .min(java.util.Comparator
                        .comparingDouble((StructureAdjustmentUnit unit) ->
                                unit.controlIds().stream().map(requested::get)
                                        .map(ManualWarpControl::targetPoint)
                                        .mapToDouble(point -> squaredDistance(
                                                point, location)).min()
                                        .orElse(Double.POSITIVE_INFINITY))
                        .thenComparing(StructureAdjustmentUnit::id))
                .orElse(null);
    }

    private static Point2D maximumMovedStructureLocation(
            final StructureAdjustmentDraft draft) {
        final java.util.Map<String, ManualWarpControl> starts = controlsById(
                draft.baselineControls());
        return draft.requestedControls().stream().max(
                java.util.Comparator
                        .comparingDouble((ManualWarpControl control) ->
                                squaredDistance(control.targetPoint(), starts
                                        .get(control.id()).targetPoint()))
                        .thenComparing(ManualWarpControl::id))
                .map(ManualWarpControl::targetPoint)
                .orElse(draft.requestedControls().get(0).targetPoint());
    }

    private static ManualWarpSafetyReport structureSafetyReport(
            final RuntimeException failure,
            final StructureAdjustmentDraft draft) {
        return manualWarpSafetyReport(failure).orElseGet(() ->
                ManualWarpSafetyReport.unmeasured(
                        ManualWarpSafetyGate.UNKNOWN, draft.atlasSide(),
                        maximumMovedStructureLocation(draft),
                        messageOf(failure)));
    }

    private static double totalStructureMovement(
            final java.util.List<ManualWarpControl> starts,
            final java.util.List<ManualWarpControl> targets) {
        final java.util.Map<String, ManualWarpControl> byId = controlsById(
                starts);
        return targets.stream().mapToDouble(control -> pointDistance(
                byId.get(control.id()).targetPoint(),
                control.targetPoint())).sum();
    }

    private static String structurePartialResultMessage(
            final StructureAdjustmentDraft draft,
            final StructureAdjustmentCandidate candidate) {
        final String highlighted = candidate.highlightedUnitId()
                .map(draft::unit)
                .map(StructureAdjustmentUnit::displayLabel)
                .orElse("A highlighted dot unit");
        final String gate = candidate.limitingReports().stream().findFirst()
                .map(StructureAdjustmentLimit::report)
                .map(ReviewController::structureGateText)
                .orElse("a local deformation limit");
        final long complete = candidate.retainedFractionByUnitId().values()
                .stream().filter(value -> value >= 1 - 1e-12).count();
        final long limited = candidate.retainedFractionByUnitId().size()
                - complete;
        return highlighted + " exceeded " + gate + "; " + complete
                + " units reached their requested positions and " + limited
                + " were locally limited. Dashed cyan shows the remainder.";
    }

    private static String structureGateText(
            final ManualWarpSafetyReport report) {
        final String gate = switch (report.gate()) {
            case MAXIMUM_SINGULAR_VALUE -> "the local stretch limit";
            case MINIMUM_SINGULAR_VALUE -> "the local compression limit";
            case ANISOTROPY -> "the local shear/anisotropy limit";
            case DETERMINANT -> "the fold/determinant limit";
            case DISPLACEMENT -> "the displacement limit";
            case ROUND_TRIP -> "the inverse round-trip limit";
            case WORKSPACE -> "the editable workspace limit";
            case SEAM -> "the fixed seam limit";
            case CONTROL_TOPOLOGY -> "the control-topology limit";
            case CONTROL_COVERAGE -> "the control-coverage limit";
            case CONTROL_CAPACITY -> "the per-side control-capacity limit";
            case FINITE_GEOMETRY -> "the finite-geometry limit";
            case UNKNOWN -> "a complete-field geometry limit";
        };
        return report.meshLocation().map(point -> gate + String.format(
                java.util.Locale.ROOT, " near preview (%.1f, %.1f)",
                point.x(), point.y())).orElse(gate);
    }

    private StructureAdjustmentDraft requireOrAdoptStructureAdjustmentDraft(
            final AlignmentReviewState captured,
            final ManualHemisphereWarp2D.AtlasSide side,
            final String acronym) {
        final SelectedAtlasContour contour = requireHandleSeedContour();
        final Optional<StructureAdjustmentDraft> active =
                structureAdjustmentState.draft().filter(draft ->
                        draft.contentRevision() == captured.contentRevision()
                                && draft.atlasSide() == side
                                && draft.structureAcronym().equals(acronym)
                                && draft.contourHash().equals(
                                        contour.boundarySha256()));
        if (active.isPresent()) {
            return active.orElseThrow();
        }
        final java.util.List<ManualWarpControl> installed =
                structureControls(captured, side, acronym).stream()
                        .map(control -> new ManualWarpControl(
                                control.id(), side,
                                ManualWarpControlOrigin.STRUCTURE_GUIDE,
                                structureGroupId(acronym), acronym,
                                control.sourcePoint(),
                                control.targetPoint()))
                        .toList();
        if (installed.size() < 4) {
            throw new IllegalStateException(
                    "Set at least four points on the selected structure before editing it.");
        }
        final AtlasAnatomicalSide anatomicalSide = side
                == ManualHemisphereWarp2D.AtlasSide.LEFT
                ? AtlasAnatomicalSide.ATLAS_LEFT
                : AtlasAnatomicalSide.ATLAS_RIGHT;
        final java.util.Map<String, Integer> assignments =
                structureComponentAssignmentsFromTargets(contour,
                        anatomicalSide, installed, captured, side);
        final java.util.List<Point2D> atlasPoints = installed.stream()
                .map(control -> nearestSelectedStructureAtlasPoint(
                        captured.mapPreviewToAtlasCandidates(
                                control.targetPoint()), contour,
                        anatomicalSide))
                .toList();
        final java.util.List<StructureOutlinePath> outlinePaths =
                structureOutlinePaths(contour, anatomicalSide, atlasPoints,
                        installed);
        final StructureAdjustmentDraft adopted = newStructureAdjustmentDraft(
                captured.contentRevision(),
                ManualWarpPrecondition.capture(captured), side, acronym,
                contour.boundarySha256(), installed, assignments,
                outlinePaths, 0);
        structureAdjustmentBaseRevision = captured.contentRevision();
        structureAdjustmentState = new StructureAdjustmentViewState(false,
                Optional.of(adopted), Optional.empty(), Optional.of(
                        "Structure controls adopted — edit the requested shape, then Calculate."));
        return adopted;
    }

    private static StructureAdjustmentDraft newStructureAdjustmentDraft(
            final long revision,
            final ManualWarpPrecondition precondition,
            final ManualHemisphereWarp2D.AtlasSide side,
            final String acronym,
            final String contourHash,
            final java.util.List<ManualWarpControl> starts,
            final java.util.Map<String, Integer> assignments,
            final java.util.List<StructureOutlinePath> outlinePaths,
            final int pendingEdits) {
        final java.util.List<StructureAdjustmentUnit> units =
                buildStructureUnits(starts, assignments, contourHash);
        final java.util.Map<String, Point2D> offsets =
                new java.util.LinkedHashMap<>();
        starts.forEach(control -> offsets.put(control.id(),
                new Point2D(0, 0)));
        final BladeSeparation separation = bladeSeparation(
                starts, assignments);
        return new StructureAdjustmentDraft(revision, precondition, side,
                acronym, contourHash, starts, starts, assignments,
                outlinePaths, units, offsets, separation.axis(),
                separation.distance(), 100, 0, pendingEdits, "");
    }

    /**
     * Keeps the verified exterior-loop order independent of the blade/pair
     * assignments. The former draws a recognizable Fiji-style polygon; the
     * latter remains free to pair opposing boundaries for thickness edits.
     */
    private static java.util.List<StructureOutlinePath>
            structureOutlinePaths(
            final SelectedAtlasContour contour,
            final AtlasAnatomicalSide side,
            final java.util.List<Point2D> atlasPoints,
            final java.util.List<ManualWarpControl> controls) {
        if (atlasPoints.size() != controls.size()) {
            throw new IllegalArgumentException(
                    "Structure outline points and controls must match");
        }
        final java.util.List<SelectedAtlasContour.ExteriorComponent>
                components = contour.principalExteriorComponents(side);
        if (components.isEmpty()) {
            return java.util.List.of(new StructureOutlinePath(
                    contour.boundarySha256() + ":outline-0",
                    controls.stream().map(ManualWarpControl::id).toList(),
                    true));
        }
        final java.util.Map<String, java.util.List<OutlineSample>> grouped =
                new java.util.LinkedHashMap<>();
        components.forEach(component -> grouped.put(component.id(),
                new java.util.ArrayList<>()));
        for (int index = 0; index < atlasPoints.size(); index++) {
            final Point2D point = atlasPoints.get(index);
            final SelectedAtlasContour.ExteriorComponent nearest = components
                    .stream().min(java.util.Comparator
                            .comparingDouble((SelectedAtlasContour.ExteriorComponent
                                    component) -> distanceToBoundary(point,
                                            component.loop()))
                            .thenComparing(
                                    SelectedAtlasContour.ExteriorComponent::id))
                    .orElseThrow();
            grouped.get(nearest.id()).add(new OutlineSample(
                    controls.get(index).id(),
                    closedLoopProgress(point, nearest.loop())));
        }
        if (grouped.values().stream().anyMatch(samples ->
                !samples.isEmpty() && samples.size() < 2)) {
            return java.util.List.of(new StructureOutlinePath(
                    contour.boundarySha256() + ":outline-0",
                    controls.stream().map(ManualWarpControl::id).toList(),
                    true));
        }
        final java.util.List<StructureOutlinePath> paths =
                new java.util.ArrayList<>();
        for (final var entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            entry.getValue().sort(java.util.Comparator
                    .comparingDouble(OutlineSample::progress)
                    .thenComparing(OutlineSample::controlId));
            paths.add(new StructureOutlinePath(entry.getKey() + ":outline",
                    entry.getValue().stream().map(OutlineSample::controlId)
                            .toList(), true));
        }
        return java.util.List.copyOf(paths);
    }

    private static double closedLoopProgress(
            final Point2D point,
            final java.util.List<Point2D> loop) {
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestProgress = 0;
        double travelled = 0;
        for (int index = 0; index < loop.size(); index++) {
            final Point2D first = loop.get(index);
            final Point2D second = loop.get((index + 1) % loop.size());
            final double dx = second.x() - first.x();
            final double dy = second.y() - first.y();
            final double lengthSquared = dx * dx + dy * dy;
            final double parameter = lengthSquared <= 0 ? 0
                    : Math.max(0, Math.min(1,
                            ((point.x() - first.x()) * dx
                                    + (point.y() - first.y()) * dy)
                                    / lengthSquared));
            final Point2D projected = new Point2D(
                    first.x() + parameter * dx,
                    first.y() + parameter * dy);
            final double distance = pointDistance(point, projected);
            final double progress = travelled
                    + parameter * Math.sqrt(lengthSquared);
            if (distance < bestDistance - 1e-12
                    || Math.abs(distance - bestDistance) <= 1e-12
                            && progress < bestProgress) {
                bestDistance = distance;
                bestProgress = progress;
            }
            travelled += Math.sqrt(lengthSquared);
        }
        return bestProgress;
    }

    private static Point2D nearestSelectedStructureAtlasPoint(
            final java.util.List<Point2D> candidates,
            final SelectedAtlasContour contour,
            final AtlasAnatomicalSide side) {
        final java.util.List<SelectedAtlasContour.ExteriorComponent>
                components = contour.principalExteriorComponents(side);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "Installed Structure control has no atlas inverse candidate");
        }
        if (components.isEmpty()) {
            return candidates.get(0);
        }
        return candidates.stream().min(java.util.Comparator
                .comparingDouble((Point2D point) -> components.stream()
                        .mapToDouble(component -> distanceToBoundary(point,
                                component.loop()))
                        .min().orElse(Double.POSITIVE_INFINITY))
                .thenComparingDouble(Point2D::y)
                .thenComparingDouble(Point2D::x)).orElseThrow();
    }

    /**
     * Builds mutually normal, non-crossing chords across each blade. The
     * sampled controls retain contour order within their component, so local
     * tangents and centroid-facing normals can reject tangential neighbours
     * and cross-gap pairings. Dots without a reliable opposite boundary stay
     * as stable, freely draggable tip controls.
     */
    private static java.util.List<StructureAdjustmentUnit>
            buildStructureUnits(
            final java.util.List<ManualWarpControl> controls,
            final java.util.Map<String, Integer> assignments,
            final String contourHash) {
        final java.util.Map<Integer, java.util.List<ManualWarpControl>> groups =
                new java.util.TreeMap<>();
        for (final ManualWarpControl control : controls) {
            groups.computeIfAbsent(assignments.get(control.id()),
                    ignored -> new java.util.ArrayList<>()).add(control);
        }
        final java.util.Map<Integer, String> componentNames =
                structureComponentNames(groups);
        final java.util.List<StructureAdjustmentUnit> result =
                new java.util.ArrayList<>();
        int unitIndex = 0;
        for (final var entry : groups.entrySet()) {
            final java.util.List<ManualWarpControl> ordered = entry.getValue();
            final boolean[] paired = new boolean[ordered.size()];
            final java.util.List<StructurePairCandidate> selected =
                    new java.util.ArrayList<>();
            for (final StructurePairCandidate candidate
                    : structurePairCandidates(ordered)) {
                if (paired[candidate.firstIndex()]
                        || paired[candidate.secondIndex()]
                        || selected.stream().anyMatch(existing ->
                                properSegmentsIntersect(
                                        candidate.firstPoint(),
                                        candidate.secondPoint(),
                                        existing.firstPoint(),
                                        existing.secondPoint()))) {
                    continue;
                }
                paired[candidate.firstIndex()] = true;
                paired[candidate.secondIndex()] = true;
                selected.add(candidate);
            }
            final Point2D lengthAxis = principalStructureAxis(ordered);
            selected.sort(java.util.Comparator
                    .comparingDouble((StructurePairCandidate pair) ->
                            projection(pair.midpoint(), lengthAxis))
                    .thenComparingInt(StructurePairCandidate::firstIndex)
                    .thenComparingInt(StructurePairCandidate::secondIndex));
            final String componentId = stableStructureComponentId(
                    contourHash, entry.getKey());
            for (int pairIndex = 0; pairIndex < selected.size(); pairIndex++) {
                final StructurePairCandidate pair = selected.get(pairIndex);
                final ManualWarpControl first = ordered.get(
                        pair.firstIndex());
                final ManualWarpControl second = ordered.get(
                        pair.secondIndex());
                result.add(new StructureAdjustmentUnit(
                        "structure-unit-" + (++unitIndex), entry.getKey(),
                        componentId,
                        java.util.List.of(first.id(), second.id()),
                        pair.midpoint(), pair.axis(), pair.distance(),
                        pairedStructureLabel(componentNames.get(
                                entry.getKey()), pairIndex,
                                selected.size())));
            }
            final java.util.List<Integer> unpaired =
                    java.util.stream.IntStream.range(0, ordered.size())
                            .filter(index -> !paired[index]).boxed()
                            .sorted(java.util.Comparator
                                    .comparingDouble((Integer index) -> projection(
                                            ordered.get(index).targetPoint(),
                                            lengthAxis))
                                    .thenComparingInt(Integer::intValue))
                            .toList();
            for (int tipIndex = 0; tipIndex < unpaired.size(); tipIndex++) {
                result.add(unpairedStructureUnit(++unitIndex,
                        entry.getKey(), componentId,
                        ordered.get(unpaired.get(tipIndex)),
                        componentNames, tipIndex, unpaired.size()));
            }
        }
        return java.util.List.copyOf(result);
    }

    private static StructureAdjustmentUnit unpairedStructureUnit(
            final int unitIndex,
            final int component,
            final String componentId,
            final ManualWarpControl control,
            final java.util.Map<Integer, String> names,
            final int tipIndex,
            final int tipCount) {
        return new StructureAdjustmentUnit(
                "structure-unit-" + unitIndex, component, componentId,
                java.util.List.of(control.id()), control.targetPoint(),
                new Point2D(0, 0), 0,
                names.get(component) + (tipCount == 1 ? " tip dot"
                        : tipIndex == 0 ? " left-tip dot"
                        : tipIndex + 1 == tipCount ? " right-tip dot"
                        : " unpaired dot " + (tipIndex + 1)));
    }

    private static java.util.List<StructurePairCandidate>
            structurePairCandidates(
            final java.util.List<ManualWarpControl> ordered) {
        if (ordered.size() < 2) {
            return java.util.List.of();
        }
        if (ordered.size() == 2) {
            return pairCandidate(ordered, 0, 1, 0)
                    .map(java.util.List::of).orElse(java.util.List.of());
        }
        if (ordered.size() == 3) {
            return shortestStructurePair(ordered).map(java.util.List::of)
                    .orElse(java.util.List.of());
        }
        final Point2D lengthAxis = principalStructureAxis(ordered);
        int firstTip = 0;
        int secondTip = 0;
        double minimumProjection = projection(
                ordered.get(0).targetPoint(), lengthAxis);
        double maximumProjection = minimumProjection;
        for (int index = 1; index < ordered.size(); index++) {
            final double candidateProjection = projection(
                    ordered.get(index).targetPoint(), lengthAxis);
            if (candidateProjection < minimumProjection - 1e-12) {
                minimumProjection = candidateProjection;
                firstTip = index;
            }
            if (candidateProjection > maximumProjection + 1e-12) {
                maximumProjection = candidateProjection;
                secondTip = index;
            }
        }
        if (firstTip == secondTip) {
            return java.util.List.of();
        }
        final StructureBoundaryChain forward = structureBoundaryChain(
                ordered, firstTip, secondTip, 1);
        final StructureBoundaryChain backward = structureBoundaryChain(
                ordered, firstTip, secondTip, -1);
        final StructureBoundaryChain smaller =
                forward.interiorCount() <= backward.interiorCount()
                        ? forward : backward;
        final StructureBoundaryChain larger = smaller == forward
                ? backward : forward;
        final java.util.List<Point2D> tangents =
                java.util.stream.IntStream.range(0, ordered.size())
                        .mapToObj(index -> localStructureTangent(
                                ordered, index)).toList();
        final java.util.List<StructurePairCandidate> matched =
                new java.util.ArrayList<>();
        int nextLargerPosition = 1;
        for (int smallerPosition = 1;
                smallerPosition + 1 < smaller.indices().size();
                smallerPosition++) {
            final int remaining = smaller.interiorCount()
                    - smallerPosition;
            final int maximumLargerPosition = larger.interiorCount()
                    - remaining;
            int bestLargerPosition = nextLargerPosition;
            double bestProgressError = Double.POSITIVE_INFINITY;
            for (int largerPosition = nextLargerPosition;
                    largerPosition <= maximumLargerPosition;
                    largerPosition++) {
                final double progressError = Math.abs(
                        smaller.progress().get(smallerPosition)
                                - larger.progress().get(largerPosition));
                if (progressError < bestProgressError - 1e-12) {
                    bestProgressError = progressError;
                    bestLargerPosition = largerPosition;
                }
            }
            nextLargerPosition = bestLargerPosition + 1;
            final int first = Math.min(
                    smaller.indices().get(smallerPosition),
                    larger.indices().get(bestLargerPosition));
            final int second = Math.max(
                    smaller.indices().get(smallerPosition),
                    larger.indices().get(bestLargerPosition));
            final StructurePairCandidate candidate = pairCandidate(
                    ordered, first, second, bestProgressError)
                    .orElseThrow();
            final double mutualNormalError = Math.max(
                    Math.abs(dot(candidate.axis(), tangents.get(first))),
                    Math.abs(dot(candidate.axis(), tangents.get(second))));
            if (mutualNormalError <= 0.80) {
                matched.add(new StructurePairCandidate(
                        candidate.firstIndex(), candidate.secondIndex(),
                        candidate.firstPoint(), candidate.secondPoint(),
                        candidate.midpoint(), candidate.axis(),
                        candidate.distance(),
                        bestProgressError + mutualNormalError));
            }
        }
        if (matched.size() < 2) {
            return java.util.List.copyOf(matched);
        }
        final java.util.List<Double> distances = matched.stream()
                .map(StructurePairCandidate::distance).sorted().toList();
        final double localThickness = distances.get(
                (distances.size() - 1) / 2);
        final double maximumLocalChord = localThickness * 4;
        return matched.stream().filter(candidate ->
                candidate.distance() <= maximumLocalChord + 1e-9).toList();
    }

    private static Optional<StructurePairCandidate> shortestStructurePair(
            final java.util.List<ManualWarpControl> ordered) {
        StructurePairCandidate shortest = null;
        for (int first = 0; first < ordered.size(); first++) {
            for (int second = first + 1; second < ordered.size(); second++) {
                final StructurePairCandidate candidate = pairCandidate(
                        ordered, first, second, 0).orElse(null);
                if (candidate != null && (shortest == null
                        || candidate.distance() < shortest.distance()
                                - 1e-12)) {
                    shortest = candidate;
                }
            }
        }
        return Optional.ofNullable(shortest);
    }

    private static StructureBoundaryChain structureBoundaryChain(
            final java.util.List<ManualWarpControl> ordered,
            final int firstTip,
            final int secondTip,
            final int direction) {
        final java.util.List<Integer> indices = new java.util.ArrayList<>();
        indices.add(firstTip);
        int current = firstTip;
        while (current != secondTip) {
            current = Math.floorMod(current + direction, ordered.size());
            indices.add(current);
        }
        final java.util.List<Double> distances = new java.util.ArrayList<>();
        distances.add(0.0);
        double total = 0;
        for (int position = 1; position < indices.size(); position++) {
            total += pointDistance(
                    ordered.get(indices.get(position - 1)).targetPoint(),
                    ordered.get(indices.get(position)).targetPoint());
            distances.add(total);
        }
        final double chainLength = total;
        final java.util.List<Double> progress = distances.stream()
                .map(distance -> chainLength <= 1e-12 ? 0
                        : distance / chainLength).toList();
        return new StructureBoundaryChain(indices, progress);
    }

    private static Optional<StructurePairCandidate> pairCandidate(
            final java.util.List<ManualWarpControl> controls,
            final int firstIndex,
            final int secondIndex,
            final double score) {
        final Point2D first = controls.get(firstIndex).targetPoint();
        final Point2D second = controls.get(secondIndex).targetPoint();
        final double dx = second.x() - first.x();
        final double dy = second.y() - first.y();
        final double distance = Math.hypot(dx, dy);
        return distance <= 1e-9 ? Optional.empty()
                : Optional.of(new StructurePairCandidate(firstIndex,
                        secondIndex, first, second,
                        midpoint(first, second),
                        new Point2D(dx / distance, dy / distance),
                        distance, score));
    }

    private static Point2D localStructureTangent(
            final java.util.List<ManualWarpControl> controls,
            final int index) {
        final Point2D previous = controls.get((index - 1
                + controls.size()) % controls.size()).targetPoint();
        final Point2D next = controls.get((index + 1)
                % controls.size()).targetPoint();
        return unitVector(new Point2D(next.x() - previous.x(),
                next.y() - previous.y()));
    }

    private static Point2D principalStructureAxis(
            final java.util.List<ManualWarpControl> controls) {
        final double centreX = controls.stream().mapToDouble(control ->
                control.targetPoint().x()).average().orElse(0);
        final double centreY = controls.stream().mapToDouble(control ->
                control.targetPoint().y()).average().orElse(0);
        double xx = 0;
        double xy = 0;
        double yy = 0;
        for (final ManualWarpControl control : controls) {
            final double dx = control.targetPoint().x() - centreX;
            final double dy = control.targetPoint().y() - centreY;
            xx += dx * dx;
            xy += dx * dy;
            yy += dy * dy;
        }
        final double angle = 0.5 * Math.atan2(2 * xy, xx - yy);
        Point2D axis = new Point2D(Math.cos(angle), Math.sin(angle));
        if (axis.x() < -1e-12
                || Math.abs(axis.x()) <= 1e-12 && axis.y() < 0) {
            axis = new Point2D(-axis.x(), -axis.y());
        }
        return axis;
    }

    private static Point2D unitVector(final Point2D vector) {
        final double length = Math.hypot(vector.x(), vector.y());
        return length <= 1e-12 ? new Point2D(0, 0)
                : new Point2D(vector.x() / length, vector.y() / length);
    }

    private static double dot(final Point2D first, final Point2D second) {
        return first.x() * second.x() + first.y() * second.y();
    }

    private static double projection(
            final Point2D point,
            final Point2D axis) {
        return dot(point, axis);
    }

    private static boolean properSegmentsIntersect(
            final Point2D firstA,
            final Point2D firstB,
            final Point2D secondA,
            final Point2D secondB) {
        final double a = cross(firstA, firstB, secondA);
        final double b = cross(firstA, firstB, secondB);
        final double c = cross(secondA, secondB, firstA);
        final double d = cross(secondA, secondB, firstB);
        return a * b < -1e-10 && c * d < -1e-10;
    }

    private static double cross(
            final Point2D first,
            final Point2D second,
            final Point2D point) {
        return (second.x() - first.x()) * (point.y() - first.y())
                - (second.y() - first.y()) * (point.x() - first.x());
    }

    private static String stableStructureComponentId(
            final String contourHash,
            final int componentIndex) {
        return contourHash.substring(0, Math.min(16, contourHash.length()))
                + ":principal-" + componentIndex;
    }

    private static String pairedStructureLabel(
            final String componentName,
            final int index,
            final int count) {
        if (count == 1) {
            return componentName + " thickness pair";
        }
        if (index == 0) {
            return componentName + " left pair";
        }
        if (index + 1 == count) {
            return componentName + " right-tip pair";
        }
        return componentName + " middle pair " + index;
    }

    private static java.util.Map<Integer, String> structureComponentNames(
            final java.util.Map<Integer, java.util.List<ManualWarpControl>>
                    groups) {
        final java.util.List<Integer> ordered = groups.entrySet().stream()
                .sorted(java.util.Comparator.comparingDouble(entry -> entry
                        .getValue().stream().mapToDouble(control -> control
                                .targetPoint().y()).average().orElse(0)))
                .map(java.util.Map.Entry::getKey).toList();
        final java.util.Map<Integer, String> names =
                new java.util.LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            names.put(ordered.get(index), ordered.size() == 1 ? "Blade"
                    : index == 0 ? "Upper-blade" : index == 1
                    ? "Lower-blade" : "Remote component " + (index + 1));
        }
        return names;
    }

    private static BladeSeparation bladeSeparation(
            final java.util.List<ManualWarpControl> controls,
            final java.util.Map<String, Integer> assignments) {
        final java.util.Map<Integer, Point2D> centroids = componentCentroids(
                controls, assignments);
        if (!centroids.containsKey(0) || !centroids.containsKey(1)) {
            return new BladeSeparation(new Point2D(0, 0), 0);
        }
        final Point2D first = centroids.get(0);
        final Point2D second = centroids.get(1);
        final double dx = second.x() - first.x();
        final double dy = second.y() - first.y();
        final double length = Math.hypot(dx, dy);
        return length <= 1e-9
                ? new BladeSeparation(new Point2D(0, 0), 0)
                : new BladeSeparation(
                        new Point2D(dx / length, dy / length), length);
    }

    private static StructureAdjustmentDraft withStructureRequest(
            final StructureAdjustmentDraft draft,
            final int thicknessPercent,
            final int gapPercent,
            final java.util.Map<String, Point2D> offsets,
            final int pendingEdits) {
        final java.util.List<ManualWarpControl> requested =
                structureSliderTargets(draft.baselineControls(),
                        draft.units(), offsets, draft.bladeGapAxis(),
                        draft.initialBladeSeparation(), thicknessPercent,
                        gapPercent);
        return new StructureAdjustmentDraft(draft.contentRevision(),
                draft.precondition(), draft.atlasSide(),
                draft.structureAcronym(), draft.contourHash(),
                draft.baselineControls(), requested,
                draft.componentByControlId(), draft.outlinePaths(),
                draft.units(), offsets,
                draft.bladeGapAxis(), draft.initialBladeSeparation(),
                thicknessPercent, gapPercent, pendingEdits, "");
    }

    private static StructureAdjustmentDraft
            structureDraftWithAbsoluteTargets(
            final StructureAdjustmentDraft draft,
            final java.util.List<ManualWarpControl> absoluteTargets,
            final int thicknessPercent,
            final int gapPercent,
            final java.util.Map<String, Point2D> offsets,
            final int pendingEdits) {
        return new StructureAdjustmentDraft(draft.contentRevision(),
                draft.precondition(), draft.atlasSide(),
                draft.structureAcronym(), draft.contourHash(),
                draft.baselineControls(), absoluteTargets,
                draft.componentByControlId(), draft.outlinePaths(),
                draft.units(), offsets,
                draft.bladeGapAxis(), draft.initialBladeSeparation(),
                thicknessPercent, gapPercent, pendingEdits, "");
    }

    private static java.util.List<ManualWarpControl> structureSliderTargets(
            final java.util.List<ManualWarpControl> starts,
            final java.util.List<StructureAdjustmentUnit> units,
            final java.util.Map<String, Point2D> offsets,
            final Point2D gapAxis,
            final double initialGap,
            final int thicknessPercent,
            final int gapPercent) {
        final java.util.Map<String, StructureAdjustmentUnit> unitByControl =
                new java.util.HashMap<>();
        units.forEach(unit -> unit.controlIds().forEach(id ->
                unitByControl.put(id, unit)));
        final java.util.List<ManualWarpControl> result =
                new java.util.ArrayList<>(starts.size());
        for (final ManualWarpControl start : starts) {
            final StructureAdjustmentUnit unit = unitByControl.get(start.id());
            Point2D target = start.targetPoint();
            if (unit.paired()) {
                final int endpoint = unit.controlIds().indexOf(start.id());
                final double sign = endpoint == 0 ? -1 : 1;
                final double half = 0.5 * unit.initialDistance()
                        * thicknessPercent / 100.0;
                target = new Point2D(
                        unit.midpoint().x()
                                + sign * unit.separationAxis().x() * half,
                        unit.midpoint().y()
                                + sign * unit.separationAxis().y() * half);
            }
            // Gap is a coherent translation of each complete blade. Apply it
            // to paired boundary controls and to unpaired terminal controls;
            // pinning the latter visibly stretches and folds the blade ends.
            final double componentSign = unit.componentIndex() == 0
                    ? -1 : unit.componentIndex() == 1 ? 1 : 0;
            final double gapShift = componentSign * 0.5 * initialGap
                    * gapPercent / 100.0;
            target = new Point2D(target.x() + gapAxis.x() * gapShift,
                    target.y() + gapAxis.y() * gapShift);
            final Point2D offset = offsets.get(start.id());
            result.add(copyControlWithTarget(start, new Point2D(
                    target.x() + offset.x(), target.y() + offset.y())));
        }
        return java.util.List.copyOf(result);
    }

    private static java.util.Map<String, Point2D> offsetsForAbsoluteTargets(
            final StructureAdjustmentDraft draft,
            final java.util.List<ManualWarpControl> absoluteTargets,
            final int thicknessPercent,
            final int gapPercent) {
        final java.util.Map<String, Point2D> zero =
                new java.util.LinkedHashMap<>();
        draft.baselineControls().forEach(control -> zero.put(control.id(),
                new Point2D(0, 0)));
        final java.util.Map<String, ManualWarpControl> sliderTargets =
                controlsById(structureSliderTargets(draft.baselineControls(),
                        draft.units(), zero, draft.bladeGapAxis(),
                        draft.initialBladeSeparation(), thicknessPercent,
                        gapPercent));
        final java.util.Map<String, Point2D> result =
                new java.util.LinkedHashMap<>();
        for (final ManualWarpControl absolute : absoluteTargets) {
            final Point2D slider = sliderTargets.get(absolute.id())
                    .targetPoint();
            result.put(absolute.id(), new Point2D(
                    absolute.targetPoint().x() - slider.x(),
                    absolute.targetPoint().y() - slider.y()));
        }
        return java.util.Map.copyOf(result);
    }

    private static StructureAdjustmentDraft rebaseStructureDraft(
            final StructureAdjustmentDraft prior,
            final java.util.List<ManualWarpControl> applied,
            final AlignmentReviewState rebased) {
        final java.util.Map<String, ManualWarpControl> appliedById =
                controlsById(applied);
        final java.util.List<StructureAdjustmentUnit> units = prior.units()
                .stream().map(unit -> rebaseStructureUnit(unit, appliedById))
                .toList();
        final BladeSeparation separation = bladeSeparation(applied,
                prior.componentByControlId());
        final java.util.Map<String, Point2D> offsets =
                new java.util.LinkedHashMap<>();
        final java.util.Map<String, ManualWarpControl> requested =
                controlsById(prior.requestedControls());
        for (final ManualWarpControl control : applied) {
            final Point2D absolute = requested.get(control.id()).targetPoint();
            offsets.put(control.id(), new Point2D(
                    absolute.x() - control.targetPoint().x(),
                    absolute.y() - control.targetPoint().y()));
        }
        return new StructureAdjustmentDraft(rebased.contentRevision(),
                ManualWarpPrecondition.capture(rebased), prior.atlasSide(),
                prior.structureAcronym(), prior.contourHash(), applied,
                prior.requestedControls(), prior.componentByControlId(),
                prior.outlinePaths(), units, offsets,
                separation.axis(), separation.distance(), 100, 0, 1, "");
    }

    private static StructureAdjustmentUnit rebaseStructureUnit(
            final StructureAdjustmentUnit prior,
            final java.util.Map<String, ManualWarpControl> applied) {
        if (!prior.paired()) {
            final Point2D point = applied.get(prior.controlIds().get(0))
                    .targetPoint();
            return new StructureAdjustmentUnit(prior.id(),
                    prior.componentIndex(), prior.componentId(),
                    prior.controlIds(), point,
                    new Point2D(0, 0), 0, prior.displayLabel());
        }
        final Point2D first = applied.get(prior.controlIds().get(0))
                .targetPoint();
        final Point2D second = applied.get(prior.controlIds().get(1))
                .targetPoint();
        final double dx = second.x() - first.x();
        final double dy = second.y() - first.y();
        final double length = Math.hypot(dx, dy);
        final Point2D axis = length <= 1e-9
                ? prior.separationAxis()
                : new Point2D(dx / length, dy / length);
        return new StructureAdjustmentUnit(prior.id(),
                prior.componentIndex(), prior.componentId(),
                prior.controlIds(),
                midpoint(first, second), axis,
                length <= 1e-9 ? prior.initialDistance() : length,
                prior.displayLabel());
    }

    private synchronized void setDirtyStructureAdjustmentDraft(
            final StructureAdjustmentDraft draft,
            final String message) {
        invalidateStructureAdjustmentAudits();
        structureAdjustmentBaseRevision = draft.contentRevision();
        structureAdjustmentState = new StructureAdjustmentViewState(false,
                Optional.of(draft), Optional.empty(), java.util.List.of(),
                Optional.of(message));
        publish();
    }

    private static String pendingStructureChangesMessage(
            final StructureAdjustmentDraft draft) {
        return draft.pendingEditCount() + (draft.pendingEditCount() == 1
                ? " change pending" : " changes pending")
                + " — Calculate structure preview";
    }

    private static java.util.Map<String, ManualWarpControl> controlsById(
            final java.util.List<ManualWarpControl> controls) {
        return controls.stream().collect(java.util.stream.Collectors.toMap(
                ManualWarpControl::id,
                java.util.function.Function.identity(),
                (first, second) -> first,
                java.util.LinkedHashMap::new));
    }

    private static java.util.Map<Integer, Point2D> componentCentroids(
            final java.util.List<ManualWarpControl> controls,
            final java.util.Map<String, Integer> assignments) {
        final java.util.Map<Integer, double[]> sums =
                new java.util.LinkedHashMap<>();
        for (final ManualWarpControl control : controls) {
            final int component = assignments.get(control.id());
            final double[] sum = sums.computeIfAbsent(component,
                    ignored -> new double[3]);
            sum[0] += control.targetPoint().x();
            sum[1] += control.targetPoint().y();
            sum[2]++;
        }
        final java.util.Map<Integer, Point2D> result =
                new java.util.LinkedHashMap<>();
        sums.forEach((component, sum) -> result.put(component,
                new Point2D(sum[0] / sum[2], sum[1] / sum[2])));
        return java.util.Map.copyOf(result);
    }

    private static ManualWarpControl copyControlWithTarget(
            final ManualWarpControl control,
            final Point2D target) {
        return new ManualWarpControl(control.id(), control.atlasSide(),
                control.origin(), control.groupId(),
                control.structureAcronym(), control.sourcePoint(), target);
    }

    private static java.util.Map<String, Integer>
            structureComponentAssignments(
            final SelectedAtlasContour contour,
            final AtlasAnatomicalSide side,
            final java.util.List<ManualWarpControl> controls,
            final java.util.List<Point2D> atlasPoints) {
        final java.util.List<SelectedAtlasContour.ExteriorComponent>
                components = contour.principalExteriorComponents(side);
        if (isConnectedDentateBladeContour(contour, components, controls)) {
            return connectedDentateBladeAssignments(controls, atlasPoints);
        }
        final java.util.Map<String, Integer> result =
                new java.util.LinkedHashMap<>();
        for (int index = 0; index < controls.size(); index++) {
            result.put(controls.get(index).id(), nearestComponent(
                    atlasPoints.get(index), components));
        }
        return java.util.Map.copyOf(result);
    }

    private static java.util.Map<String, Integer>
            structureComponentAssignmentsFromTargets(
            final SelectedAtlasContour contour,
            final AtlasAnatomicalSide anatomicalSide,
            final java.util.List<ManualWarpControl> controls,
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide atlasSide) {
        final java.util.List<SelectedAtlasContour.ExteriorComponent> raw =
                contour.principalExteriorComponents(anatomicalSide);
        if (isConnectedDentateBladeContour(contour, raw, controls)) {
            return connectedDentateBladeAssignments(controls,
                    controls.stream().map(ManualWarpControl::targetPoint)
                            .toList());
        }
        final java.util.List<java.util.List<Point2D>> mapped = raw.stream()
                .map(component -> state.mapAtlasPathToPreview(atlasSide,
                        component.loop(), true)).toList();
        final java.util.Map<String, Integer> result =
                new java.util.LinkedHashMap<>();
        for (final ManualWarpControl control : controls) {
            int nearest = 0;
            double best = Double.POSITIVE_INFINITY;
            for (int component = 0; component < mapped.size(); component++) {
                for (final Point2D point : mapped.get(component)) {
                    final double distance = squaredDistance(
                            control.targetPoint(), point);
                    if (distance < best) {
                        best = distance;
                        nearest = component;
                    }
                }
            }
            result.put(control.id(), nearest);
        }
        return java.util.Map.copyOf(result);
    }

    /**
     * DG-sg is commonly one connected horseshoe contour even though its two
     * visible blades need separate gap controls. Split only that verified
     * acronym when exactly one exterior component is present; unrelated
     * structures keep the conservative disconnected-component behavior.
     */
    private static boolean isConnectedDentateBladeContour(
            final SelectedAtlasContour contour,
            final java.util.List<SelectedAtlasContour.ExteriorComponent>
                    components,
            final java.util.List<ManualWarpControl> controls) {
        return contour.region().acronym().equalsIgnoreCase("DG-sg")
                && components.size() == 1 && controls.size() >= 4;
    }

    /**
     * Deterministically divides a connected DG-sg contour along its minor
     * principal axis. The balanced split guarantees at least two controls on
     * each blade and keeps the result transient: only ordinary shared-warp
     * controls are ever persisted.
     */
    private static java.util.Map<String, Integer>
            connectedDentateBladeAssignments(
            final java.util.List<ManualWarpControl> controls,
            final java.util.List<Point2D> points) {
        if (controls.size() != points.size() || controls.size() < 4) {
            throw new IllegalArgumentException(
                    "Connected dentate blade grouping needs at least four paired points");
        }
        final double centreX = points.stream().mapToDouble(Point2D::x)
                .average().orElseThrow();
        final double centreY = points.stream().mapToDouble(Point2D::y)
                .average().orElseThrow();
        double xx = 0;
        double xy = 0;
        double yy = 0;
        for (final Point2D point : points) {
            final double dx = point.x() - centreX;
            final double dy = point.y() - centreY;
            xx += dx * dx;
            xy += dx * dy;
            yy += dy * dy;
        }
        final double majorAngle = 0.5 * Math.atan2(2 * xy, xx - yy);
        double minorX = -Math.sin(majorAngle);
        double minorY = Math.cos(majorAngle);
        if (minorY < -1e-12
                || (Math.abs(minorY) <= 1e-12 && minorX < 0)) {
            minorX = -minorX;
            minorY = -minorY;
        }
        final double axisX = minorX;
        final double axisY = minorY;
        final java.util.List<Integer> ordered =
                java.util.stream.IntStream.range(0, points.size()).boxed()
                        .sorted(java.util.Comparator
                                .comparingDouble((Integer index) -> {
                                    final Point2D point = points.get(index);
                                    return (point.x() - centreX) * axisX
                                            + (point.y() - centreY) * axisY;
                                })
                                .thenComparingDouble(index ->
                                        points.get(index).y())
                                .thenComparingDouble(index ->
                                        points.get(index).x())
                                .thenComparing(index ->
                                        controls.get(index).id()))
                        .toList();
        final int split = Math.max(2, Math.min(ordered.size() - 2,
                ordered.size() / 2));
        final java.util.Map<String, Integer> result =
                new java.util.LinkedHashMap<>();
        for (int rank = 0; rank < ordered.size(); rank++) {
            final int index = ordered.get(rank);
            result.put(controls.get(index).id(), rank < split ? 0 : 1);
        }
        return java.util.Map.copyOf(result);
    }

    private static int nearestComponent(
            final Point2D point,
            final java.util.List<SelectedAtlasContour.ExteriorComponent>
                    components) {
        int nearest = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int component = 0; component < components.size(); component++) {
            for (final Point2D candidate : components.get(component).loop()) {
                final double distance = squaredDistance(point, candidate);
                if (distance < best) {
                    best = distance;
                    nearest = component;
                }
            }
        }
        return nearest;
    }

    private static double squaredDistance(
            final Point2D first,
            final Point2D second) {
        final double dx = first.x() - second.x();
        final double dy = first.y() - second.y();
        return dx * dx + dy * dy;
    }

    private void discardStructureAdjustmentDraft() {
        invalidateStructureAdjustmentAudits();
        structureAdjustmentState = StructureAdjustmentViewState.inactive();
        structureAdjustmentBaseRevision = -1;
    }

    private void invalidateStructureAdjustmentAudits() {
        structureAdjustmentRequestToken.incrementAndGet();
        pendingStructureAdjustmentAudit = null;
    }

    private java.util.List<ManualWarpControl> retainedControlsExceptGroup(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final String groupId) {
        final java.util.List<ManualWarpControl> retained =
                new java.util.ArrayList<>();
        state.content().hemisphereWarp().ifPresent(warp ->
                retained.addAll(warp.controls().stream()
                        .filter(control -> control.atlasSide() != atlasSide
                                || !control.groupId().equals(groupId))
                        .toList()));
        return retained;
    }

    private java.util.List<ManualWarpControl>
            retainedControlsExceptStructure(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final String structureAcronym) {
        final java.util.List<ManualWarpControl> retained =
                new java.util.ArrayList<>();
        state.content().hemisphereWarp().ifPresent(warp -> retained.addAll(
                warp.controls().stream().filter(control ->
                        !isStructureControl(control, atlasSide,
                                structureAcronym)).toList()));
        return retained;
    }

    private static java.util.List<ManualWarpControl> structureControls(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final String structureAcronym) {
        return state.content().hemisphereWarp()
                .map(warp -> structureControls(warp.controls(), atlasSide,
                        structureAcronym))
                .orElse(java.util.List.of());
    }

    private static java.util.List<ManualWarpControl> structureControls(
            final java.util.List<ManualWarpControl> controls,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final String structureAcronym) {
        return controls.stream().filter(control -> isStructureControl(
                control, atlasSide, structureAcronym)).toList();
    }

    private static boolean isStructureControl(
            final ManualWarpControl control,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final String structureAcronym) {
        return control.atlasSide() == atlasSide
                && (control.origin()
                        == ManualWarpControlOrigin.STRUCTURE_GUIDE
                        || control.origin()
                        == ManualWarpControlOrigin
                                .VERIFIED_STRUCTURE_BOUNDARY)
                && control.structureAcronym().equalsIgnoreCase(
                        structureAcronym);
    }

    private static String structureGroupId(final String acronym) {
        return "structure-guide:" + Objects.requireNonNull(
                acronym, "acronym").trim();
    }

    private void requireManualControlContext() {
        requireOpen();
        final AlignmentReviewState state = session.state();
        if (!state.content().orientation().confirmed()) {
            throw new IllegalStateException(
                    "Choose Direct or Reflected before editing warp points.");
        }
    }

    private static void requireIncludedManualControlSide(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide side) {
        final ManualHemisphereWarp2D.AtlasSide checked = Objects.requireNonNull(
                side, "atlasSide");
        final var content = state.content();
        if (content.reviewSectionMode() != ReviewSectionMode.HALF
                || content.halfAtlasCoverage().includesOppositeRemnant()) {
            return;
        }
        final ManualHemisphereWarp2D.AtlasSide visible =
                PlacedAtlasSideSupport.visibleHalfSide(state);
        if (checked != visible) {
            throw new IllegalArgumentException(
                    "Half refinement is limited to the confirmed visible atlas side. Include an eligible opposite-side remnant before editing that side.");
        }
    }

    private static java.util.List<ManualHemisphereWarp2D.AtlasSide>
            includedManualControlSides(
            final AlignmentReviewState state) {
        final var content = state.content();
        if (content.reviewSectionMode() == ReviewSectionMode.HALF
                && !content.halfAtlasCoverage()
                        .includesOppositeRemnant()) {
            return java.util.List.of(
                    PlacedAtlasSideSupport.visibleHalfSide(state));
        }
        return java.util.List.of(
                ManualHemisphereWarp2D.AtlasSide.values());
    }

    private boolean oppositeRemnantEligibilityIsCurrent() {
        return boundaryWarpOppositeRemnantRevision >= 0
                && boundaryWarpOppositeRemnantRevision
                        == session.state().contentRevision();
    }

    private void clearOppositeRemnantEligibility() {
        boundaryWarpOppositeRemnantAvailable = false;
        boundaryWarpOppositeRemnantIncluded = false;
        boundaryWarpOppositeRemnantRevision = -1;
    }

    private static void requireRefinementControlCount(final int count) {
        if (count < 4 || count
                > ManualHemisphereWarp2D.MAXIMUM_CONTROLS_PER_GROUP) {
            throw new IllegalArgumentException(
                    "Interior and Structure density must be from 4 to 64");
        }
    }

    private static void requireBoundaryControlCount(final int count) {
        if (count < 4 || count
                > ManualHemisphereWarp2D
                        .MAXIMUM_BOUNDARY_CONTROLS_PER_SIDE) {
            throw new IllegalArgumentException(
                    "Boundary control count must be from 4 to 48");
        }
    }

    private static void requireSideCapacity(
            final java.util.List<ManualWarpControl> controls) {
        for (final ManualHemisphereWarp2D.AtlasSide side
                : ManualHemisphereWarp2D.AtlasSide.values()) {
            final long count = controls.stream().filter(control ->
                    control.atlasSide() == side).count();
            if (count > ManualHemisphereWarp2D.MAXIMUM_CONTROLS_PER_SIDE) {
                throw new ManualWarpException(
                        org.atlasalign.application.manual.ManualWarpFailureKind.CONTROL_LIMIT,
                        "This side already has " + count
                                + " points. The limit is "
                                + ManualHemisphereWarp2D.MAXIMUM_CONTROLS_PER_SIDE
                                + "; reduce a group or reset the side.",
                        "Manual control capacity exceeded on " + side
                                + ": " + count);
            }
        }
    }

    public synchronized void setReviewSectionMode(
            final ReviewSectionMode mode) {
        requireOpen();
        if (session.state().content().reviewSectionMode()
                == Objects.requireNonNull(mode, "mode")) {
            return;
        }
        manualWarpRequestToken.incrementAndGet();
        session.apply(new ReviewEdit.SetReviewSectionMode(mode));
        publish();
    }

    /** Rebuilds the crop footprint from copied-image contrast off Swing's EDT. */
    public synchronized void resuggestTissueSupport() {
        requireOpen();
        final AlignmentReviewState captured = session.state();
        final BinaryMask mask = captured.basis().segmentation()
                .filter(result -> !result.supportMask().isEmpty())
                .map(result -> result.supportMask())
                .orElseThrow(() -> new IllegalStateException(
                        "Copied-image contrast did not produce a tissue crop."));
        submitTissueSupportBuild(captured,
                () -> ReviewedTissueSupport.fromMask(mask),
                captured.content().tissueClippingEnabled(),
                "Re-suggest editable tissue crop from copied-image contrast");
    }

    /** Replaces the editable support with one reviewer-drawn polygon. */
    public synchronized void replaceTissueSupportWithPolygon(
            final List<Point2D> polygon) {
        requireOpen();
        final AlignmentReviewState captured = session.state();
        final List<Point2D> requested = List.copyOf(
                Objects.requireNonNull(polygon, "polygon"));
        submitTissueSupportBuild(captured,
                () -> ReviewedTissueSupport.fromPolygon(
                        preview.width(), preview.height(), requested),
                captured.content().tissueClippingEnabled(),
                "Replace editable tissue crop with reviewer-drawn polygon");
    }

    /** Moves one crop-only control without changing either atlas warp field. */
    public synchronized void moveTissueSupportControl(
            final String identifier,
            final Point2D destination) {
        requireOpen();
        final AlignmentReviewState captured = session.state();
        final ReviewedTissueSupport support = captured.content()
                .reviewedTissueSupport().orElseThrow(() ->
                        new IllegalStateException(
                                "No editable tissue crop is available."));
        final Point2D requested = Objects.requireNonNull(
                destination, "destination");
        if (support.control(identifier).point().equals(requested)) {
            return;
        }
        submitTissueSupportBuild(captured,
                () -> support.moveControl(identifier, requested),
                captured.content().tissueClippingEnabled(),
                "Move tissue-crop control " + identifier);
    }

    /** Inserts one crop-only node after the selected polygon edge. */
    public synchronized void insertTissueSupportControl(
            final int componentIndex,
            final int afterVertexIndex,
            final Point2D point) {
        requireOpen();
        final AlignmentReviewState captured = session.state();
        final ReviewedTissueSupport support = captured.content()
                .reviewedTissueSupport().orElseThrow(() ->
                        new IllegalStateException(
                                "No editable tissue crop is available."));
        final String identifier = nextTissueSupportControlId(support);
        submitTissueSupportBuild(captured,
                () -> support.insertControl(componentIndex,
                        afterVertexIndex, identifier,
                        Objects.requireNonNull(point, "point")),
                captured.content().tissueClippingEnabled(),
                "Insert tissue-crop control " + identifier);
    }

    /** Deletes one crop-only node, retaining four nodes per component. */
    public synchronized void deleteTissueSupportControl(
            final String identifier) {
        requireOpen();
        final AlignmentReviewState captured = session.state();
        final ReviewedTissueSupport support = captured.content()
                .reviewedTissueSupport().orElseThrow(() ->
                        new IllegalStateException(
                                "No editable tissue crop is available."));
        submitTissueSupportBuild(captured,
                () -> support.deleteControl(identifier),
                captured.content().tissueClippingEnabled(),
                "Delete tissue-crop control " + identifier);
    }

    public synchronized void setTissueClippingEnabled(
            final boolean enabled) {
        requireOpen();
        if (session.state().content().tissueClippingEnabled() == enabled) {
            return;
        }
        apply(new ReviewEdit.SetTissueClipping(enabled));
    }

    private void submitTissueSupportBuild(
            final AlignmentReviewState captured,
            final Supplier<ReviewedTissueSupport> factory,
            final boolean clippingEnabled,
            final String description) {
        final long requestToken = tissueSupportRequestToken.incrementAndGet();
        final long revision = captured.contentRevision();
        manualWarpExecutor.execute(() -> {
            final ReviewedTissueSupport built;
            try {
                built = Objects.requireNonNull(factory.get(),
                        "tissue-support factory returned null");
            } catch (final RuntimeException error) {
                viewExecutor.execute(() -> showError(
                        "Tissue crop not changed", messageOf(error)));
                return;
            }
            viewExecutor.execute(() -> {
                synchronized (ReviewController.this) {
                    if (closed
                            || requestToken != tissueSupportRequestToken.get()
                            || session.state().contentRevision() != revision) {
                        return;
                    }
                    final AlignmentReviewContent current = session.state().content();
                    // Re-suggesting an unchanged crop is a successful no-op:
                    // retain acceptance, audit history, and any redo entries.
                    if (current.reviewedTissueSupport().filter(built::equals).isPresent()
                            && current.tissueClippingEnabled() == clippingEnabled) {
                        return;
                    }
                    try {
                        if (session.applyIfCurrentRevision(revision,
                                new ReviewEdit.ReplaceReviewedTissueSupport(
                                        built, clippingEnabled, description))) {
                            publish();
                        }
                    } catch (final RuntimeException error) {
                        showError("Tissue crop not changed", messageOf(error));
                    }
                }
            });
        });
    }

    private static String nextTissueSupportControlId(
            final ReviewedTissueSupport support) {
        int suffix = 1;
        while (true) {
            final String candidate = "crop-insert-" + suffix++;
            final boolean used = support.controls().stream()
                    .anyMatch(control -> control.id().equals(candidate));
            if (!used) {
                return candidate;
            }
        }
    }

    private static String manualWarpUserMessage(final Throwable error) {
        if (error instanceof ManualWarpException typed) {
            return typed.userMessage();
        }
        final String message = messageOf(error);
        if (message.contains("Jacobian")
                || message.contains("singular-value")
                || message.contains("anisotropy")) {
            return "That move would fold or sharply distort the atlas. "
                    + "The dot stayed at its last safe position; try a smaller move or add a nearby point.";
        }
        if (message.contains("displacement")) {
            return "That move is too far from the starting dot. "
                    + "Move it a shorter distance or add a nearby point.";
        }
        if (message.contains("strictly inside")
                || message.contains("escaped")) {
            return "That point left the editable image area or crossed the joined midline. "
                    + "Move it back inside, or choose Disjoined for separated tissue halves.";
        }
        if (message.contains("controls per side")
                || message.contains("at most")) {
            return "This side supports at most 256 interior/structure points plus 48 border points. Reduce a group or reset the side first.";
        }
        return message;
    }

    private static Optional<ManualWarpSafetyReport> manualWarpSafetyReport(
            final Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ManualWarpException typed
                    && typed.safetyReport().isPresent()) {
                return typed.safetyReport();
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static String safeStepMessage(
            final BoundaryWarpCandidate candidate) {
        final String fraction = String.format(java.util.Locale.ROOT,
                "%.1f%%", candidate.safeStepFraction() * 100.0);
        return candidate.limitingSafetyReport().map(report ->
                "Safe step " + fraction + " — limiting gate: "
                        + manualWarpSafetySummary(report)
                        + "; requested dots stay for the next pass")
                .orElse("Safe step " + fraction
                        + " — requested dots stay for the next pass");
    }

    static String boundaryWarpSafetyFailureMessage(
            final ManualWarpSafetyReport report) {
        return "No numerically meaningful safe step — limiting gate: "
                + manualWarpSafetySummary(Objects.requireNonNull(
                        report, "report"))
                + ". Requested dots kept; add nearby controls or improve coarse placement.";
    }

    private static String manualWarpSafetySummary(
            final ManualWarpSafetyReport report) {
        final StringBuilder summary = new StringBuilder(
                report.gate().name().toLowerCase(
                        java.util.Locale.ROOT).replace('_', ' '));
        if (report.measuredValue().isPresent()) {
            summary.append(String.format(java.util.Locale.ROOT,
                    " (value %.4g", report.measuredValue().getAsDouble()));
            if (report.threshold().isPresent()) {
                summary.append(String.format(java.util.Locale.ROOT,
                        ", threshold %.4g",
                        report.threshold().getAsDouble()));
            }
            summary.append(')');
        }
        report.affectedSide().ifPresent(side -> summary.append(" on atlas-")
                .append(side.name().toLowerCase(java.util.Locale.ROOT)));
        report.meshLocation().ifPresent(point -> summary.append(
                String.format(java.util.Locale.ROOT,
                        " near preview (%.1f, %.1f)",
                        point.x(), point.y())));
        return summary.toString();
    }

    private long nextManualControlSuffix() {
        return nextManualControlSuffix(session.state());
    }

    private static long nextManualControlSuffix(
            final AlignmentReviewState state) {
        long maximum = 0;
        final java.util.List<ManualWarpControl> controls = state
                .content().hemisphereWarp()
                .map(ManualHemisphereWarp2D::controls).orElse(java.util.List.of());
        for (final ManualWarpControl control : controls) {
            if (!control.id().startsWith("manual-control-")) {
                continue;
            }
            try {
                maximum = Math.max(maximum, Long.parseLong(
                        control.id().substring("manual-control-".length())));
            } catch (final NumberFormatException ignored) {
                // A supplied non-generated identifier does not reserve a suffix.
            }
        }
        if (maximum == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "No manual-control identifier remains available");
        }
        return maximum;
    }

    private static java.util.List<Point2D> interiorControlPoints(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final int count) {
        return PlacedAtlasSideSupport.interiorPoints(
                state, atlasSide, count);
    }

    /** Anatomical raw-atlas midline after the joined coarse placement. */
    private static ManualHemisphereWarp2D.MidlineSegment mappedAtlasMidline(
            final AlignmentReviewState state) {
        final double middle =
                (state.basis().atlas().atlasPlaneWidth() - 1.0) * 0.5;
        final double lastY = state.basis().atlas().atlasPlaneHeight() - 1.0;
        final Point2D first = state.mapAtlasBeforeHemisphereWarp(
                new Point2D(middle, 0));
        final Point2D second = state.mapAtlasBeforeHemisphereWarp(
                new Point2D(middle, lastY));
        return new ManualHemisphereWarp2D.MidlineSegment(first, second);
    }

    private static java.util.List<Point2D>
            bestSupportedInteriorControlPoints(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final int preferredCount) {
        return PlacedAtlasSideSupport.evaluate(
                state, atlasSide, preferredCount)
                .distributedInteriorPoints();
    }

    private static java.util.List<Point2D> suggestedBoundaryPoints(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final int count) {
        final BinaryMask mask = state.basis().segmentation()
                .orElseThrow(() -> new IllegalStateException(
                        "No copied-preview tissue contrast mask is available"))
                .mask();
        final boolean imageLeft = (atlasSide
                == ManualHemisphereWarp2D.AtlasSide.LEFT)
                != state.content().orientation().reflected();
        final double seamX = (mask.width() - 1.0) * 0.5;
        final java.util.List<Point2D> boundary = new java.util.ArrayList<>();
        double centreX = 0;
        double centreY = 0;
        for (int y = 1; y + 1 < mask.height(); y++) {
            for (int x = 1; x + 1 < mask.width(); x++) {
                if (!mask.contains(x, y)
                        || (imageLeft ? x >= seamX - 1 : x <= seamX + 1)) {
                    continue;
                }
                if (!mask.contains(x - 1, y)
                        || !mask.contains(x + 1, y)
                        || !mask.contains(x, y - 1)
                        || !mask.contains(x, y + 1)) {
                    final Point2D point = new Point2D(x, y);
                    boundary.add(point);
                    centreX += x;
                    centreY += y;
                }
            }
        }
        if (boundary.size() < count) {
            throw new IllegalArgumentException(
                    "Copied-image contrast does not provide enough boundary points on "
                            + atlasSide);
        }
        final double cx = centreX / boundary.size();
        final double cy = centreY / boundary.size();
        boundary.sort(java.util.Comparator
                .comparingDouble((Point2D point) -> Math.atan2(
                        point.y() - cy, point.x() - cx))
                .thenComparingDouble(point -> Math.hypot(
                        point.x() - cx, point.y() - cy)));
        return resamplePolyline(boundary, count);
    }

    private static java.util.List<Point2D> resamplePolyline(
            final java.util.List<Point2D> points,
            final int count) {
        if (points.size() < 2 || count < 2) {
            throw new IllegalArgumentException(
                    "Boundary resampling requires at least two points");
        }
        final double[] cumulative = new double[points.size()];
        for (int index = 1; index < points.size(); index++) {
            cumulative[index] = cumulative[index - 1]
                    + pointDistance(points.get(index - 1),
                            points.get(index));
        }
        final double total = cumulative[cumulative.length - 1];
        if (!(total > 0)) {
            throw new IllegalArgumentException(
                    "Boundary points do not span a usable arc");
        }
        final java.util.List<Point2D> sampled = new java.util.ArrayList<>(count);
        int segment = 1;
        for (int sample = 0; sample < count; sample++) {
            final double requested = total * sample / (count - 1.0);
            while (segment + 1 < cumulative.length
                    && cumulative[segment] < requested) {
                segment++;
            }
            final double start = cumulative[segment - 1];
            final double length = cumulative[segment] - start;
            final double fraction = length > 0
                    ? (requested - start) / length : 0;
            sampled.add(interpolate(points.get(segment - 1),
                    points.get(segment), fraction));
        }
        return java.util.List.copyOf(sampled);
    }

    private static double distanceToBoundary(
            final Point2D point,
            final java.util.List<Point2D> boundary) {
        double closest = Double.POSITIVE_INFINITY;
        for (int index = 0; index < boundary.size(); index++) {
            closest = Math.min(closest, distanceToSegment(point,
                    boundary.get(index),
                    boundary.get((index + 1) % boundary.size())));
        }
        return closest;
    }

    private static double distanceToSegment(
            final Point2D point,
            final Point2D first,
            final Point2D second) {
        final double dx = second.x() - first.x();
        final double dy = second.y() - first.y();
        final double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) {
            return pointDistance(point, first);
        }
        final double parameter = Math.max(0, Math.min(1,
                ((point.x() - first.x()) * dx
                        + (point.y() - first.y()) * dy)
                        / lengthSquared));
        return pointDistance(point, new Point2D(
                first.x() + parameter * dx,
                first.y() + parameter * dy));
    }

    private static double pointDistance(
            final Point2D first,
            final Point2D second) {
        return Math.hypot(first.x() - second.x(),
                first.y() - second.y());
    }

    private static Point2D midpoint(
            final Point2D first,
            final Point2D second) {
        return new Point2D((first.x() + second.x()) * 0.5,
                (first.y() + second.y()) * 0.5);
    }

    private static Point2D interpolate(
            final Point2D first,
            final Point2D second,
            final double fraction) {
        return new Point2D(
                first.x() + fraction * (second.x() - first.x()),
                first.y() + fraction * (second.y() - first.y()));
    }

    private record ControlTargetMove(
            String controlId,
            Point2D originalTarget,
            Point2D requestedTarget) {
        private ControlTargetMove {
            controlId = Objects.requireNonNull(controlId, "controlId");
            originalTarget = Objects.requireNonNull(
                    originalTarget, "originalTarget");
            requestedTarget = Objects.requireNonNull(
                    requestedTarget, "requestedTarget");
        }
    }

    private record ClampRequest(
            java.util.List<ControlTargetMove> moves) {
        private ClampRequest {
            moves = java.util.List.copyOf(Objects.requireNonNull(
                    moves, "moves"));
            if (moves.isEmpty() || moves.stream().map(
                    ControlTargetMove::controlId).distinct().count()
                    != moves.size()) {
                throw new IllegalArgumentException(
                        "A clamp request needs unique moved controls");
            }
        }
    }

    private record ManualWarpSolveOutcome(
            java.util.List<ManualWarpControl> controls,
            Optional<ManualHemisphereWarp2D> warp,
            double appliedFraction) {
        private ManualWarpSolveOutcome {
            controls = java.util.List.copyOf(controls);
            warp = Objects.requireNonNull(warp, "warp");
            if (!Double.isFinite(appliedFraction)
                    || appliedFraction <= 0 || appliedFraction > 1) {
                throw new IllegalArgumentException(
                        "Applied manual-warp fraction must be in (0, 1]");
            }
        }
    }

    private record StructurePairCandidate(
            int firstIndex,
            int secondIndex,
            Point2D firstPoint,
            Point2D secondPoint,
            Point2D midpoint,
            Point2D axis,
            double distance,
            double score) {
        private StructurePairCandidate {
            firstPoint = Objects.requireNonNull(firstPoint, "firstPoint");
            secondPoint = Objects.requireNonNull(secondPoint,
                    "secondPoint");
            midpoint = Objects.requireNonNull(midpoint, "midpoint");
            axis = Objects.requireNonNull(axis, "axis");
            if (firstIndex < 0 || secondIndex <= firstIndex
                    || !Double.isFinite(distance) || distance <= 0
                    || !Double.isFinite(score)) {
                throw new IllegalArgumentException(
                        "Structure pair candidate is invalid");
            }
        }
    }

    private record StructureBoundaryChain(
            java.util.List<Integer> indices,
            java.util.List<Double> progress) {
        private StructureBoundaryChain {
            indices = java.util.List.copyOf(Objects.requireNonNull(
                    indices, "indices"));
            progress = java.util.List.copyOf(Objects.requireNonNull(
                    progress, "progress"));
            if (indices.size() < 2 || indices.size() != progress.size()) {
                throw new IllegalArgumentException(
                        "A Structure boundary chain needs matching positions");
            }
        }

        private int interiorCount() {
            return Math.max(0, indices.size() - 2);
        }
    }

    private record StructureAdjustmentAuditJob(
            long token,
            StructureAdjustmentDraft draft,
            java.util.List<ManualWarpControl> controls,
            AtlasOrientation orientation,
            ReviewSectionMode mode,
            ManualHemisphereWarp2D.MidlineSegment midline,
            int width,
            int height) {
        private StructureAdjustmentAuditJob {
            draft = Objects.requireNonNull(draft, "draft");
            controls = java.util.List.copyOf(Objects.requireNonNull(
                    controls, "controls"));
            orientation = Objects.requireNonNull(orientation,
                    "orientation");
            mode = Objects.requireNonNull(mode, "mode");
            midline = Objects.requireNonNull(midline, "midline");
            if (token <= 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                        "Structure audit identity and dimensions must be positive");
            }
        }
    }

    private record StructureCalculationOutcome(
            java.util.List<ManualWarpControl> controls,
            Optional<ManualHemisphereWarp2D> warp,
            java.util.Map<String, Double> retainedFractions,
            java.util.List<StructureAdjustmentLimit> limits,
            Optional<String> highlightedUnitId,
            Optional<ManualWarpSafetyReport> terminalReport) {
        private StructureCalculationOutcome {
            controls = java.util.List.copyOf(Objects.requireNonNull(
                    controls, "controls"));
            warp = Objects.requireNonNull(warp, "warp");
            retainedFractions = java.util.Map.copyOf(
                    new java.util.LinkedHashMap<>(Objects.requireNonNull(
                            retainedFractions, "retainedFractions")));
            limits = java.util.List.copyOf(Objects.requireNonNull(
                    limits, "limits"));
            highlightedUnitId = Objects.requireNonNull(
                    highlightedUnitId, "highlightedUnitId");
            terminalReport = Objects.requireNonNull(
                    terminalReport, "terminalReport");
        }
    }

    private record BladeSeparation(Point2D axis, double distance) {
        private BladeSeparation {
            axis = Objects.requireNonNull(axis, "axis");
            if (!Double.isFinite(distance) || distance < 0) {
                throw new IllegalArgumentException(
                        "Blade separation must be finite and non-negative");
            }
        }
    }

    private record BilateralGridProposal(
            java.util.List<ManualWarpControl> allControls,
            java.util.List<ManualWarpControl> replacements) {
        private BilateralGridProposal {
            allControls = java.util.List.copyOf(allControls);
            replacements = java.util.List.copyOf(replacements);
        }
    }

    private SelectedAtlasContour requireHandleSeedContour() {
        requireOpen();
        if (atlasPlaneLoading || atlasPlane == null
                || atlasPlane.zeroBasedAnteriorPosteriorIndex()
                != session.state().content().coronalLevel()
                .zeroBasedAnteriorPosteriorIndex()) {
            throw new IllegalStateException(
                    "Wait for the exact current atlas plane before seeding handles");
        }
        final SelectedAtlasContour contour = selectedAtlasContour
                .orElseThrow(() -> new IllegalStateException(
                        "Select a visible atlas target first"));
        if (!contour.isPresent()) {
            throw new IllegalArgumentException(
                    "Selected atlas target is not present on the current plane");
        }
        if (contour.width() != session.state().basis().atlas()
                .atlasPlaneWidth()
                || contour.height() != session.state().basis().atlas()
                .atlasPlaneHeight()) {
            throw new IllegalStateException(
                    "Selected atlas target dimensions do not match the verified atlas identity");
        }
        return contour;
    }

    public synchronized void setCoronalLevel(final int index) {
        final AllenCoronalLevel level =
                new AllenCoronalLevel(index);
        if (session.state().content().coronalLevel().equals(level)) {
            return;
        }
        requireOpen();
        session.apply(new ReviewEdit.SetCoronalLevel(level));
        markBoundaryWarpForRecheck();
        requestAtlasPlane(level);
    }

    /**
     * Applies one explicitly selected exploratory candidate as a manual plane
     * in a single auditable revision. Automatic proposal/evidence remain in
     * the immutable review basis.
     */
    public synchronized void applyGuidedManualCandidate(
            final ReviewEdit.ApplyGuidedManualCandidate edit) {
        requireOpen();
        final ReviewEdit.ApplyGuidedManualCandidate checked =
                Objects.requireNonNull(edit, "edit");
        final var current = session.state().content();
        final boolean planeChanged =
                !current.coronalLevel().equals(checked.level())
                || !current.atlasPlaneTilt().equals(checked.tilt());
        session.apply(checked);
        if (!planeChanged) {
            publish();
            return;
        }
        markBoundaryWarpForRecheck();
        requestAtlasPlane(checked.level());
    }

    /**
     * Applies a reviewer-chosen visual starting plane without attaching any
     * candidate score or automatic-confidence evidence.
     */
    public synchronized void applyGuidedManualStartingPlane(
            final ReviewEdit.ApplyGuidedManualStartingPlane edit) {
        requireOpen();
        final ReviewEdit.ApplyGuidedManualStartingPlane checked =
                Objects.requireNonNull(edit, "edit");
        final var current = session.state().content();
        final boolean planeChanged =
                !current.coronalLevel().equals(checked.level())
                || !current.atlasPlaneTilt().equals(checked.tilt());
        session.apply(checked);
        if (!planeChanged) {
            publish();
            return;
        }
        markBoundaryWarpForRecheck();
        requestAtlasPlane(checked.level());
    }

    public synchronized void setSagittalTiltDegrees(final double degrees) {
        requireOpen();
        final AtlasPlaneTilt current = session.state().content()
                .atlasPlaneTilt();
        final AtlasPlaneTilt next = new AtlasPlaneTilt(
                degrees, current.horizontalDegrees());
        if (next.equals(current)) {
            return;
        }
        session.apply(new ReviewEdit.SetAtlasPlaneTilt(next));
        markBoundaryWarpForRecheck();
        requestAtlasPlane(session.state().content().coronalLevel());
    }

    public synchronized void setHorizontalTiltDegrees(final double degrees) {
        requireOpen();
        final AtlasPlaneTilt current = session.state().content()
                .atlasPlaneTilt();
        final AtlasPlaneTilt next = new AtlasPlaneTilt(
                current.sagittalDegrees(), degrees);
        if (next.equals(current)) {
            return;
        }
        session.apply(new ReviewEdit.SetAtlasPlaneTilt(next));
        markBoundaryWarpForRecheck();
        requestAtlasPlane(session.state().content().coronalLevel());
    }

    public synchronized void resetAtlasPlaneTilt() {
        requireOpen();
        final AtlasPlaneTilt current = session.state().content()
                .atlasPlaneTilt();
        if (AtlasPlaneTilt.CORONAL.equals(current)) {
            return;
        }
        session.apply(new ReviewEdit.SetAtlasPlaneTilt(
                AtlasPlaneTilt.CORONAL));
        markBoundaryWarpForRecheck();
        requestAtlasPlane(session.state().content().coronalLevel());
    }

    /** Controls only atlas visualization; it never changes review geometry. */
    public synchronized void setShowAtlasAnatomy(final boolean show) {
        requireOpen();
        if (showAtlasAnatomy == show) {
            return;
        }
        showAtlasAnatomy = show;
        requestAtlasPlane(session.state().content().coronalLevel());
    }

    public synchronized void addLandmark(
            final String identifier,
            final Point2D atlasPoint,
            final Point2D previewPoint) {
        final Point2D constrainedAtlasPoint = constrainToSelectedBoundary(
                atlasPoint, 0.75);
        apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                identifier,
                session.state().content().coronalLevel(),
                session.state().content().atlasPlaneTilt(),
                constrainedAtlasPoint,
                previewPoint)));
    }

    /**
     * Adds an exact-plane correspondence captured from the atlas and tissue
     * panes. The identifier is deterministic within the in-memory review and
     * cannot overwrite an existing manually named pair.
     */
    public synchronized String addClickedLandmark(
            final Point2D atlasPoint,
            final Point2D previewPoint) {
        final String identifier = nextClickedLandmarkId();
        addLandmark(identifier, atlasPoint, previewPoint);
        return identifier;
    }

    public synchronized void moveLandmarkAtlasPoint(
            final String identifier,
            final Point2D atlasPoint) {
        requireGenericLandmark(identifier,
                "Verified anatomical-handle atlas endpoints are immutable");
        apply(new ReviewEdit.MoveLandmarkAtlasPoint(
                identifier, constrainToSelectedBoundary(
                        atlasPoint, 0.75)));
    }

    public synchronized void moveLandmarkPreviewPoint(
            final String identifier,
            final Point2D previewPoint) {
        requireGenericLandmark(identifier,
                "Move typed anatomical handles with the atomic move/refit action");
        apply(new ReviewEdit.MoveLandmarkPreviewPoint(
                identifier, previewPoint));
    }

    private void requireGenericLandmark(
            final String identifier,
            final String message) {
        session.state().content().landmarks().stream()
                .filter(pair -> pair.id().equals(identifier))
                .findFirst()
                .filter(pair -> pair.anatomicalHandleMetadata().isPresent())
                .ifPresent(pair -> {
                    throw new IllegalArgumentException(message);
                });
    }

    public synchronized void setLandmarkRole(
            final String identifier,
            final LandmarkRole role) {
        apply(new ReviewEdit.SetLandmarkRole(identifier, role));
    }

    public synchronized void removeLandmark(
            final String identifier) {
        apply(new ReviewEdit.RemoveLandmark(identifier));
    }

    /**
     * Replaces the current preview-space manual adjustment with its existing
     * adjustment followed by a least-squares landmark similarity correction.
     * This is an explicit, undoable reviewer action.
     */
    public synchronized void fitActiveLandmarks() {
        apply(new ReviewEdit.FitActiveLandmarks());
    }

    /**
     * Applies a reviewer-triggered, non-reflecting global affine correction
     * from three or more non-collinear active landmark pairs.
     */
    public synchronized void fitActiveLandmarksAffine() {
        apply(new ReviewEdit.FitActiveLandmarksAffine());
    }

    /**
     * Fits the bounded atlas-only local deformation from exact-plane FIT
     * landmarks. CHECK landmarks are excluded from fitting and retained for
     * independent residual diagnostics.
     */
    public synchronized void fitActiveLandmarksLocalWarp() {
        apply(new ReviewEdit.FitActiveLandmarksLocalWarp());
    }

    /** Clears whichever mutually exclusive reviewer-local deformation is active. */
    public synchronized void clearLocalWarp() {
        if (session.state().content().hemisphereWarp().isPresent()) {
            apply(new ReviewEdit.ClearHemisphereWarp());
        } else {
            apply(new ReviewEdit.ClearLocalWarp());
        }
    }

    public synchronized void undo() {
        requireOpen();
        final int previousLevel = currentLevel();
        final AtlasPlaneTilt previousTilt = currentTilt();
        if (session.undo()) {
            publishOrReloadForPlane(previousLevel, previousTilt);
        }
    }

    public synchronized void redo() {
        requireOpen();
        final int previousLevel = currentLevel();
        final AtlasPlaneTilt previousTilt = currentTilt();
        if (session.redo()) {
            publishOrReloadForPlane(previousLevel, previousTilt);
        }
    }

    public synchronized void reset() {
        requireOpen();
        if (session.isAtInitialContent()) {
            return;
        }
        final int previousLevel = currentLevel();
        final AtlasPlaneTilt previousTilt = currentTilt();
        manualWarpRequestToken.incrementAndGet();
        session.resetToProposal();
        publishOrReloadForPlane(previousLevel, previousTilt);
    }

    public synchronized void setWarningsAcknowledged(
            final boolean acknowledged) {
        requireOpen();
        warningsAcknowledged = acknowledged;
        publish();
    }

    public synchronized Optional<AcceptedAlignmentSnapshot> accept() {
        requireOpen();
        if (boundaryFitState.active() || boundaryWarpState.active()) {
            showError("Finish border work",
                    "Apply or Cancel the current border preview before accepting.");
            return Optional.empty();
        }
        try {
            final AcceptedAlignmentSnapshot accepted =
                    session.accept(
                            () -> {
                                if (atlasPlaneLoading
                                        || atlasPlane == null
                                        || atlasPlane
                                        .zeroBasedAnteriorPosteriorIndex()
                                        != currentLevel()
                                        || atlasPlane.geometry()
                                        .sagittalDegrees()
                                        != currentTilt().sagittalDegrees()
                                        || atlasPlane.geometry()
                                        .horizontalDegrees()
                                        != currentTilt().horizontalDegrees()) {
                                    throw new ReviewAcceptanceException(
                                            ReviewAcceptanceBlockReason
                                                    .CURRENT_ATLAS_PLANE_NOT_VERIFIED,
                                            "Wait for the verified atlas overlay for the current level before accepting.");
                                }
                                return acceptanceVerifier.verify();
                            },
                            warningsAcknowledged);
            publish();
            return Optional.of(accepted);
        } catch (final ReviewAcceptanceException error) {
            publish();
            showError(
                    "Alignment not accepted",
                    error.getMessage());
            return Optional.empty();
        } catch (final RuntimeException error) {
            publish();
            showError(
                    "Verification failed",
                    messageOf(error));
            return Optional.empty();
        }
    }

    public synchronized void revokeAcceptance() {
        requireOpen();
        if (session.revokeAcceptance()) {
            publish();
        }
    }

    public synchronized boolean isAccepted() {
        return session.acceptedAlignment().isPresent();
    }

    /** Exact immutable acceptance used by the same-window source export. */
    public synchronized Optional<AcceptedAlignmentSnapshot>
            acceptedAlignment() {
        requireOpen();
        return session.acceptedAlignment();
    }

    /** Read-only lifecycle state for rejecting stale asynchronous UI work. */
    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        planeRequestToken.incrementAndGet();
        manualWarpRequestToken.incrementAndGet();
        tissueSupportRequestToken.incrementAndGet();
        boundaryFitRequestToken.incrementAndGet();
        boundaryWarpRequestToken.incrementAndGet();
        invalidateStructureAdjustmentAudits();
        executorShutdown.run();
        final ReviewView target = view;
        if (target != null) {
            viewExecutor.execute(target::reviewClosed);
        }
    }

    private void apply(final ReviewEdit edit) {
        requireOpen();
        session.apply(edit);
        publish();
    }

    private String nextClickedLandmarkId() {
        long maximum = 0;
        for (final LandmarkPair landmark
                : session.state().content().landmarks()) {
            final Matcher match = CLICKED_LANDMARK_ID.matcher(
                    landmark.id());
            if (match.matches()) {
                try {
                    maximum = Math.max(maximum,
                            Long.parseLong(match.group(1)));
                } catch (final NumberFormatException ignored) {
                    // A manually supplied out-of-range identifier is not a
                    // generated landmark identifier.
                }
            }
        }
        if (maximum == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "No generated landmark identifier remains available");
        }
        return "clicked-" + (maximum + 1);
    }

    private void publishOrReloadForPlane(
            final int previousLevel,
            final AtlasPlaneTilt previousTilt) {
        final AllenCoronalLevel current =
                session.state().content().coronalLevel();
        if (previousLevel
                != current.zeroBasedAnteriorPosteriorIndex()
                || !previousTilt.equals(currentTilt())) {
            markBoundaryWarpForRecheck();
            requestAtlasPlane(current);
        } else {
            publish();
        }
    }

    private void requestAtlasPlane(
            final AllenCoronalLevel level) {
        final long token = planeRequestToken.incrementAndGet();
        final AtlasPlaneRequest request = new AtlasPlaneRequest(
                level, currentTilt(), showAtlasAnatomy);
        final AtlasCoronalPlane cached = planeCache.get(request);
        if (cached != null) {
            atlasPlane = cached;
            selectedAtlasContour = selectedAtlasRegion.map(region ->
                    SelectedAtlasContour.from(cached, region));
            atlasPlaneError = null;
            atlasPlaneLoading = false;
            publish();
            return;
        }
        atlasPlaneLoading = true;
        atlasPlaneError = null;
        // Retain the last rendered atlas and selected guide while the next
        // plane loads. The loading banner makes the temporary state explicit,
        // and interaction gates remain disabled until the requested plane is
        // installed. This prevents tab changes and rapid arrow navigation
        // from making the guide flash away.
        publish();
        planeExecutor.execute(() -> {
            if (closed || token != planeRequestToken.get()) return;
            try {
                final AtlasCoronalPlane loaded =
                        Objects.requireNonNull(
                        planeSource.load(request),
                                "Atlas plane source returned null");
                if (loaded.zeroBasedAnteriorPosteriorIndex()
                        != level.zeroBasedAnteriorPosteriorIndex()) {
                    throw new IllegalStateException(
                            "Atlas plane source returned the wrong level");
                }
                viewExecutor.execute(
                        () -> completeAtlasLoad(
                                token, loaded, null));
            } catch (final RuntimeException error) {
                viewExecutor.execute(
                        () -> completeAtlasLoad(
                                token, null, messageOf(error)));
            }
        });
    }

    private synchronized void completeAtlasLoad(
            final long token,
            final AtlasCoronalPlane loaded,
            final String error) {
        if (closed || token != planeRequestToken.get()) {
            return;
        }
        if (loaded != null) {
            final AtlasPlaneRequest request = new AtlasPlaneRequest(
                    new AllenCoronalLevel(
                            loaded.zeroBasedAnteriorPosteriorIndex()),
                    currentTilt(), showAtlasAnatomy);
            planeCache.put(request, loaded);
            atlasPlane = loaded;
            selectedAtlasContour = selectedAtlasRegion.map(region ->
                    SelectedAtlasContour.from(loaded, region));
        }
        atlasPlaneError = error;
        atlasPlaneLoading = false;
        publish();
    }

    private void publish() {
        final AlignmentReviewState state = session.state();
        if (boundaryWarpOppositeRemnantRevision >= 0
                && boundaryWarpOppositeRemnantRevision
                        != state.contentRevision()) {
            clearOppositeRemnantEligibility();
        }
        if (boundaryFitState.active()
                && boundaryFitBaseRevision >= 0
                && state.contentRevision() != boundaryFitBaseRevision) {
            boundaryFitRequestToken.incrementAndGet();
            boundaryFitState = staleBoundaryFitState();
            boundaryFitBaseRevision = -1;
            boundaryFitRefitSuggested = true;
        }
        if (boundaryWarpState.active()
                && boundaryWarpBaseRevision >= 0
                && state.contentRevision() != boundaryWarpBaseRevision) {
            boundaryWarpRequestToken.incrementAndGet();
            boundaryWarpState = staleBoundaryWarpState();
            boundaryWarpBaseRevision = -1;
        }
        if (structureAdjustmentState.active()
                && structureAdjustmentBaseRevision >= 0
                && state.contentRevision()
                        != structureAdjustmentBaseRevision) {
            invalidateStructureAdjustmentAudits();
            structureAdjustmentState = new StructureAdjustmentViewState(
                    false, Optional.empty(), Optional.empty(), Optional.of(
                            "Review geometry changed; the unapplied Structure draft was discarded."));
            structureAdjustmentBaseRevision = -1;
        }
        final ReviewView target = view;
        if (target == null || closed) {
            return;
        }
        final ReviewViewModel model = new ReviewViewModel(
                preview,
                state,
                session.confidence(),
                Optional.ofNullable(atlasPlane),
                atlasPlaneLoading,
                Optional.ofNullable(atlasPlaneError),
                showAtlasAnatomy,
                selectedAtlasRegion,
                selectedAtlasContour,
                session.canUndo(),
                session.canRedo(),
                !session.isAtInitialContent(),
                warningsAcknowledged,
                session.acceptedAlignment().isPresent(),
                new BoundaryFitViewState(
                        boundaryFitState.loading(),
                        boundaryFitState.draft(),
                        boundaryFitState.preview(),
                        boundaryFitState.candidate(),
                        boundaryFitState.message(),
                        boundaryFitRefitSuggested),
                new BoundaryWarpViewState(
                        boundaryWarpState.loading(),
                        boundaryWarpState.primary(),
                        boundaryWarpState.secondary(),
                        boundaryWarpState.message(),
                        boundaryWarpRecheckSuggested),
                structureAdjustmentState);
        viewExecutor.execute(() -> {
            synchronized (ReviewController.this) {
                if (closed || view != target) {
                    return;
                }
            }
            target.render(model);
        });
    }

    private void showError(
            final String title,
            final String message) {
        final ReviewView target = view;
        if (target != null && !closed) {
            viewExecutor.execute(
                    () -> target.showError(title, message));
        }
    }

    private AtlasCoronalPlane requireCurrentAtlasPlane() {
        if (atlasPlaneLoading || atlasPlane == null
                || atlasPlane.zeroBasedAnteriorPosteriorIndex()
                != currentLevel()
                || atlasPlane.geometry().sagittalDegrees()
                != currentTilt().sagittalDegrees()
                || atlasPlane.geometry().horizontalDegrees()
                != currentTilt().horizontalDegrees()) {
            throw new IllegalStateException(
                    "Wait for the verified atlas overlay for the current plane before suggesting a border fit.");
        }
        return atlasPlane;
    }

    private BoundaryFitCandidate requireBoundaryFitCandidate() {
        requireOpen();
        return boundaryFitState.candidate().orElseThrow(() ->
                new IllegalStateException(
                        "Complete at least four guided border matches before applying the fit."));
    }

    private BoundaryFitDraft requireBoundaryFitDraft() {
        requireOpen();
        if (boundaryFitState.loading()) {
            throw new IllegalStateException(
                    "Wait for the current border preview to finish.");
        }
        return boundaryFitState.draft().orElseThrow(() ->
                new IllegalStateException(
                        "Choose Match border points to begin guided placement."));
    }

    private static BoundaryFitAnchor boundaryFitAnchor(
            final BoundaryFitDraft draft,
            final String identifier) {
        return draft.anchors().stream()
                .filter(anchor -> anchor.id().equals(identifier))
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown boundary-fit anchor: "
                                        + identifier));
    }

    private static String boundaryFitPrompt(
            final BoundaryFitDraft draft) {
        final int ready = draft.includedCompletedCount();
        final BoundaryFitAnchor active = draft.activeAnchor().orElse(null);
        if (active == null) {
            return ready + "/4 matches ready — select an atlas dot";
        }
        return "Click the matching cyan-border point, or drag dot "
                + active.ordinal() + " onto it • " + ready + "/4 ready";
    }

    private static String progressiveBoundaryFitMessage(
            final BoundaryFitDraft draft) {
        final int ready = draft.includedCompletedCount();
        if (draft.activeAnchorId().isEmpty()) {
            return ready + "/4 matches ready — select a dimmed atlas dot, then Use selected, or Apply when four are ready";
        }
        if (ready == 0) {
            return "0/4 matches — choose an atlas dot, then its cyan tissue-border position";
        }
        final String preview = switch (ready) {
            case 1 -> "translation preview";
            case 2 -> "proportional preview";
            default -> draft.request().model()
                    == BoundaryFitModel.ORTHOGONAL_XY
                    ? "width/height preview"
                    : "proportional preview";
        };
        return ready + "/4 matches — " + preview
                + " shown; keep matching to enable Apply";
    }

    private BoundaryWarpRequest requireBoundaryWarpDraft() {
        requireOpen();
        return boundaryWarpState.draft().orElseThrow(() ->
                new IllegalStateException(
                        "Choose Border to start atlas-to-tissue boundary pairs."));
    }

    private static Point2D nearestBoundaryPoint(
            final List<BoundaryFitSample> samples,
            final Point2D requested) {
        BoundaryFitSample nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (final BoundaryFitSample sample : samples) {
            final double dx = sample.point().x() - requested.x();
            final double dy = sample.point().y() - requested.y();
            final double squared = dx * dx + dy * dy;
            if (squared < best) {
                best = squared;
                nearest = sample;
            }
        }
        if (nearest == null) {
            throw new IllegalStateException(
                    "The requested border is unavailable on this plane.");
        }
        return nearest.point();
    }

    private static Point2D requirePreviewEndpoint(
            final int previewWidth,
            final int previewHeight,
            final Point2D requested,
            final String label) {
        final Point2D checked = Objects.requireNonNull(
                requested, "requested");
        if (checked.x() < 0 || checked.x() > previewWidth - 1.0
                || checked.y() < 0 || checked.y() > previewHeight - 1.0) {
            throw new IllegalArgumentException(label
                    + " must stay inside the review preview; points may lie outside the cyan tissue crop but not outside the image.");
        }
        return checked;
    }

    private void requireBoundaryCandidateSafe(
            final BoundaryFitCandidate candidate) {
        final AlignmentReviewState current = session.state();
        final BoundaryFitRequest request = candidate.request();
        if (current.contentRevision() != request.contentRevision()
                || !request.planeHash().equals(
                ManualWarpPrecondition.planeSha256(
                        current.basis(), current.content()))
                || !request.tissueSupportHash().equals(current.content()
                .reviewedTissueSupport()
                .map(ReviewedTissueSupport::contentSha256).orElse(""))
                || !request.sourceHash().equals(current.basis()
                .sourceSnapshot().pixelSha256())
                || !request.atlasHash().equals(current.basis()
                .atlas().identitySha256())) {
            throw new BoundaryFitException(
                    org.atlasalign.application.manual
                            .BoundaryFitFailureKind.STALE_RESULT,
                    "The plane, placement, or tissue crop changed; press Refit for the current view.",
                    "Assisted-fit request hashes no longer match the current review state");
        }
        final ReviewEdit.ApplyAssistedBoundaryFit edit =
                boundaryFitEdit(candidate);
        final AlignmentReviewContent candidateContent = edit.apply(
                current.content(), current.basis());
        final AlignmentReviewState candidateState =
                new AlignmentReviewState(current.basis(), candidateContent,
                        current.contentRevision() + 1);
        if (request.sectionMode() != ReviewSectionMode.DISJOINED) {
            requireSafeJoinedAdjustment(
                    candidateContent.manualPreviewAdjustment());
            // ApplyAssistedBoundaryFit already enforces the selected fit
            // model's absolute axis constraint. Retain that semantic here and
            // only apply the remaining controller-level workspace gate.
            requireJoinedIntersectsWorkspace(candidateState);
            return;
        }
        final ManualHemisphereWarp2D.AtlasSide side = request.targetSide()
                .orElseThrow();
        requireSideIntersectsWorkspace(current, side,
                candidateContent.manualSidePlacement());
    }

    private static ReviewEdit.ApplyAssistedBoundaryFit boundaryFitEdit(
            final BoundaryFitCandidate candidate) {
        final BoundaryFitRequest request = candidate.request();
        return new ReviewEdit.ApplyAssistedBoundaryFit(
                request.sectionMode(), request.targetSide(), request.model(),
                candidate.previewCorrection(),
                candidate.includedMatchCount(), candidate.solverRevision(),
                candidate.inputHash(), request.planeHash(),
                request.placementHash(), request.tissueSupportHash());
    }

    private void requireBoundaryWarpCandidateSafe(
            final BoundaryWarpCandidate candidate) {
        final AlignmentReviewState current = session.state();
        final BoundaryWarpRequest request = candidate.request();
        if (current.contentRevision() != request.contentRevision()
                || !request.sourceHash().equals(current.basis()
                .sourceSnapshot().pixelSha256())
                || !request.atlasHash().equals(current.basis()
                .atlas().identitySha256())) {
            throw new ManualWarpException(
                    org.atlasalign.application.manual.ManualWarpFailureKind
                            .STALE_RESULT,
                    "The plane, crop, placement, or warp changed, so the older border preview was discarded. Start Border again.",
                    "Manual boundary-warp revision/source/atlas identity changed");
        }
        request.precondition().requireMatches(
                current.content(), current.basis());
        boundaryWarpEdit(candidate).apply(
                current.content(), current.basis());
    }

    private static ReviewEdit.ApplyManualBoundaryWarp boundaryWarpEdit(
            final BoundaryWarpCandidate candidate) {
        final BoundaryWarpRequest request = candidate.request();
        return new ReviewEdit.ApplyManualBoundaryWarp(
                request.targetSide(), BoundaryWarpSolver.GROUP_ID,
                candidate.replacementControls(), request.precondition(),
                candidate.validatedWarp(), candidate.solverRevision(),
                candidate.inputHash());
    }

    private BoundaryWarpViewState staleBoundaryWarpState() {
        boundaryWarpRecheckSuggested = true;
        return new BoundaryWarpViewState(false,
                BoundaryWarpSideState.empty(),
                BoundaryWarpSideState.empty(), Optional.of(
                        "The plane, side, placement, crop, or warp changed; start Border again."),
                true);
    }

    private BoundaryFitViewState staleBoundaryFitState() {
        boundaryFitRefitSuggested = true;
        return new BoundaryFitViewState(false, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(
                "The plane, placement, side, or crop changed; start border matching again."),
                true);
    }

    private void cancelBoundaryFitForStaleState() {
        boundaryFitRequestToken.incrementAndGet();
        boundaryFitState = staleBoundaryFitState();
        boundaryFitBaseRevision = -1;
        publish();
    }

    private static String boundaryFitMessage(final RuntimeException error) {
        if (error instanceof BoundaryFitException known) {
            return switch (known.kind()) {
                case UNSAFE_TRANSFORM ->
                        "This preview is too stretched, rotated, or moved to apply. Points kept — edit them, restart, or choose Exit match.";
                case INSUFFICIENT_COVERAGE, AMBIGUOUS_MATCH ->
                        known.reviewerMessage()
                                + " Points kept — edit, clear, or restart them.";
                default -> known.reviewerMessage();
            };
        }
        if (error instanceof ManualWarpException known) {
            return known.userMessage()
                    + " Points kept — edit, clear, or restart them.";
        }
        final String message = messageOf(error);
        if (message.contains("perpendicular atlas axes")
                || message.contains("no shear")) {
            return "These matches would skew the atlas. Points kept — move one or more tissue points, restart, or choose Exit match.";
        }
        return message + " Points kept — edit, clear, or restart them.";
    }

    private void markBoundaryWarpForRecheck() {
        final boolean hasAppliedPairs = session.state().content()
                .hemisphereWarp().stream()
                .flatMap(warp -> warp.controls().stream())
                .anyMatch(control -> control.origin()
                        == ManualWarpControlOrigin
                                .ATLAS_TISSUE_BOUNDARY_PAIR);
        if (hasAppliedPairs) {
            boundaryWarpRecheckSuggested = true;
            boundaryWarpState = new BoundaryWarpViewState(
                    boundaryWarpState.loading(),
                    boundaryWarpState.primary(),
                    boundaryWarpState.secondary(),
                    boundaryWarpState.message(), true);
        }
    }

    private Point2D previewCenter() {
        return new Point2D(
                (preview.width() - 1.0) * 0.5,
                (preview.height() - 1.0) * 0.5);
    }

    private static ManualHemisphereWarp2D.AtlasSide oppositeSide(
            final ManualHemisphereWarp2D.AtlasSide side) {
        return side == ManualHemisphereWarp2D.AtlasSide.LEFT
                ? ManualHemisphereWarp2D.AtlasSide.RIGHT
                : ManualHemisphereWarp2D.AtlasSide.LEFT;
    }

    private Point2D constrainToSelectedBoundary(
            final Point2D requested,
            final double tolerancePixels) {
        Objects.requireNonNull(requested, "atlasPoint");
        if (selectedAtlasRegion.isEmpty()) {
            return requested;
        }
        final SelectedAtlasContour contour = selectedAtlasContour
                .orElseThrow(() -> new IllegalStateException(
                        "Wait for the selected atlas target on the current plane"));
        if (!contour.isPresent()) {
            throw new IllegalArgumentException(
                    "Selected atlas target is not present on the current plane");
        }
        return contour.nearestBoundary(requested, tolerancePixels)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Atlas point must lie on the selected "
                                + contour.region().acronym()
                                + " boundary; clear the target for unconstrained points"));
    }

    private int currentLevel() {
        return session.state().content().coronalLevel()
                .zeroBasedAnteriorPosteriorIndex();
    }

    private AtlasPlaneTilt currentTilt() {
        return session.state().content().atlasPlaneTilt();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Alignment review is closed");
        }
    }

    private static String messageOf(final Throwable error) {
        return error.getMessage() == null
                || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private record OutlineSample(String controlId, double progress) {
        private OutlineSample {
            controlId = Objects.requireNonNull(controlId, "controlId");
            if (controlId.isBlank() || !Double.isFinite(progress)
                    || progress < 0) {
                throw new IllegalArgumentException(
                        "Structure outline sample is invalid");
            }
        }
    }

    public enum BoundaryFitEndpoint {
        ATLAS,
        TISSUE
    }
}
