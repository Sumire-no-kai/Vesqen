# Vesqen Roadmap

The normative scope, acceptance criteria, and product boundaries live in [PRD.md](PRD.md). This page is only a compact navigation aid and must not be used to claim that a milestone is complete.

- **M0 — Foundation:** Android scaffold, durable package identity, build/lint/unit-test workflow, CI, contributor documentation, and evidence-level rules.
- **M1 — Local player MVP:** MediaStore/SAF library, Media3-based playback, metadata, queue, background controls, focus handling, ordinary Android routes, the formal `Library / Now / Settings` shell, and the secondary Chain evidence surface.
- **M2 — Audio Proof:** auditable source/decoder/processing/route telemetry with explicit confidence levels.
- **M3 — Android 14+ USB bit-perfect and Library performance convergence:** capability-gated official mixer attributes, centralized strategy decisions, fail-closed behavior, plus root-cause analysis and a two-device verified fix for Library home fast-scroll jank.
  - [Implementation checklist and open gates](M3_IMPLEMENTATION_PLAN.md): a software candidate and partial device evidence exist; Library fixes and hardware acceptance remain open while M4 preparation can proceed.
- **M4 — Verification and public beta:** device matrix, external bit-perfect evidence, endurance testing, accessibility, localization, and performance budgets.
  - [Development tasks and dependencies](M4_IMPLEMENTATION_PLAN.md): evidence records, test vectors, declaration matching, stability, performance/UI acceptance, and beta preparation. Missing hardware and deferred endurance tests remain explicit gates.
- **M5+ — Conditional extensions:** advanced formats, DAC lab, incremental playback-kernel work, legacy USB experiments, and optional on-device audio intelligence.

A build, passing test, implemented API call, or matching DAC display does not by itself complete an audio-verification milestone. Completion requires the corresponding PRD acceptance evidence.

## Accepted additions, 2026-09-07

M1 additions and the M2 Bluetooth supplement have implementation candidates. The connected iQOO confirmed the scrubber alignment fix and the larger play/pause cover transition. Order editing now stays in the original list, and the advanced Chain dashboard uses compact evidence rows rather than large repeated cards. Local unit/lint/build gates pass; iQOO scroll acceptance, updated instrumentation execution, Honor coverage and Bluetooth hardware checks remain separate open gates.

- **M1 library:** optional A-Z/# jump index, on by default only for unfiltered All Songs in title order; no index inside Favorites or other collections. Favorites and each custom playlist gain an independently persisted manual order, with accessible editing and safe migration.
- **M1 playback quality:** make play/pause artwork scaling perceptible but restrained, fix scrubber thumb alignment using device evidence, and investigate fast library-scroll jank with controlled measurements on the reported device.
- **M2 Bluetooth evidence:** show publicly observable Bluetooth audio device and route information; expose negotiated codec, configuration and transport rates only where reliable APIs provide them. Unavailable fields retain reasons; no Bluetooth bit-perfect claim or hidden-API dependency.
- **Delivery:** PRD F1.1/F1.2, F3, F6.5 and section 10.3 own the detailed contracts. The M1/M2 device acceptance documents include the new checks; earlier test results do not accept this expanded scope.
