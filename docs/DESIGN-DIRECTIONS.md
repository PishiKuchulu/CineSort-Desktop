# CineSort Desktop — Design Directions (UI/UX Prompt Special 1)

Produced before any UI implementation, per the UI/UX prompt: three
directions, a recommendation with reasons, then the final build follows
the pick. All three directions share the product's functional skeleton
(desktop-adapted from the Android app): a left navigation rail
(Home / Sources / Preview / History / Settings), a content area built
on cards, the Scan → Preview → Run → Report flow as the spine, and the
owner-approved accordion cards in Settings. All directions obey the
prompt's rules: 4/8 spacing grid, semantic colors, full interactive
states (hover, focus-visible, pressed, disabled, loading, selected,
error, success), dangerous actions confirmed, long names truncated
mid-string (never silently losing the distinctive tail), empty/loading/
error states designed, not improvised.

## Direction 1 — Minimal & Professional

- **Feel:** a serious file tool. Flat surfaces, 1px borders instead of
  shadows, compact rows, system font, restrained use of the brand tint.
- **Layout:** dense preview table (sortable columns: File, Parsed
  title, Destination, Status), toolbar with grouped actions, status
  bar with live counts.
- **Strengths:** fastest to scan for power users with 10k-file
  libraries; least visual risk.
- **Weaknesses:** cold; newcomers get no guidance from the visuals;
  diverges most from the Android app's warm identity.

## Direction 2 — Modern & Premium (RECOMMENDED)

- **Feel:** the Android app's sibling, grown up: the Atelier palette
  (warm paper light theme, deep ink dark theme, aperture motif as the
  brand mark), large rounded cards (20–28dp radii), soft shadows,
  generous spacing, titleLarge section headers, accordion Settings
  cards exactly as approved on Android v1.0.8.
- **Layout:** card-based preview groups (like Android, widened:
  poster thumb + parsed title + destination path + status chip),
  a persistent right-side inspector for the selected item (the desktop
  answer to the Android edit sheet — always visible, no modal hunt),
  progress as a slim top bar + detail card during runs.
- **Strengths:** one product identity across phone and desktop; the
  owner's already-approved patterns carry over; card groups handle
  mixed movie/episode plans better than a rigid table.
- **Weaknesses:** slightly less dense than Direction 1 for extreme
  libraries (mitigated by a density toggle in Preview).

## Direction 3 — Creative & Energetic

- **Feel:** film-festival energy: the film-perforation motif from the
  Android onboarding becomes a design element (perforated edge on the
  rail, reel-style progress), duotone poster-forward preview grid,
  springy micro-animations on group expand and run completion.
- **Strengths:** memorable; posters make the library feel alive.
- **Weaknesses:** decoration competes with a tool whose job is trust
  and clarity during irreversible file moves; animation budget risks
  the "calm during a 40-minute run" requirement. The UI/UX prompt's
  own priority order (clarity > beauty > creativity) argues against it
  as the base — its best elements (perforation motif, poster thumbs)
  can be borrowed into Direction 2 sparingly.

## Recommendation

**Direction 2**, with two borrowings: Direction 1's optional compact
density for the Preview list, and Direction 3's film-perforation motif
confined to the empty states and the About/Onboarding surfaces.
Reasons: (1) product identity continuity with the shipped Android app
— same tokens, same accordion Settings, same status language; (2) the
inspector pattern is a genuine desktop UX upgrade over modal sheets
for the edit-heavy Preview step; (3) it keeps clarity first during
destructive operations while still feeling premium. If the owner
prefers another direction, the functional skeleton is unchanged and
only tokens/layout density move.
