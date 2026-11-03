# CineSort Desktop — Feature Traceability (App Prompt §11)

Statuses per the App Prompt vocabulary. Updated at every phase gate.
Feature definitions and acceptance criteria: `ANALYSIS-AND-ARCHITECTURE.md` §2.

| شناسه | قابلیت | وضعیت | وابستگی | معیار پذیرش | ماژول/فایل مرتبط | وضعیت تست | توضیح |
|---|---|---|---|---|---|---|---|
| F-01 | Source folders management | برنامه‌ریزی‌شده | — | §2 | app-desktop (Sources), core-data-desktop settings | — | Desktop folder picker + drag & drop entry (F-15) |
| F-02 | Destination + root preparation | برنامه‌ریزی‌شده | — | §2 | core-data-desktop (DesktopFileStore) | — | |
| F-03 | Scan engine + filters | برنامه‌ریزی‌شده | F-01, F-02 | §2 | core-data-desktop, shared/core-domain (ScanRules) | Rules covered by synced domain tests | Walk is desktop-new; rules are shared |
| F-04 | Filename parser | برنامه‌ریزی‌شده | — | §2 | shared/core-parser | ✅ 12/12 passed on desktop JVM in the new tree (2026-11-03, byte-identical copy) | Synced verbatim; core proof PASS — implementation = sync + CI wiring in Phase 2 |
| F-05 | Planner + decoration + conflicts | برنامه‌ریزی‌شده | F-04, F-08 (optional data) | §2 | shared/core-domain | ✅ 45/45 passed on desktop JVM in the new tree (2026-11-03, byte-identical copy) | Synced verbatim; core proof PASS |
| F-06 | Preview UI | برنامه‌ریزی‌شده | F-05 | §2 | app-desktop (Preview) | — | Includes the visible-only group-toggle semantics and free-space gate (Android audit lessons) |
| F-07 | Edit sheet / inspector + overrides | برنامه‌ریزی‌شده | F-05 | §2 | app-desktop (Inspector), core-data-desktop (overrides) | — | Desktop form: right-side inspector (Direction 2) |
| F-08 | Metadata enrichment + posters | برنامه‌ریزی‌شده | F-05 | §2 | core-data-desktop (metadata) | — | Phase 3 |
| F-09 | Execution + journal + resume | برنامه‌ریزی‌شده | F-02, F-05 | §2 | core-data-desktop (executor, journal) | — | Port of the Android engine; hash-verified cross-volume copies |
| F-10 | Undo | برنامه‌ریزی‌شده | F-09 | §2 | core-data-desktop (undo) | — | Confirmation dialog mandatory (Android audit lesson) |
| F-11 | History & report | برنامه‌ریزی‌شده | F-09 | §2 | app-desktop (History, Report) | — | Categorized failure reasons from day one |
| F-12 | Settings (accordion) | برنامه‌ریزی‌شده | — | §2 | app-desktop (Settings) | — | Same sections and accordion style as Android v1.0.8 |
| F-13 | In-app updater | برنامه‌ریزی‌شده | — | §2 | core-data-desktop (update) | — | Completed-check-only stamp (Android v1.0.7 lesson) |
| F-14 | Onboarding | برنامه‌ریزی‌شده | F-01, F-02, F-12 | §2 | app-desktop (Onboarding) | — | Phase 3 |
| F-15 | Desktop extras (drag & drop, reveal, shortcuts) | برنامه‌ریزی‌شده | F-01 | §2 | app-desktop (shell) | — | Phase 3 |
| F-16 | Android data import | نیازمند شفاف‌سازی | F-12 | §2 | — | — | Owner decision pending (Analysis §7.5) |
