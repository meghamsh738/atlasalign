# Contributing

Start with a reproducible issue and discuss substantial changes before implementation.
Use Java 17+, Maven and Python 3.10+. Run `./scripts/test.sh` (Windows:
`python scripts/test.py`) and include relevant UI evidence for interface changes.
Tests use synthetic fixtures unless explicitly enabled with external data.
Never commit laboratory images, patient information, model weights or runtime caches.

Read `docs/SCIENTIFIC_INVARIANTS.md` and `docs/TRANSFORM_CONVENTIONS.md` before
changing image, coordinate or export code. Source pixels, calibration, channel
state, overlays and ROI Manager contents must be preserved. Add tests for each
affected invariant. Do not weaken export gates or fabricate confidence values.

Keep fixes small, explain the observed behavior and resulting behavior, and
record the commands actually tested. Cross-platform CI cannot substitute for
native Fiji GUI testing. Project contributions are under the existing MIT
license; preserve all third-party notices.
