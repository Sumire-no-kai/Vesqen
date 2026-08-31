# Vesqen brand assets

These SVG files are the canonical visual-identity sources for Vesqen v1.0.

- `vesqen-mark-primary.svg`: two-colour mark for light surfaces.
- `vesqen-mark-inverse.svg`: two-colour mark for dark surfaces.
- `vesqen-mark-monochrome.svg`: single-colour mark for tinting, themed icons, and constrained reproduction.
- `vesqen-app-icon.svg`: square reference rendering of the Android adaptive icon.
- `vesqen-lockup-primary.svg`: horizontal mark and typeset name for light surfaces.
- `vesqen-lockup-inverse.svg`: horizontal mark and typeset name for dark surfaces.
- `vesqen-mark-construction.svg`: geometry, safe-zone, and clear-space reference.
- `vesqen-notification-symbol.svg`: optically enlarged monochrome source for Android 24 dp notification rendering.
- `vesqen-visual-system-board.svg`: formal one-page overview of the v1.0 system.
- `vesqen-visual-system-board.png`: reviewed 1600 × 1200 preview rendered from the canonical SVG.

The standalone mark paths are authoritative. Lockup text remains live text in SVG and must use Roboto Medium/SemiBold with the documented system fallback; convert it to outlines only in an exported production format, never in the canonical source.

Do not recolour, skew, rotate, decorate, or extract the mark from a raster screenshot. Android runtime assets are maintained separately under `app/src/main/res/` so launcher masks, monochrome theming, splash behaviour, and notification tinting remain platform-correct.
