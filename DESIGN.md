---
name: Vesqen
description: A quiet, precise visual system for local listening and auditable playback.
colors:
  signal-moss: "#9FBF4B"
  signal-moss-bright: "#BFD66B"
  signal-moss-deep: "#536B1E"
  carbon-black: "#0F0F0F"
  carbon-surface: "#171914"
  carbon-elevated: "#23261E"
  pure-white: "#FFFFFF"
  frost-surface: "#F6F7F2"
  ink-dark: "#1B1C18"
  ink-light: "#E7E8E1"
  muted-dark: "#C8C9BE"
  muted-light: "#5E6056"
  now-canvas: "#101415"
  now-dock: "#191F20"
  now-raised: "#202728"
  now-artwork-frame: "#252C2D"
  warning-amber-bright: "#F2C36B"
  warning-amber-deep: "#7A4F00"
  error: "#BA1A1A"
typography:
  display:
    fontFamily: "Roboto, Noto Sans SC, sans-serif"
    fontSize: "32sp"
    fontWeight: 600
    lineHeight: "38sp"
    letterSpacing: "-0.02em"
  headline:
    fontFamily: "Roboto, Noto Sans SC, sans-serif"
    fontSize: "28sp"
    fontWeight: 600
    lineHeight: "34sp"
    letterSpacing: "-0.01em"
  title:
    fontFamily: "Roboto, Noto Sans SC, sans-serif"
    fontSize: "20sp"
    fontWeight: 600
    lineHeight: "26sp"
    letterSpacing: "normal"
  body:
    fontFamily: "Roboto, Noto Sans SC, sans-serif"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "24sp"
    letterSpacing: "0.01em"
  label:
    fontFamily: "Roboto, Noto Sans SC, sans-serif"
    fontSize: "12sp"
    fontWeight: 600
    lineHeight: "16sp"
    letterSpacing: "0.02em"
  action:
    fontFamily: "Roboto, Noto Sans SC, sans-serif"
    fontSize: "14sp"
    fontWeight: 600
    lineHeight: "20sp"
    letterSpacing: "0.01em"
  data:
    fontFamily: "Roboto Mono, monospace"
    fontSize: "12sp"
    fontWeight: 500
    lineHeight: "18sp"
    letterSpacing: "normal"
rounded:
  album: "10dp"
  control: "12dp"
  surface: "16dp"
  pill: "999dp"
spacing:
  xxs: "4dp"
  xs: "8dp"
  sm: "12dp"
  md: "16dp"
  lg: "24dp"
  xl: "32dp"
components:
  button-primary-light:
    backgroundColor: "{colors.signal-moss-deep}"
    textColor: "{colors.pure-white}"
    typography: "{typography.action}"
    rounded: "{rounded.control}"
    padding: "12dp 20dp"
    height: "48dp"
  button-primary-dark:
    backgroundColor: "{colors.signal-moss-bright}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.action}"
    rounded: "{rounded.control}"
    padding: "12dp 20dp"
    height: "48dp"
  status-chip-neutral-dark:
    backgroundColor: "{colors.carbon-elevated}"
    textColor: "{colors.muted-dark}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "6dp 10dp"
  status-chip-neutral-light:
    backgroundColor: "{colors.frost-surface}"
    textColor: "{colors.muted-light}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "6dp 10dp"
  status-chip-available-dark:
    backgroundColor: "{colors.carbon-surface}"
    textColor: "{colors.signal-moss-bright}"
    borderColor: "{colors.signal-moss-bright}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "6dp 10dp"
  status-chip-available-light:
    backgroundColor: "{colors.frost-surface}"
    textColor: "{colors.signal-moss-deep}"
    borderColor: "{colors.signal-moss-deep}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "6dp 10dp"
  status-chip-active-dark:
    backgroundColor: "{colors.signal-moss-bright}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "6dp 10dp"
  status-chip-active-light:
    backgroundColor: "{colors.signal-moss-deep}"
    textColor: "{colors.pure-white}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "6dp 10dp"
  track-row-dark:
    backgroundColor: "{colors.carbon-black}"
    textColor: "{colors.ink-light}"
    rounded: "{rounded.album}"
    padding: "8dp 16dp"
    height: "72dp"
  track-row-light:
    backgroundColor: "{colors.pure-white}"
    textColor: "{colors.ink-dark}"
    rounded: "{rounded.album}"
    padding: "8dp 16dp"
    height: "72dp"
  mini-player-dark:
    backgroundColor: "{colors.carbon-elevated}"
    textColor: "{colors.ink-light}"
    rounded: "{rounded.surface}"
    padding: "8dp 12dp"
    height: "72dp"
  mini-player-light:
    backgroundColor: "{colors.frost-surface}"
    textColor: "{colors.ink-dark}"
    rounded: "{rounded.surface}"
    padding: "8dp 12dp"
    height: "72dp"
  bottom-navigation-dark:
    backgroundColor: "{colors.carbon-black}"
    textColor: "{colors.muted-dark}"
    padding: "8dp 12dp"
    height: "80dp"
  bottom-navigation-light:
    backgroundColor: "{colors.pure-white}"
    textColor: "{colors.muted-light}"
    padding: "8dp 12dp"
    height: "80dp"
---

# Design System: Vesqen

## Overview

**Creative North Star: "The Quiet Signal"**

Vesqen should feel like a listening instrument used in a dim train carriage, a quiet study, or a softly lit room: the screen recedes until a meaningful state changes. The dark experience is the primary expression, while the light experience is a complete daylight counterpart—not a separate brand. The protected full player uses a single Nocturne Graphite material ladder; album artwork may cast one restrained reflection into it, but navigation, text, and evidence labels remain stable and legible.

The information architecture is fixed around three destinations: **Library**, **Now**, and **Chain**. Library is the beginner's starting point, Now is the focused playback surface, and Chain progressively exposes output and evidence. Technical density belongs behind explicit disclosure, not in the track list.

**Key Characteristics:**

- Dark-first and light-complete, with identical hierarchy and behavior.
- One moss signal color used sparingly for active controls, selection, and positive evidence above the neutral playback states.
- Soft tonal separation instead of hard divider lines or nested card grids.
- Album-derived atmosphere only on the full player and only behind protected contrast layers.
- Familiar Android navigation and controls, with advanced evidence one deliberate tap away.

**The Three-Destination Rule.** Top-level navigation is Library, Now, and Chain. Settings and track details are secondary destinations and never become additional permanent tabs.

**The Progressive Proof Rule.** The track list contains title, artist, artwork, playback state, and overflow only. Format, path, source parameters, route evidence, and live metrics belong in track details, Now, or Chain.

## Colors

Signal Moss is an olive-yellow-green derived from the brand mark rather than streaming-service green. Carbon neutrals carry the dark theme; true white and Frost Surface carry the light theme. The full player has its own near-neutral Nocturne Graphite ladder so it reads as one listening instrument rather than a second colored theme.

### Primary

- **Signal Moss:** The immutable brand anchor used in the mark and small identity moments.
- **Signal Moss Bright:** Dark-theme active controls, progress, selected navigation, and high-value focus states.
- **Signal Moss Deep:** Light-theme filled controls and selected states with white foreground content.

### Neutral

- **Carbon Black:** Dark canvas and adaptive-icon background.
- **Carbon Surface:** Quiet structural layer for toolbars and persistent navigation.
- **Carbon Elevated:** Mini-player, bottom sheet, and transient elevated surfaces.
- **Pure White:** Light canvas and inverse identity field.
- **Frost Surface:** Light-theme secondary surface without cream or paper warmth.
- **Ink Dark / Ink Light:** Primary readable text for the corresponding theme.
- **Muted Light / Muted Dark:** Secondary text; both remain readable rather than decorative gray.

### Focused Player Material

- **Now Canvas (`#101415`):** Protected near-neutral midnight field for the full player and its status bar.
- **Now Dock (`#191F20`):** Fully opaque smoked-graphite transport surface and navigation-bar continuation.
- **Now Raised (`#202728`) / Artwork Frame (`#252C2D`):** One-step material lift for the factual route chip, session surface, and framed cover stage.
- **Artwork reflection:** A real cover may enter at 22% behind an 82% Canvas scrim, yielding a capped 3.96% low-frequency reflection. Missing or unreadable artwork remains neutral; Twin Paths never becomes a false full-screen light source.

These values are material roles, never success, selection, proof, or a second action color. Midnight Violet is retired from visible Now surfaces.

### Supporting

- **Warning Amber Bright / Deep:** Fixed warning roles for dark/light themes. Amber communicates attention or a recoverable limitation, never active or verified playback.
- **Error:** Destructive and failure state only. It never appears as decoration or brand expression.

### Evidence States

| Evidence level | Color treatment | Required non-color cue |
| --- | --- | --- |
| `SYSTEM MIXED` | Neutral tonal chip; Muted Dark on Carbon Elevated or Muted Light on Frost | Exact text plus route icon |
| `DIRECT SUPPORTED` | Neutral tonal chip | Exact text plus `DIRECT` label; never a success badge |
| `BIT-PERFECT AVAILABLE` | Moss outline, no filled background | Open-circle availability icon and “AVAILABLE” text |
| `BIT-PERFECT ACTIVE` | Filled Moss control-state chip | Solid activity dot and “ACTIVE” text |
| `BIT-PERFECT VERIFIED` | Moss outline/tonal chip shown with exact matrix context | Shield/certificate icon, “VERIFIED” text, and device-matrix link |
| Recoverable limitation | Warning Amber for the current theme | Warning icon and actionable explanation |
| Failure / destructive | Error role | Error icon, exact failure text, and recovery action |

`SYSTEM MIXED` never uses Moss. Available, active, and verified may share the brand hue only because their fill/outline, icon, wording, and evidence context remain distinct; color alone never promotes one level into another.

**The Signal Budget Rule.** Signal Moss occupies no more than roughly 10% of an ordinary screen. Its rarity creates recognition and makes active state unambiguous.

**The Stable Truth Rule.** Android dynamic color is not enabled in the v1.0 baseline. If it is added later, output states must keep fixed semantic roles: brand, warning, error, and proof meanings never inherit an arbitrary wallpaper hue.

## Typography

**Display Font:** Roboto with Noto Sans SC fallback

**Body Font:** Roboto with Noto Sans SC fallback
**Label/Mono Font:** Roboto Mono for measured technical values only

**Character:** One humanist system family keeps the product immediate and native. Weight, size, spacing, and alignment create hierarchy; decorative display fonts are prohibited. Monospace is reserved for data whose alignment or provenance matters.

### Hierarchy

- **Display** (600, 32sp/38sp): Full-player track title and exceptional empty-state headlines; maximum two lines.
- **Headline** (600, 28sp/34sp): Destination titles such as Library and Playback Chain.
- **Title** (600, 20sp/26sp): Section headings, current track title in compact surfaces, and sheet titles.
- **Body** (400, 16sp/24sp): Explanations, permission copy, and readable metadata; prose stays below 70 characters per line where width permits.
- **Action** (600, 14sp/20sp): Primary and secondary button labels.
- **Label** (600, 12sp/16sp): Navigation labels, chips, and short state text; sentence case is the default.
- **Data** (500, 12sp/18sp): Sample rate, bit depth, buffer values, timestamps, and confidence annotations inside detail surfaces only.

**The One-Family Rule.** UI labels, buttons, track metadata, and navigation use the same sans-serif family. Monospace never leaks into the ordinary library or primary controls.

**The Unbroken Title Rule.** Song titles and artist names may truncate after one line in lists, but the full player and detail sheet provide a readable expanded form without horizontal scrolling.

## Elevation

The system is flat by default and builds depth through tonal layers, artwork-derived light, and small state transitions. Shadows appear only where a surface physically detaches: the persistent mini-player, an expanded player sheet, a menu, or a bottom sheet. A resting track row has no outline and no shadow.

Translucency is purposeful. The full player may use a protected, low-frequency artwork blur behind an opaque contrast scrim; bottom sheets may use platform blur on capable devices. Every translucent surface has an opaque Carbon Elevated or Frost Surface fallback. Blur never reduces text contrast and never becomes a grid of glass cards.

### Shadow Vocabulary

- **Ambient Low:** A compact soft shadow for menus and the mini-player; never paired with a decorative border.
- **Player Lift:** A wider low-opacity shadow reserved for the expanded player and bottom sheets. On Now it is one 20 dp ambient/spot lift between Canvas and Dock, never a divider, border, glow, or second shadow.
- **Focus Halo:** A moss-tinted focus indication around keyboard- or accessibility-focused controls, paired with a shape/state change where color alone is insufficient.

**The No-Line Rule.** Separate list items with rhythm, alignment, and surface tone. Hard dividers are limited to dense technical tables where row tracking genuinely requires them.

**The Opaque Fallback Rule.** If blur, transparency, performance, or contrast cannot be guaranteed, render a fully opaque semantic surface without changing layout or affordance.

## Components

### Buttons

- **Shape:** Confident rounded rectangle (12dp), never a bloated capsule unless the control contains only an icon.
- **Primary:** Deep moss with white content in light mode; bright moss with dark content in dark mode. Minimum height is 48dp.
- **Focus / Pressed:** A visible halo plus a subtle tonal shift; pressed feedback completes within 150ms.
- **Secondary / Ghost:** Tonal surface or transparent background with readable ink. Destructive actions use Error only after clear confirmation.

### Status Chips

- **Style:** Compact pill reserved for short factual state such as `SYSTEM MIXED`; no promotional tags.
- **State:** Apply the Evidence States table exactly. Text and icon/shape jointly communicate status; tapping opens an explanation or the corresponding Chain evidence.

### Track Rows

- **Structure:** 48dp artwork, one-line title, one-line artist, playback indicator, and overflow action inside a 72dp row.
- **Disclosure:** Duration, album, format, sample rate, file location, and telemetry are excluded from the default row.
- **State:** The active row uses a moss title or leading indicator, not a full saturated background.

### Mini-player

- **Structure:** Artwork, title/artist, previous, play/pause, and next. The entire non-button surface opens Now.
- **Material:** One elevated tonal surface with optional low-intensity artwork glow. It never becomes a nested card stack.
- **Motion:** Expands into Now with a 240ms shared-axis transition. Reduced motion uses an 80ms crossfade.

### Full Player

- **Structure:** A framed artwork stage anchors the upper field. One purposeful, opaque lower transport dock contains the one-line title, artist/album when space permits, factual route chip, scrubber, and controls; it is not a stack of floating cards.
- **Control hierarchy:** Previous / play-pause / next form the large centered primary transport group. One Playback Order switch, one explicit Playback Session switch, and the circled information action live in the dock's secondary footer, so transport never competes with modes or metadata. At narrow widths the same three 48dp actions become evenly spaced icons; the session switch uses the GraphicEq glyph but retains an explicit TalkBack action/state. When extreme text hides the route chip, one 48dp AccountTree action becomes the single Chain path. The mini-player never repeats the route chip.
- **Playback-order control:** Shuffle, list repeat, and single-track repeat are **not** separate buttons. One familiar 48dp control cycles `Sequential → Shuffle → Repeat all → Repeat one → Sequential`; this normal cycle always writes mutually exclusive Media3 switches. Sequential uses a numbered-list glyph, Shuffle uses the standard shuffle glyph, Repeat all uses the standard repeat glyph, and Repeat one uses repeat-with-`1`. Sequential is muted; the other three modes use Signal Moss. If an external controller supplies a compound shuffle-plus-repeat state, the same one button renders its two-part state accurately and one tap resets it to Sequential rather than hiding a switch. Each transition changes tint and icon/scale over 160ms and exposes the exact mode to TalkBack.
- **Motion:** Opening Now follows the mini-player's 240ms shared-axis path; returning uses a 180ms inverse path. A user-initiated previous/next change moves only artwork and track identity 220ms in the corresponding horizontal direction while the transport dock remains spatially stable. Reduced motion replaces these movements with the 80ms crossfade fallback.
- **Atmosphere:** Artwork may cast a 3.96% low-frequency reflection behind a stable 82% Nocturne Canvas scrim. The focused surface explicitly supplies fixed light foreground tokens; controls and `SYSTEM MIXED` retain their semantic colors and never sample arbitrary artwork colors. Missing/unreadable artwork has an opaque neutral fallback with no false reflection.
- **Focus mode:** Now is a full-height, edge-to-edge Nocturne Graphite listening surface rather than a squeezed destination panel. Top-level navigation yields its space to the focused player (including the wide-window rail): the status bar continues the Canvas and the navigation bar continues the opaque Dock, with platform contrast scrims disabled. One Player Lift—not a hard line—separates the Dock from the field. Light system glyphs remain legible and all prior system-bar state restores on exit. Toolbar Back and Android Back return to the originating destination, while the factual route chip—or its compact AccountTree fallback under extreme text—remains the deliberate path to Chain.
- **Responsive behavior:** The player is never vertically scrollable. Track and header titles remain one line; an overlong track title may marquee horizontally. Artwork, gaps, and secondary metadata contract as height or font scale decreases, but the scrubber, three primary transport controls, mode controls, Chain path, and information action remain reachable.
- **Progressive session information:** The explicit Playback Session switch replaces only the upper focused stage with live facts the current player exposes (play state, elapsed/remaining time, and queue position). At extreme text, the stable dock remains the sole progress display and the fixed-height session card contracts to state plus queue rather than clipping or scrolling. The header, backdrop, transport dock, and all playback controls remain spatially stable; the stage uses a contained fade/scale transition, never a horizontal pager or a whole-screen swipe. Android Back first restores the artwork stage. The circled information action opens track metadata; it does not invent codec, sample-rate, PCM, or proof telemetry.
- **Details:** Overflow opens an inline sheet for track details; Chain remains the authoritative path for route and proof data.

### Navigation

- **Destinations:** Library, Now, and Chain, each with a familiar icon and persistent text label.
- **Localization:** `Library`, `Now`, and `Chain` are stable semantic IDs, not forced English display strings. English uses those labels; Simplified Chinese uses `曲库`, `正在播放`, and `链路`.
- **Default:** First launch and ordinary cold launch begin in Library. Now without a track explains the single next action: choose a track from Library.
- **Adaptive:** Compact windows use bottom navigation; medium and expanded windows use a navigation rail while preserving the same order and labels.

### Empty, Loading, and Error States

- **Loading:** Use content-shaped skeleton rows; avoid a solitary spinner as the main library experience.
- **Empty:** Explain how to add or rescan local music and present one primary action.
- **Chain without playback:** Explain that chain evidence appears after playback starts and provide one primary action back to Library.
- **Error:** State what failed, what remains safe, and the next action. Do not expose raw exceptions to listeners.

## Do's and Don'ts

### Do

- **Do** keep Library, Now, and Chain stable across themes and window sizes.
- **Do** keep Signal Moss below roughly 10% of ordinary screen area and reserve it for identity, focus, selection, and positive evidence above neutral playback states.
- **Do** maintain at least WCAG 2.2 AA contrast, 48dp touch targets, TalkBack labels, scalable text, and reduced-motion behavior.
- **Do** use soft tonal transitions, restrained artwork light, and platform blur only when they clarify elevation or playback context.
- **Do** disclose technical detail progressively and preserve evidence confidence labels in Chain and detail surfaces.
- **Do** use the symmetric Twin Paths V mark inside the Android adaptive-icon safe zone and provide a dedicated monochrome layer.

### Don't

- **Don't** build a feed-heavy streaming surface with recommendations, social activity, advertising, or engagement loops.
- **Don't** imitate Spotify, NetEase Cloud Music, Apple Music, or another recognizable player.
- **Don't** use neon gaming-audio dashboards, spectrum visualizer spectacle, or dense audiophile telemetry on the default screen.
- **Don't** use generic glassmorphism, decorative blur, oversized rounded-card grids, purple-gradient dark mode, or animation without a state purpose.
- **Don't** use music notes, headphones, play triangles, vinyl records, waveforms, or checkmarks as the product mark.
- **Don't** let Android dynamic color change the Vesqen mark or the semantic meaning of playback, proof, warning, or error states.
- **Don't** pair a decorative border with a wide soft shadow, use side-stripe accents, gradient text, or card radii above 16dp.
