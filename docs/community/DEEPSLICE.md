# Optional DeepSlice

The manual workflow needs no Python, TensorFlow or DeepSlice installation.
DeepSlice is disabled by default for new single-section reviews. When enabled,
it proposes a starting plane from copied preview pixels; orientation and anatomy
still require reviewer confirmation. A verified runtime manifest is required.

Earlier development validation exercised a separately provisioned **macOS arm64**
runtime using DeepSlice 1.2.8, Python 3.11.15 and TensorFlow 2.21.0. That evidence
applies to that runtime only. Windows/Linux DeepSlice and arbitrary pip
installations have not been established as supported by this beta.
No model weights or Python environment are included in the release.

Use the runtime/work-directory controls to select an existing verified runtime.
Keep its manifest, worker and weights intact. A plain environment without the
required runtime manifest will be rejected. Failed or unavailable prediction is
reported; use the manual starting-plane workflow instead of assuming a proposal
was produced.

Source and separate terms: [DeepSlice](https://github.com/PolarBean/DeepSlice).
Report the exact runtime and platform when reporting prediction issues. Never
include model weights or private images in an issue attachment.
