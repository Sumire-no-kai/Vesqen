---
name: Vesqen
description: Paper & Sound, a quiet visual system for local listening and auditable playback.
colors:
  paper: "#F3EFE6"
  paper-raised: "#FBF9F4"
  paper-nav: "#F6F3EC"
  ink: "#1A1A16"
  ink-muted: "#5E5A50"
  hairline-light: "ink at 13%"
  radio-idle-light: "#7C776B"
  moss-deep: "#536B1E"
  on-moss: "#FFFFFF"
  amber-deep: "#7A4F00"
  error-light: "#BA1A1A"
  night: "#151411"
  night-raised: "#1E1C18"
  night-nav: "#191814"
  night-text: "#EDE8DC"
  night-muted: "#A8A294"
  hairline-dark: "night-text at 13%"
  radio-idle-dark: "#8A8578"
  moss-bright: "#BFD66B"
  amber-bright: "#F2C36B"
  error-dark: "#FFB4AB"
  signal-moss: "#9FBF4B"
typography:
  title:
    fontFamily: "Instrument Serif, system serif (Songti where present)"
    fontWeight: 400
    sizes: "34/38, 32/38, 28/31 sp; names in lists and sheets 22/28, 21/25, 17/22 sp"
    use: "page titles and names of music only"
  heading:
    fontFamily: "Instrument Sans, system sans for Chinese"
    fontWeight: 500
    sizes: "26/30, 22/28, 21/25, 19/24, 17/22 sp"
  body:
    fontFamily: "Instrument Sans, system sans for Chinese"
    sizes: "16/24, 15/23, 13/19 sp"
  label:
    fontFamily: "Instrument Sans"
    fontWeight: 600
    sizes: "15/20, 14/20, 12/16 sp"
  data:
    fontFamily: "Instrument Sans"
    fontWeight: 500
    fontSize: "13sp"
    lineHeight: "18sp"
    fontFeature: "tnum"
  code:
    fontFamily: "monospace"
    use: "JSON previews and literal identifiers only"
rounded:
  album: "4dp"
  control: "12dp"
  surface: "16dp"
  mini-player: "14dp"
  pill: "999dp"
spacing:
  xxs: "4dp"
  xs: "8dp"
  sm: "12dp"
  md: "16dp"
  lg: "24dp"
  xl: "32dp"
components:
  paper-card:
    backgroundColor: "{colors.paper-raised} / {colors.night-raised}"
    border: "1dp hairline"
    rounded: "{rounded.surface}"
  list-row:
    minHeight: "56dp, 64dp with a description"
    separator: "1dp hairline, inset 16dp"
  mini-player:
    height: "72dp"
    rounded: "{rounded.mini-player}"
    elevation: "6dp warm shadow, 1dp hairline"
  compact-navigation:
    height: "60dp plus the system inset"
    backgroundColor: "{colors.paper-nav} / {colors.night-nav}"
    selection: "ink label at 600 with a 16 x 2dp Moss mark, no pill"
  touch-target:
    minimum: "48dp"
---

# Design System: Vesqen

This file is the visual and interaction baseline. The board values and their reasoning live in [docs/redesign/B_PAPER_AND_SOUND.md](docs/redesign/B_PAPER_AND_SOUND.md); where the two differ, the code and this file describe what ships. The 2026-10-07 review behind this version is recorded in [docs/redesign/REVIEW_2026-10.md](docs/redesign/REVIEW_2026-10.md).

## Overview

**North star: liner notes.** Vesqen reads like the sleeve of a record you own: warm paper, a serif title, small factual notes, and nothing that sells. Playback comes first; the evidence of how the sound travels sits one deliberate tap away and is written as plainly as a credit on a sleeve.

The four destinations are **Library**, **Now**, **Chain** and **Settings**. Library is where people start. Now is the focused player. Chain is the evidence page. Settings holds output modes, privacy and app information. About, track details, the queue, the privacy policy and licenses are secondary pages or sheets, never tabs.

**Key characteristics**

- Light and dark are complete counterparts with the same hierarchy; the system theme decides.
- One Moss accent for actions, selection and positive evidence, used sparingly.
- Paper cards with a hairline border group rows; rows are ruled by hairlines, not separated by gaps or fills.
- Now's background follows the album cover; nothing else does.
- Technical detail is progressive: the track list shows title, artist and cover; formats live in track details; measurements live in Chain.

**The progressive proof rule.** The track list contains title, artist, artwork, playback state and the overflow action only.

## Colors

All text roles meet WCAG AA on every surface they use. Measured 2026-10-07 with the WCAG formula; `PaperAndSoundColorTest` guards the token pairs.

| Text role | Light: paper / raised / nav | Dark: night / raised / nav |
| --- | --- | --- |
| Body (`ink`, `night-text`) | 15.2 / 16.6 / 15.8 | 15.1 / 13.9 / 14.5 |
| Secondary (`ink-muted`, `night-muted`) | 6.0 / 6.5 / 6.2 | 7.3 / 6.7 / 7.0 |
| Moss text and icons (`moss-deep`, `moss-bright`) | 5.2 / 5.7 / 5.4 | 11.4 / 10.6 / 11.0 |
| Warning (`amber-deep`, `amber-bright`) | 6.2 / 6.8 / 6.4 | 11.2 / 10.4 / 10.8 |
| Error | 5.6 / 6.1 / 5.8 | 10.9 / 10.0 / 10.5 |
| Idle radio and checkbox outline (non-text, 3:1 needed) | 3.9 / 4.2 / 4.0 | 5.0 / 4.6 / 4.8 |

Labels on filled Moss buttons: 6.0:1 (white on `moss-deep`), 11.4:1 (`night` on `moss-bright`).

- **Moss** (`moss-deep` light, `moss-bright` dark) marks primary actions, the selected tab, the playing row, progress and the ACTIVE and VERIFIED evidence states. `signal-moss` is the mark's own colour and appears only in the logo.
- **Amber** is a recoverable warning, such as a recent-events warning. It never raises an evidence claim.
- **Error** is a failure or destructive action only.
- **Album tint.** Now's background, and an open album in Library, take the cover's dominant hue at fixed lightness and capped chroma (B §2.3), so `ink-muted` keeps at least 5.26:1 at every hue. Text, controls, chips, hairlines, navigation and the mini-player never follow the cover. No cover means plain `paper` or `night`.
- **No dynamic colour.** Android wallpaper colours never change the mark or the meaning of evidence, warning or error states.

### Evidence states

| State | Label (all languages) | Treatment |
| --- | --- | --- |
| System output | `SYSTEM MIXED` | Neutral chip, never Moss |
| Strict output possible | `BIT-PERFECT AVAILABLE` | Moss outline, open-circle icon |
| Strict output starting | `BIT-PERFECT REQUESTED` | Moss outline |
| Strict output on | `BIT-PERFECT ACTIVE` | Filled Moss, solid dot |
| Verified by a signed record | `BIT-PERFECT VERIFIED` | Moss outline with shield; the record ID and method are always shown |
| Strict output stopped | `STRICT OUTPUT STOPPED` | Error tone; the localized title says what happened and the body says why |

**The fixed-code rule.** Evidence chips show the same English code in every language, so the app, the website and bug reports use one vocabulary. The title and explanation beside a chip are localized and never repeat the chip.

**The no-promotion rule.** A lossless file is not bit-perfect, an active path is not verified, and Bluetooth is never bit-perfect. Colour alone never moves one state to another.

## Typography

- **Page titles and names** use Instrument Serif at weight 400, with the system serif as fallback so Chinese uses Songti where the phone has it (Android 10 and later; Android 8–9 fall back to the system sans). Serif is only for page titles ("Library", "Chain", "Settings" and the pages opened from them) and for names of music: track titles in Now, lists, the mini player, track details and the Chain summary, and album, artist and playlist names. Code marks each of these with `serif()`.
- **Headings that are not names** use Instrument Sans at weight 500: Settings group titles, sheet and dialog titles, Chain section and status titles, empty-state titles. Owner decision, 2026-10-10: a card never pairs a Songti heading with sans rows.
- **Body, labels and controls** use Instrument Sans; Chinese uses the system sans.
- **Data** (times, counts, rates, measured values) uses Instrument Sans with tabular figures, so changing digits keep their width. Monospace is only for literal code: the JSON report preview and cookie names in the policy. Monospace never sets a value with a unit; it spread "96  kHz" apart.
- **Eyebrows** (small labels such as "Now" in the Now top bar) use 12 sp at weight 500–600 with wide tracking; Chinese keeps its case and spacing.

**The one-line rule.** Track titles truncate after one line in lists; Now and track details show the full title.

## Elevation and surfaces

Flat paper by default. Grouping comes from one paper-raised card with a 1 dp hairline border; rows inside are separated by inset hairlines. Shadows appear only on surfaces that physically float: the mini-player (6 dp, warm), menus, dialogs and bottom sheets.

**The no-near-white-block rule.** Never place near-white fills inside a paper card or on paper to separate rows; use hairlines. A row's state shows in its label colour, not in a filled block.

## Components

### Buttons

- Primary: filled Moss, 48 dp minimum height, fully rounded. One primary action per screen.
- Secondary: outlined or text buttons in Moss or ink. Destructive actions use Error and confirm first.

### Lists and rows

- Track rows: 48 dp cover, one-line serif title, one-line "artist · album", play indicator on the current track, and a 48 dp overflow button. Rows are ruled by a bottom hairline.
- Settings rows: 56 dp, or 64 dp with a description. A value (version, On/Off) sits left of the chevron; above 130 % text it moves under the title so the title never squeezes into single letters.
- Album detail rows: track number, title, duration and a 48 dp overflow button in a 48 dp row.

### Library

- Text tabs (Songs, Albums, Artists, Folders, Genres, Playlists) scroll horizontally; the selected tab is ink at weight 600 with a Moss underline.
- Header actions: search, sort, Favorites (heart) and a menu for adding folders and rescanning.
- **A–Z index:** a 24 dp strip along the right edge, 11 dp letters, missing letters at 25 %, the selected letter in Moss. Dragging shows a 56 dp letter bubble beside the finger that takes no layout space. Windows under 420 dp tall, text above 120 % and TalkBack get an "A–Z" button that opens a letter picker with 48 dp rows instead. Shown only in unfiltered Songs sorted by title.
- Favorites and playlists show an Edit order action only when there is something to reorder (two or more songs). Reordering uses a 48 dp drag handle and Move up / Move down accessibility actions.
- Until the catalog has been read once, the library shows loading rows, never the empty state.

### Mini-player

- A 72 dp paper-raised card floating 4 dp above the navigation: cover, title, artist, previous, play/pause, next. The non-button area opens Now.
- Above 150 % text only the title shows; the artist is still read out by TalkBack.

### Now

- **Focus mode.** With a track, Now takes the whole window: the bottom bar and the rail yield, and the top-left collapse button or Android Back returns to where Now was opened. Without a track, Now shows its empty state with the navigation.
- Top bar: collapse, "Now" with the queue position, the orientation toggle when the phone can rotate, and the queue.
- Cover as wide as the content, up to 360 dp, shrinking to 164 dp while the liner notes are open; under 64 dp it is dropped rather than squeezed. Then the serif title, "artist — album", progress, transport (playback order, previous, play/pause, next, favorite) and the liner notes. Height the cover cannot use is shared above and below the cover, so the transport and the liner notes sit at the bottom on tall phones.
- **Liner notes:** collapsed to one summary line ("FLAC 24/96 · SYSTEM MIXED"); open, they list 01 Source file, 02 AudioTrack and 03 Route and stability with each value and its confidence, then the evidence chip, its explanation, Output mode and Track information. The step numbers are the liner-notes metaphor of the B design, not section numbering.
- Landscape is a separate composition: cover or liner notes on the left, identity and controls on the right, system bars hidden.
- The page never scrolls; only the open liner notes scroll inside their own region.

### Chain

- Summary: observation notice (live, last playback, stale, partial), now playing, the evidence card (chip, localized title, explanation), the five-step path (source file, decoder, app processing, AudioTrack, route) split into "Vesqen's part" and "the system's part", pinned metrics, and the link to the advanced dashboard.
- Rows show the value and its confidence only. Freshness is the page notice's job; no row shows a ticking age.
- Advanced dashboard: compact controls (view, refresh rate, customize), metric cards by section, recent events. A card's expanded details show source, method, window and "Updated: Just now / N minutes ago". Recent events are hairline rows in one card; severity is the label colour.
- Ages never count seconds: under a minute is "Just now", then minutes, hours, days.

### Settings

Groups in this order: Playback output (System / Strict USB, radio group), Audio proof (Playback chain), Privacy & data (Usage statistics, Export device report), Updates (GitHub builds), Application (About, Privacy policy, Open source licenses), Advanced (Output verification records), then one footer line. Group titles are sans; each group is one paper card. When Strict USB is selected, a status box under the options says what happens next or why it stopped, never the option description again.

### About

Header, mark, one plain sentence about the app, a facts card (version, developer, license), and "Your data": what stays on the phone, no account, no backup, no move to a new phone, plus the privacy policy row.

### Sheets and dialogs

- Queue and track details are bottom sheets that wrap their content. Queue rows play on tap and mark the current track with a Moss dot.
- Dialogs carry one primary action. The strict-output failure dialog appears only after a user action, never on a cold start.

### Empty, loading and error states

- Loading uses content-shaped rows, not a lone spinner.
- Empty states name the situation and one next step. Without music access the library says nothing has been read yet ("No music yet"); with access and no files it says none were found. Never ask for a permission that is already granted.
- Errors say what failed, what is safe, and what to do; no raw exception text. Titles have no full stop.

## Navigation

- Compact windows: a 60 dp bar plus the system inset with Library, Now, Chain and Settings. Labels stay on one line and scale with system text up to 130 %, then stop growing so they are never cut off; every item keeps a 48 dp target.
- Windows 600 dp and wider use a rail in the same order.
- Now with a track yields both the bar and the rail. Chain is a tab; opened in context from Settings it shows a back arrow.
- Cold start opens Library.

## Motion

- Opening Now: 240 ms shared-axis rise from the mini-player; closing: 180 ms inverse, with the shell revealed in the last 108 ms.
- Secondary pages (Chain from Settings, About): 240 ms horizontal path; Back reverses it.
- State changes 180 ms, playback-order mode 160 ms, presses 120 ms. Album tint transitions 800 ms.
- Reduced motion replaces all movement with an 80 ms crossfade and drops the play/pause cover scale.

## Copy

Vesqen's voice is plain, concrete and calm. It tells people what happened and what they can do, in the words they would use.

- **No slogans or aphorisms.** Describe what the app does ("plays the music stored on this phone"), not what it stands for.
- **No reflexive contrast frames** ("not X but Y", "不是……而是……"), no chains of negations, no hype words, and no em dashes in sentences.
- **One idea per sentence; no repeated sentence patterns** across a screen.
- **Jargon stays in the evidence layers.** Ordinary screens say "bit-perfect output", "the DAC", "the phone"; terms like mixer profile, PCM, Media3 or AudioTrack appear only where they label measured evidence (liner notes, Chain).
- **Name the real cause.** When strict output stops because the phone offers no bit-perfect path, say so; do not blame the DAC.
- **Titles have no full stop**; Chinese titles and buttons have no trailing punctuation. Chinese uses full-width punctuation and a space between Chinese and Latin text or numbers ("96 kHz", "Android 14 及以上").
- **Time:** live values show no age. Where an age matters it is "Just now" or whole minutes and up.
- **Chinese is written, not translated.** English is first-class too; each language reads naturally on its own.
- **Never claim more than the evidence.** Follow the evidence-state rules above.

### Terms

| English | 中文 |
| --- | --- |
| Library | 曲库 |
| Now | 正在播放 |
| Chain | 链路 |
| Liner notes | 链路注记 |
| Settings | 设置 |
| Favorites | 我的喜欢 |
| Playlists | 播放列表 |
| Queue | 队列 |
| System output | 系统输出 |
| Strict USB output | 严格 USB 输出 |
| Usage statistics | 使用统计 |
| Device report | 设备报告 |
| Open source licenses | 开源许可 |

## Accessibility

- **Touch targets:** 48 dp minimum for every control, including overflow buttons in dense rows and each A–Z picker row.
- **Labels:** every control has a name TalkBack reads. A checkbox and its label are one toggleable control; icon buttons have content descriptions; state uses state descriptions ("Now playing", "Expanded") rather than colour.
- **Grouping:** a row reads as one unit (title, subtitle, value); custom actions cover reordering (Move up, Move down) instead of drag-only gestures.
- **Text scaling:** layouts hold at 100, 130, 150 and 200 % and at 320 dp width. Rules above say what changes: settings values move under titles above 130 %, navigation labels stop at 130 %, the mini-player drops the artist above 150 %, the A–Z strip becomes a picker above 120 %.
- **Contrast:** see the colour table; disabled controls may drop below 4.5:1, nothing else may.
- **Motion:** honour the system animation setting (reduced-motion fallbacks above).

## Do and don't

**Do**

- Keep the four tabs stable across themes and window sizes, and let Now take the window while it plays.
- Keep Moss scarce: actions, selection, the playing item and positive evidence.
- Rule rows with hairlines inside one paper card.
- Show evidence with its confidence and keep provenance one tap away.
- Use the Twin Paths mark inside the adaptive-icon safe zone, with its monochrome layer.

**Don't**

- Don't add feeds, recommendations, social features or ads.
- Don't imitate a recognisable streaming app.
- Don't use near-white blocks to separate rows, icons in tinted rounded squares, equal-size card grids, gradient text, side-stripe accents or card radii above 16 dp.
- Don't show live counters that tick every second.
- Don't use music notes, headphones, play triangles, vinyl, waveforms or checkmarks as the mark.
