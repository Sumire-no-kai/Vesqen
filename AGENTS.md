# Working on Vesqen

- Read `PRODUCT.md`, `CONTEXT.md`, `docs/PRD.md`, and the relevant implementation before changing behavior. `docs/PRD.md` owns scope and milestone acceptance; `DESIGN.md` owns the visual and interaction baseline.
- Keep the current offline-first Android baseline. Playback uses Media3 in `PlaybackService`; `PlaybackController` exposes commands and `PlaybackSnapshot` to the UI. Queue checkpoints and listening history belong to the service/application lifetime.
- `LibraryCatalog` owns MediaStore/SAF access, private SQLite metadata, scan reconciliation, and source grants. Preserve stable catalog IDs, user metadata, and old recovery state. Only a completed scan may prune unseen rows.
- `PlaybackTelemetry.observe()` is the external Audio Proof seam. UI consumes evidence snapshots; Android/player probes stay behind the adapter. Sampling must follow active observers or an explicit diagnostic recording.
- Preserve confidence, provenance, observation time, unavailable reasons, and privacy filtering. Source format, route selection, direct support, active bit-perfect requests, and external verification are distinct claims.
- Prefer package boundaries and existing patterns. Add engine/module abstractions when a concrete milestone needs them; native playback and experimental USB/AI remain conditional, optional work.
- Inspect Git status first and preserve existing modifications. Scope local refactors and tests to the task; do not infer permission to publish, push, merge, or change releases.
- Use JDK 21 and the checked-in Gradle wrapper. Run focused regressions first, then `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` for substantial changes. Compile instrumentation tests when touched; validate Release when shared playback/storage changes warrant it.
- Review the resulting diff. Report local unit tests/builds, executed instrumentation, manual device QA, remote CI, and milestone acceptance separately. Device-dependent claims require the evidence in `docs/M1_DEVICE_ACCEPTANCE.md` and `docs/M2_DEVICE_ACCEPTANCE.md`.
- End each turn's final user-facing response with a sentence identifying the current model version (for example, GPT-5.6 or GPT-6). Use only the most specific version explicitly available in the session; if the subversion or model identity is unavailable, say so instead of guessing.
