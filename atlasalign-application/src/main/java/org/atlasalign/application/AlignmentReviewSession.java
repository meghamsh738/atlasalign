package org.atlasalign.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory review history with explicit, revocable acceptance.
 */
public final class AlignmentReviewSession {

    private final AlignmentReviewBasis basis;
    private final ReviewConfidenceEvaluator confidenceEvaluator;
    private final AlignmentReviewContent initialContent;
    private final boolean adjustedInitialPlacement;
    private final List<AlignmentReviewContent> timeline =
            new ArrayList<>();
    private final List<ReviewAuditEvent> auditTrail =
            new ArrayList<>();
    private List<ReviewAuditSummary> priorAuditHistory = List.of();
    private int cursor;
    private long contentRevision;
    private long auditSequence;
    private AcceptedAlignmentSnapshot accepted;

    public AlignmentReviewSession(
            final AlignmentReviewBasis basis) {
        this(basis, new ReviewConfidenceEvaluator());
    }

    AlignmentReviewSession(
            final AlignmentReviewBasis basis,
            final ReviewConfidenceEvaluator confidenceEvaluator) {
        this(basis, confidenceEvaluator, false);
    }

    /** New manual launches start with an upright rectangular placement.
     * Automatic proposals and restored sessions retain their recorded geometry.
     */
    public static AlignmentReviewSession forNewReview(
            final AlignmentReviewBasis basis) {
        return new AlignmentReviewSession(basis,
                new ReviewConfidenceEvaluator(), true);
    }

    private AlignmentReviewSession(
            final AlignmentReviewBasis basis,
            final ReviewConfidenceEvaluator confidenceEvaluator,
            final boolean uprightManualStart) {
        this.basis = Objects.requireNonNull(basis, "basis");
        this.confidenceEvaluator = Objects.requireNonNull(
                confidenceEvaluator, "confidenceEvaluator");
        final AlignmentReviewContent proposalContent = basis.initialContent();
        initialContent = uprightManualStart
                && proposalContent.workflowMode() == ReviewWorkflowMode.MANUAL_ONLY
                ? new ReviewEdit.MakeAtlasUpright().apply(proposalContent, basis)
                : proposalContent;
        adjustedInitialPlacement = !initialContent.equals(proposalContent);
        new AlignmentReviewState(basis, initialContent, 0);
        timeline.add(initialContent);
        if (adjustedInitialPlacement) {
            audit(ReviewOperation.MAKE_ATLAS_UPRIGHT,
                    "Initialize upright rectangular manual placement; automatic proposal retained in basis",
                    proposalContent, initialContent);
        }
    }

    /** Restores stored geometry directly; prior acceptance never authorizes a reopened review. */
    public static AlignmentReviewSession restore(final AlignmentReviewCheckpoint saved,
            final ReviewAcceptanceVerification currentIdentities) {
        Objects.requireNonNull(saved, "saved");
        Objects.requireNonNull(currentIdentities, "currentIdentities");
        if (!saved.basis().sourceSnapshot().equals(currentIdentities.currentSourceSnapshot())) {
            throw new SourceVerificationException("Saved project source pixels or metadata do not match");
        }
        if (!saved.basis().atlas().equals(currentIdentities.currentAtlas())) {
            throw new IllegalArgumentException("Saved project atlas identity does not match the verified cache");
        }
        return new AlignmentReviewSession(saved);
    }

    private AlignmentReviewSession(final AlignmentReviewCheckpoint saved) {
        basis = saved.basis();
        confidenceEvaluator = new ReviewConfidenceEvaluator();
        initialContent = saved.initialContent();
        adjustedInitialPlacement = saved.adjustedInitialPlacement();
        timeline.add(saved.content());
        contentRevision = saved.contentRevision();
        priorAuditHistory = saved.auditHistory();
        auditSequence = priorAuditHistory.isEmpty() ? 0
                : priorAuditHistory.get(priorAuditHistory.size() - 1).sequence();
        audit(ReviewOperation.REOPEN_PROJECT,
                "Reopen exact saved geometry; renewed atlas acceptance is required",
                saved.content(), saved.content());
    }

    public synchronized AlignmentReviewCheckpoint checkpoint() {
        final List<ReviewAuditSummary> history = new ArrayList<>(priorAuditHistory);
        auditTrail.stream().map(ReviewAuditSummary::from).forEach(history::add);
        return new AlignmentReviewCheckpoint(basis, initialContent, timeline.get(cursor),
                adjustedInitialPlacement, contentRevision, history);
    }

    public List<ReviewAuditSummary> priorAuditHistory() { return priorAuditHistory; }

    public synchronized AlignmentReviewState state() {
        return new AlignmentReviewState(
                basis, timeline.get(cursor), contentRevision);
    }

    /**
     * Returns the immutable starting placement constructed when this session began.
     * In particular, callers must not retrace or rerasterize tissue support
     * merely to decide whether Reset should be enabled.
     */
    public AlignmentReviewContent initialContent() {
        return initialContent;
    }

    public synchronized boolean isAtInitialContent() {
        return timeline.get(cursor).equals(initialContent);
    }

    public synchronized ReviewConfidenceReport confidence() {
        return confidenceEvaluator.evaluate(state());
    }

    public synchronized void apply(final ReviewEdit edit) {
        applyChecked(edit);
    }

    /**
     * Atomically installs an asynchronously prepared edit only when the
     * review revision used to build it is still current. A stale result is
     * discarded without changing history, acceptance, or the audit trail.
     */
    public synchronized boolean applyIfCurrentRevision(
            final long expectedContentRevision,
            final ReviewEdit edit) {
        Objects.requireNonNull(edit, "edit");
        if (expectedContentRevision != contentRevision) {
            return false;
        }
        applyChecked(edit);
        return true;
    }

    private void applyChecked(final ReviewEdit edit) {
        Objects.requireNonNull(edit, "edit");
        final AlignmentReviewContent before = timeline.get(cursor);
        if (!before.workflowMode().permitsManualEdits()
                && !permittedDuringAutomaticReview(edit)) {
            throw new IllegalStateException(
                    "Choose Refine manually before changing the automatic proposal");
        }
        final AlignmentReviewContent after =
                edit.apply(before, basis);
        if (after.equals(before)) {
            throw new IllegalArgumentException(
                    "Review edit did not change the current content");
        }
        new AlignmentReviewState(
                basis, after, contentRevision + 1);
        while (timeline.size() > cursor + 1) {
            timeline.remove(timeline.size() - 1);
        }
        timeline.add(after);
        cursor++;
        contentRevision++;
        accepted = null;
        audit(
                edit.operation(),
                edit.description(),
                before,
                after);
    }

    /** Records selection of a guided-manual candidate already at this plane. */
    public synchronized void recordGuidedManualCandidateSelection(
            final ReviewEdit.ApplyGuidedManualCandidate edit) {
        Objects.requireNonNull(edit, "edit");
        final AlignmentReviewContent current = timeline.get(cursor);
        if (!current.workflowMode().permitsManualEdits()) {
            throw new IllegalStateException(
                    "Choose Refine manually before selecting a guided candidate");
        }
        final AlignmentReviewContent proposed = edit.apply(current, basis);
        if (!proposed.equals(current)) {
            throw new IllegalArgumentException(
                    "A plane-changing candidate must be applied as a review edit");
        }
        audit(ReviewOperation.APPLY_GUIDED_MANUAL_CANDIDATE,
                edit.description(), current, current);
    }

    private static boolean permittedDuringAutomaticReview(
            final ReviewEdit edit) {
        return edit instanceof ReviewEdit.EnterManualRefinement
                || edit instanceof ReviewEdit.SetAtlasOrientation
                || edit instanceof ReviewEdit.SetObservedHemisphere;
    }

    public synchronized boolean undo() {
        if (!canUndo()) {
            return false;
        }
        final AlignmentReviewContent before = timeline.get(cursor);
        cursor--;
        final AlignmentReviewContent after = timeline.get(cursor);
        contentRevision++;
        accepted = null;
        audit(
                ReviewOperation.UNDO,
                "Undo previous review edit",
                before,
                after);
        return true;
    }

    public synchronized boolean redo() {
        if (!canRedo()) {
            return false;
        }
        final AlignmentReviewContent before = timeline.get(cursor);
        cursor++;
        final AlignmentReviewContent after = timeline.get(cursor);
        new AlignmentReviewState(
                basis, after, contentRevision + 1);
        contentRevision++;
        accepted = null;
        audit(
                ReviewOperation.REDO,
                "Redo next review edit",
                before,
                after);
        return true;
    }

    public synchronized void resetToProposal() {
        apply(adjustedInitialPlacement
                ? new ReviewEdit.ResetToInitialPlacement(initialContent)
                : new ReviewEdit.ResetToProposal(initialContent));
    }

    public synchronized boolean canUndo() {
        return cursor > 0;
    }

    public synchronized boolean canRedo() {
        return cursor + 1 < timeline.size();
    }

    public synchronized Optional<AcceptedAlignmentSnapshot>
            acceptedAlignment() {
        return Optional.ofNullable(accepted);
    }

    public synchronized AcceptedAlignmentSnapshot accept(
            final ReviewAcceptanceVerifier verifier,
            final boolean warningsAcknowledged) {
        Objects.requireNonNull(verifier, "verifier");
        accepted = null;
        final ReviewAcceptanceVerification verification;
        try {
            verification = Objects.requireNonNull(
                    verifier.verify(),
                    "verifier returned null");
        } catch (final RuntimeException error) {
            final ReviewAcceptanceException blocked =
                    error instanceof ReviewAcceptanceException known
                            ? known
                            : new ReviewAcceptanceException(
                                    ReviewAcceptanceBlockReason.VERIFICATION_FAILED,
                                    "Live source or atlas verification failed",
                                    error);
            auditAcceptance(
                    false,
                    warningsAcknowledged,
                    Optional.empty(),
                    Optional.of(blocked.reason()));
            throw blocked;
        }
        try {
            verifyAcceptance(
                    verification, warningsAcknowledged);
        } catch (final ReviewAcceptanceException blocked) {
            auditAcceptance(
                    false,
                    warningsAcknowledged,
                    Optional.of(verification),
                    Optional.of(blocked.reason()));
            throw blocked;
        }
        final AlignmentReviewState current = state();
        final ReviewConfidenceReport report =
                confidenceEvaluator.evaluate(current);
        final AlignmentReviewContent content = current.content();
        final long sequence = auditSequence + 1;
            accepted = new AcceptedAlignmentSnapshot(
                content.coronalLevel(),
                content.atlasPlaneTilt(),
                current.preOutlineAtlasToPreview(),
                !content.manualPreviewAdjustment().equals(
                        AlignmentReviewContent.identityPreviewAdjustment()),
                content.outlineWarp(),
                content.outlineAnchorsConfirmed(),
                content.postOutlinePreviewAdjustment(),
                content.hemisphereWarp(),
                content.localWarp(),
                content.orientation(),
                current.observedAnatomicalHemisphere(),
                content.reviewSectionMode(),
                content.manualSidePlacement(),
                content.activeLandmarks(),
                report,
                verification.currentSourceSnapshot(),
                verification.currentAtlas(),
                content.workflowMode(),
                basis.initialPlaneProposal().filter(proposal ->
                        proposal.source() == InitialPlaneSource.LOCAL_DEEPSLICE),
                warningsAcknowledged,
                contentRevision,
                sequence,
                basis.previewMapping(),
                content.reviewedTissueSupport(),
                content.tissueClippingEnabled(),
                content.halfAtlasCoverage());
        auditAcceptance(
                true,
                warningsAcknowledged,
                Optional.of(verification),
                Optional.empty());
        return accepted;
    }

    public synchronized boolean revokeAcceptance() {
        if (accepted == null) {
            return false;
        }
        final AlignmentReviewContent content = timeline.get(cursor);
        accepted = null;
        audit(
                ReviewOperation.REVOKE_ACCEPTANCE,
                "Explicitly revoke alignment acceptance",
                content,
                content);
        return true;
    }

    public synchronized List<ReviewAuditEvent> auditTrail() {
        return List.copyOf(auditTrail);
    }

    private void verifyAcceptance(
            final ReviewAcceptanceVerification verification,
            final boolean warningsAcknowledged) {
        if (!basis.sourceSnapshot().equals(
                verification.currentSourceSnapshot())) {
            throw new ReviewAcceptanceException(
                    ReviewAcceptanceBlockReason.SOURCE_CHANGED,
                    "Source pixels or scientific metadata changed during review");
        }
        if (!basis.atlas().equals(
                verification.currentAtlas())) {
            throw new ReviewAcceptanceException(
                    ReviewAcceptanceBlockReason.ATLAS_IDENTITY_CHANGED,
                    "Verified atlas assets or identity changed during review");
        }
        final AlignmentReviewState current = state();
        if (basis.proposal().geometry()
                .anatomicalLateralityRequiresConfirmation()
                && !current.content().orientation().confirmed()) {
            throw new ReviewAcceptanceException(
                    ReviewAcceptanceBlockReason.ORIENTATION_UNCONFIRMED,
                    "Confirm atlas left/right orientation before acceptance");
        }
        if (current.observedAnatomicalHemisphere()
                == ObservedAnatomicalHemisphere.UNSURE) {
            throw new ReviewAcceptanceException(
                    ReviewAcceptanceBlockReason.LATERALITY_UNSURE,
                    "Observed anatomical hemisphere remains unsure");
        }
        if (current.content().activeLandmarks().stream()
                .anyMatch(landmark -> !basis.hasValidLandmark(landmark))) {
            throw new ReviewAcceptanceException(
                    ReviewAcceptanceBlockReason.LANDMARK_OUT_OF_BOUNDS,
                    "Every active landmark must remain within the atlas and review preview pixel-center domains");
        }
        if (basis.syntheticPixelPolicy()
                == SyntheticPixelReviewPolicy.UNVERIFIED) {
            throw new ReviewAcceptanceException(
                    ReviewAcceptanceBlockReason.SYNTHETIC_PROVENANCE_UNVERIFIED,
                    "Synthetic-pixel exclusion was not verified");
        }
        if (confidenceEvaluator.evaluate(current)
                .requiresAcknowledgement()
                && !warningsAcknowledged) {
            throw new ReviewAcceptanceException(
                    ReviewAcceptanceBlockReason.WARNINGS_NOT_ACKNOWLEDGED,
                    "Review cautions must be explicitly acknowledged");
        }
    }

    private void audit(
            final ReviewOperation operation,
            final String description,
            final AlignmentReviewContent before,
            final AlignmentReviewContent after) {
        auditTrail.add(new ReviewAuditEvent(
                ++auditSequence,
                operation,
                description,
                before,
                after,
                Optional.empty()));
    }

    private void auditAcceptance(
            final boolean succeeded,
            final boolean warningsAcknowledged,
            final Optional<ReviewAcceptanceVerification> verification,
            final Optional<ReviewAcceptanceBlockReason> failureReason) {
        final AlignmentReviewContent content = timeline.get(cursor);
        final ReviewAcceptanceAudit acceptanceAudit =
                new ReviewAcceptanceAudit(
                        succeeded,
                        warningsAcknowledged,
                        verification.map(
                                ReviewAcceptanceVerification
                                        ::currentSourceSnapshot),
                        verification.map(
                                ReviewAcceptanceVerification::currentAtlas),
                        failureReason);
        auditTrail.add(new ReviewAuditEvent(
                ++auditSequence,
                ReviewOperation.ACCEPT,
                succeeded
                        ? "Explicitly accept current alignment"
                        : "Block explicit alignment acceptance",
                content,
                content,
                Optional.of(acceptanceAudit)));
    }
}
