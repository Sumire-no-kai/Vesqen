# Demo library

Fictional albums for store screenshots and demo videos, so no commercial cover or title ever appears. The albums, artists and covers are the website's sample albums (`components/cover-art.tsx` in the website repository); the audio is synthesized pads and melodies with no samples.

```bash
python3 -I tools/demo_library/flacgen.py <output-folder> tools/demo_library
```

It writes three albums (Low Tide Archive, 24-bit/96 kHz; Copper Hours, 16-bit/44.1 kHz; 盐与雾, 24-bit/48 kHz), ten tracks and about 260 MB of FLAC with tags and the cover embedded. The writer uses a fixed order-2 predictor with Rice-coded residuals; Core Audio (`afconvert`) decodes its output bit-exactly. Copy the folder to a device or emulator and add it in Vesqen through the folder picker.
