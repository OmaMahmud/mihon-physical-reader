# Virtual book reader — development work in progress

## Implemented in this branch
- `BookSpreadPlanner.kt`: initial layout model for two-facing-page landscape reading.
- Recognizes pre-combined wide source images, placing each across both facing slots.
- Supports left-to-right and right-to-left ordering and a standalone front cover.
- Does not split or crop wide source images.

## Not yet implemented
- Connecting the planner to Mihon's image loader and rendering pipeline.
- Orientation-based viewer selection and portrait panning/border behavior.
- 3D curl mesh, lighting, gesture tracking, and tap-to-flip UI.
- Physical cover opening, spine, stacked pages, and closing animation.
- Android build, integration tests, and installable APK.

## Important integration notes
`PagerViewer` currently uses a `ViewPager` with one `PagerPageHolder` per adapter item.
The virtual-book viewer needs its own facing-page surface, while reusing the chapter
loader and reader progress tracking. Do not simply group adjacent adapter items:
chapter transitions, split pages, and user reading direction need careful handling.
`BookSpreadPlanner` is a first implementation component, not a finished feature.
