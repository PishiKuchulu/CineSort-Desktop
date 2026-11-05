# CineSort Desktop — Feature Traceability (App Prompt §11)

Statuses per the App Prompt vocabulary. Updated at every phase gate.
Feature definitions and acceptance criteria: `ANALYSIS-AND-ARCHITECTURE.md` §2.

| شناسه | قابلیت | وضعیت | وابستگی | معیار پذیرش | ماژول/فایل مرتبط | وضعیت تست | توضیح |
|---|---|---|---|---|---|---|---|
| F-01 | Source folders management | برنامه‌ریزی‌شده | — | §2 | app-desktop (Sources), core-data-desktop settings | — | Desktop folder picker + drag & drop entry (F-15) |
| F-02 | Destination + root preparation | برنامه‌ریزی‌شده | — | §2 | core-data-desktop (DesktopFileStore) | — | |
| F-03 | Scan engine + filters | پیاده‌سازی‌شده | F-01, F-02 | §2 | core-data-desktop (DesktopStorageGateway), shared/core-domain (ScanRules) | ✅ Gateway scan proven locally (ancestors/siblings/exclusion tests, 2026-11-03) | Settings controls wired in v1.1.0 (ignore samples, subfolders, companions, age guard, max depth), persisted in the settings store and passed to every scan |
| F-04 | Filename parser | برنامه‌ریزی‌شده | — | §2 | shared/core-parser | ✅ 12/12 passed on desktop JVM in the new tree (2026-11-03, byte-identical copy) | Synced verbatim; core proof PASS — implementation = sync + CI wiring in Phase 2 |
| F-05 | Planner + decoration + conflicts | برنامه‌ریزی‌شده | F-04, F-08 (optional data) | §2 | shared/core-domain | ✅ 45/45 passed on desktop JVM in the new tree (2026-11-03, byte-identical copy) | Synced verbatim; core proof PASS |
| F-06 | Preview UI | پیاده‌سازی‌شده | F-05 | §2 | app-desktop (Preview) | — | v1.1.0: search field + status filter chips + free-space shortfall warning (the domain check wired to the UI) |
| F-07 | Edit sheet / inspector + overrides | پیاده‌سازی‌شده | F-05 | §2 | app-desktop (Inspector), core-data-desktop (overrides) | ✅ Re-plan override test in the session suite (2026-11-05) | Desktop form: right-side inspector (Direction 2); edits re-plan in place, session-scoped (persistence is a future slice) |
| F-08 | Metadata enrichment + posters | پیاده‌سازی‌شده | F-05 | §2 | core-data-desktop (metadata) | — | Shipped in v1.0.0; keys encrypted at rest since v1.1.0 (AES-GCM, see SecretCipher) |
| F-09 | Execution + journal + resume | در حال پیاده‌سازی | F-02, F-05 | §2 | core-data-desktop (DesktopStorageGateway + domain OrganizeExecutor) | ✅ End-to-end locally: execute → byte-identical landing, journal DONE (2026-11-03) | Persistent Room journal DONE: v5-shape entities/DAO + RoomJournalStore ported, desktop DB v1 on Room 2.7.2 + sqlite-bundled 2.5.1; functional journal tests green in CI run 41698510306 (2026-11-03) |
| F-10 | Undo | پیاده‌سازی‌شده | F-09 | §2 | core-data-desktop (domain UndoExecutor) | ✅ End-to-end locally: undo restored all 3 files and removed created folders (2026-11-03) | Confirmation dialog added in v1.1.0 (Report + History), plus an in-flight guard |
| F-11 | History & report | برنامه‌ریزی‌شده | F-09 | §2 | app-desktop (History, Report) | — | Categorized failure reasons from day one |
| F-12 | Settings (accordion) | برنامه‌ریزی‌شده | — | §2 | app-desktop (Settings) | — | Same sections and accordion style as Android v1.0.8 |
| F-13 | In-app updater | برنامه‌ریزی‌شده | — | §2 | core-data-desktop (update) | — | Completed-check-only stamp (Android v1.0.7 lesson) |
| F-14 | Onboarding | برنامه‌ریزی‌شده | F-01, F-02, F-12 | §2 | app-desktop (Onboarding) | — | Phase 3 |
| F-15 | Desktop extras (drag & drop, reveal, shortcuts) | پیاده‌سازی‌شده (بخشی) | F-01 | §2 | app-desktop (shell) | — | Drag & drop of folders onto the Setup screen shipped in v1.1.0; reveal/shortcuts still open |
| F-16 | Android data import | نیازمند شفاف‌سازی | F-12 | §2 | — | — | Owner decision pending (Analysis §7.5) |
