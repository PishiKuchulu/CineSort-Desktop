# CineSort Desktop

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

## Modules

| Module | Contents |
|---|---|
| `:shared:core-parser` | Release filename parser (pure Kotlin/JVM, synced from the Android repo, test-proven) |
| `:shared:core-domain` | Planner, naming rules, journal, execute/undo engines (pure Kotlin/JVM, synced) |
| `:core-data-desktop` | Desktop data layer: Room journal/settings storage, DataStore preferences, filesystem + metadata services |
| `:app-desktop` | Compose Desktop UI and application entry point |

Design and architecture documents live in [`docs/`](docs/) —
start with `docs/ANALYSIS-AND-ARCHITECTURE.md`.

## Build

The Gradle wrapper is not committed yet; use a local Gradle 8.10.2
(CI provisions it the same way).

```bash
# Run all unit tests
gradle :shared:core-parser:test :shared:core-domain:test :core-data-desktop:test

# Run the desktop app
gradle :app-desktop:run

# Package a native installer for the current OS (MSI/EXE on Windows)
gradle :app-desktop:packageDistributionForCurrentOS
```
