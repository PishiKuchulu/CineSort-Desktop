# CineSort Desktop — Analysis & Architecture (Phases 0–1)

Date: 2026-11-03. Method: App Prompt Special 2 (analysis before code),
PFP v5.7.7.0 (desktop category). Status: owner CONFIRMED §7 decisions 1–4 on 2026-11-03 (👍): stack A,
separate repo, design Direction 2, macOS unsigned v1. F-16 deferred
(needs clarification). Phase 2 in progress.

## 1. Understanding of the product

CineSort Desktop is the Windows/macOS/Linux edition of the shipped
Android app CineSort (v1.0.8): it scans folders full of messy
release-style video filenames, parses each name (title, year, season,
episode), plans a clean library layout — `<Destination>/Movie/<Title
(Year) (Genres) rating/…` and `<Destination>/Tv series/<Series>/Sxx/…`
— lets the user review and correct the plan, then MOVES the files
(never renames them), journaling every operation so any run can be
resumed after an interruption and fully undone. Optional TMDB/OMDb
enrichment (user's own API keys) decorates folder names and downloads
posters. The product is offline-first: the core flow needs no network
and no account.

The desktop edition keeps the Android product's behavior and data
model, replaces Android-only machinery (SAF file access, WorkManager)
with desktop equivalents (real paths, a coroutine execution service),
and adapts the UI to wide windows, mouse + keyboard, and drag & drop.

## 2. Feature inventory (IDs per App Prompt rule 1)

| ID | Feature | Acceptance criteria (summary) |
|---|---|---|
| F-01 | Source folders: add/remove multiple sources, per-source max depth, include-subfolders toggle | Folder picker adds a source; invalid/unreadable paths show a localized error, never a crash; settings persist across restarts |
| F-02 | Destination: pick destination root; `Movie/` and `Tv series/` roots prepared automatically | Roots are created or reported already-present; failure (read-only volume) is surfaced with the reason |
| F-03 | Scan: recursive walk with filters (min file size, ignore samples, video extensions, per-source depth, global depth cap) | Live counters; cancel stops promptly; unreadable entries are counted and summarized, not fatal |
| F-04 | Parser: release filename → title/year/season/episode/quality | The Android parser's full test suite passes unmodified |
| F-05 | Planner: target paths, folder-name decoration (genres/rating toggles per media type), conflict detection | Same plan output as Android for the same inputs (domain tests pass unmodified) |
| F-06 | Preview: grouped plan list, search, status filters, tri-state group selection, bulk select, summary counts, free-space warning | Group toggle affects exactly the visible expectation (incl. filtered items — behavior specified, not accidental); free-space shortfall blocks or loudly confirms before a run |
| F-07 | Edit sheet: correct title/year/season/episodes, conflict resolution, persists title overrides | Invalid fields show inline errors; applied edits replan visibly; overrides survive restarts |
| F-08 | Metadata enrichment: TMDB/OMDb with user keys (encrypted at rest), folder decoration data, poster download (opt-in), metadata cache | No key → core flow unaffected; provider failure degrades to a visible banner + hold/continue choice; keys never logged |
| F-09 | Execution: move files + companions (subtitles), cross-volume copy with SHA-256 verify before source delete, journal, resume after interruption/kill | Killing the app mid-run and relaunching offers resume with identical end state; a failed item keeps its source file untouched |
| F-10 | Undo: roll back a whole run (files return to origins, created folders cleaned) | Undo asks for confirmation, reports moved-back vs problem counts honestly |
| F-11 | History & report: run list, per-run report (done/failed/skipped/undone, failure categories), retry failed items, share diagnostics | Failure reasons are categorized in user language; report names the run's destination and date |
| F-12 | Settings: language (FA/EN + RTL), theme (system/light/dark), naming rules, safety toggles, metadata behavior, accordion-card layout | Every control wired to real logic; dangerous toggles carry consequence text; settings persist |
| F-13 | In-app updater: checks GitHub Releases, verifies SHA-256 sidecar, surfaces HTTP errors, downloads and hands off to the OS installer | A failed check never reads as "up to date"; the 1.0.7 lesson (stamp only on completed checks) is implemented from day one |
| F-14 | First-run onboarding: language, destination, sources, optional keys, summary | Back navigation is stepwise; finishing shows a summary of choices; skippable keys step |
| F-15 | Desktop extras: drag & drop folders onto the window to add sources, reveal-in-file-manager, keyboard shortcuts for the main flow | Dropping a folder adds it as a source with the same validation as the picker |
| F-16 | Android→desktop data import (settings + title overrides via an export file) | NEEDS CLARIFICATION — scope and format are the owner's decision |

## 3. Dependencies

F-03→F-04→F-05 form the offline pipeline; F-06/F-07 depend on F-05;
F-09 depends on F-05 + F-02; F-10/F-11 depend on F-09's journal; F-08
enriches F-05 but nothing depends on it; F-13/F-14/F-15 are shell-level
and independent. The shared core (F-04, F-05, and the rules inside
F-03) already exists and is test-proven on Android.

## 4. Phasing (App Prompt §10)

- **Phase 0 — Analysis (this document):** inventory, risks, questions.
- **Phase 1 — Architecture & design:** module map (§6), data model
  (§6), design directions + owner's pick (DESIGN-DIRECTIONS.md).
- **Phase 2 — Core & MVP:** repo scaffold, shared-core sync + CI,
  desktop file store, executor + journal port, Compose Desktop shell
  with Scan → Preview → Run → Report flow (F-01..F-07, F-09, F-10,
  F-12 subset) — fully offline.
- **Phase 3 — Completion:** F-08 metadata + posters, F-11 full report,
  F-13 updater, F-14 onboarding, F-15 desktop extras.
- **Phase 4 — Stability & quality:** test deepening, error-state sweep,
  performance on 100k-file libraries, security review vs PC-1.
- **Phase 5 — Release readiness:** installers (Windows first, then
  Linux, then macOS), release-verification receipts (G3), user docs,
  compliance matrix (G4).

## 5. Technical risks

- **R-1 Compose Desktop maturity:** the toolkit is stable, but some
  Android composables (navigation, dialogs) need desktop patterns.
  Mitigation: shell stays deliberately simple (single window, sidebar).
- **R-2 Core drift:** two repos share the parser/domain. Mitigation:
  checksum-verified sync gate in CI (PFP rule 7); Android repo leads.
- **R-3 Room on JVM:** supported (Room 2.6+ ships JVM artifacts via
  KMP); the Android schema v5 reuses, but migrations must be tested on
  desktop SQLite. Mitigation: migration test in Phase 2.
- **R-4 macOS distribution:** unsigned builds trigger Gatekeeper;
  notarization needs a paid Apple Developer account. Mitigation:
  disclose in Phase 5; Windows/Linux ship first.
- **R-5 Sandbox limits:** this environment cannot run Gradle or a
  Windows/macOS host; full builds verify in CI (G1/G2 honesty rules).

## 6. Architecture (summary; full text in the private PFP pack)

Separate repo `CineSort-Desktop`. Modules: `shared/core-parser`,
`shared/core-domain` (synced verbatim), `core-data-desktop` (Room JVM,
DataStore file store, metadata clients, DesktopFileStore, executor +
journal + undo, updater), `app-desktop` (Compose Desktop). Data model:
the Android Room v5 entities unchanged (runs, operations, title
overrides, metadata cache); settings as a DataStore document mirroring
Android's AppSettings fields 1:1 so behavior matches.

## 7. Open questions / decisions for the owner

1. **Stack:** options in §8 — recommendation A (Kotlin + Compose
   Desktop). (The owner's "start" followed the feasibility answer that
   proposed A; A is treated as proposed-not-final until confirmed here.)
2. **Repo:** separate `CineSort-Desktop` repo (recommended, AD-1) vs a
   desktop module inside the Android repo (risks the shipping app).
3. **Design direction:** three directions in DESIGN-DIRECTIONS.md;
   recommendation = Direction 2 (Modern Premium) expressed in the
   existing Atelier brand tokens.
4. **macOS v1:** unsigned personal-use build acceptable, notarization
   as a later phase? (Recommended: yes.)
5. **F-16** (Android data import): in scope at all? If yes, which phase?

## 8. Stack options (App Prompt rule 3)

| Option | Pros | Cons |
|---|---|---|
| **A. Kotlin + Compose Multiplatform Desktop (recommended)** | Parser/domain/planner reused verbatim with their tests; one language with the Android app; native packaging for all 3 OSes | UI must be ported (not shared); Compose Desktop ecosystem smaller than Android's |
| B. Tauri (Rust) + web UI | Tiny binaries; strong Windows story | Full rewrite of parser/planner/journal in Rust/TS — discards the proven, test-covered core; two new languages to maintain |
| C. C#/.NET + Avalonia | Good desktop tooling; single-file exes | Full rewrite like B; weakest macOS/Linux parity of the three; splits the product's future fixes across two codebases forever |

Option A is the only one that keeps the product's brain — the part
that is already verified — intact.
