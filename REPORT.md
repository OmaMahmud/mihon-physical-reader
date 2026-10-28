# Real Reader 0.1.0-alpha01 — implementation and test report

28 October 2026 · Android debug alpha · application ID `com.realreader.app` · Android 8.0+ (API 26).

## Deliverable and independence

This is a new Kotlin/Jetpack Compose Android application, not a relabeled Mihon APK. Its build tree contains only Real Reader and standard Android/Jetpack dependencies. It has a distinct application ID and does not import Mihon libraries, sources, extensions, resources or application modules. The approved HTML prototype supplies the crease geometry equations; its embedded comic assets are excluded. No WebView or fictional sample library ships in the app.

The self-contained codebase is on `real-reader-independent` in the accessible `OmaMahmud/mihon-physical-reader` repository. The main branch/reference implementation was not altered. A distinct repository remains outstanding: the connector has no create-repository operation and GitHub's web interface required sign-in. The branch has inherited reference history, but its current tree and Android build are independent. See REFERENCES.md for exact source hashes and provenance.

## Implemented

| Area | Behavior |
| --- | --- |
| Native UI | Material 3 Scaffold, NavigationBar, app bars, cards, dialogs, switches, chips, slider and reader settings sheet; native back handling and explicit Home from secondary screens and reader |
| Midnight Glass | Charcoal/navy background, restrained blue/violet accents, rounded translucent tonal cards, subtle highlights and consistent reader/library controls; bundled Google Material Symbols Rounded |
| Touch sizing | Icon buttons at least 48×48 dp; primary reading/import buttons full width and at least 56 dp tall; 12–24 dp layout spacing |
| Home | First row lists up to three recently opened unfinished comics, actual covers, page progress and large Continue Reading buttons; Recently Added section |
| Library | Filename/series search, numeric issue ordering, read-state filters, Recently Added tab, cross-folder virtual grouping, editable series name, series issue list |
| Import | Android SAF individual files and multiple selected folders; recursively scans subfolders; CBZ/ZIP, PDF, JPEG, PNG and WebP; explicit PDF format opt-in |
| Source preservation | Original files are not moved, renamed or deleted; source URI retained; remove action deletes only the local index entry |
| Scanning | Manual scan, off-main-thread traversal, persisted folder selection, periodic WorkManager scan requested every six hours subject to Android restrictions; exact-URI duplicates suppressed |
| Reading | Local archive/image decode and Android PdfRenderer; whole artwork fitted without cropping; auto portrait/single and landscape/double layout, single covers, wide-image detection |
| Physical turn | Native OpenGL ES 2; 88×56 crease-clipped reflected mesh, spine-safe finger target, curved lip/lift, shaded reverse surface, finger-following drag, completion/cancellation; outside leaf folds around spine in double mode |
| Controls | Edge taps, horizontal drags, center controls toggle, previous/next, seek-on-release slider, bookmarks, reset zoom, direction, layout, brightness and orientation |
| Zoom | Pinch and double-tap zoom 1–4×; pan limits follow fitted art; zoomed horizontal swipes pan first and turn only when started at the corresponding horizontal boundary |
| Persistence | Private SQLite index stores URI, series, issue, page, page count, opened/added times, bookmarks and finished state; completion includes a final two-page spread; resume on reopen |
| Issue flow | Next issue control at the final reading position when another filename-parsed issue exists in the same series |
| Privacy | No account, ads, subscription, comic upload, analytics SDK or app Internet permission; no billing gates around page turning |

## Important limits and outstanding work

1. **Exact v4.0 visual parity is not certified.** The original source checksum matches the approved file and the core crease equations are native. This alpha's paper material, reverse-face treatment, lighting/shadows, cover transitions and wide-spread transitions are not yet a frame-by-frame reproduction of the approved reference. No 60 FPS or physical-device performance claim is made.
2. **Page quality has an alpha ceiling.** Image decode targets a 2,048-pixel maximum side, PDFs render to that ceiling, and paired output uses a 1,600-pixel height. Source artwork is not cropped, but this is not full source-resolution zoom. Tiled/full-resolution rendering and GPU-limit adaptation remain outstanding.
3. **CBR/RAR is unsupported.** CBZ/ZIP, PDF and images are implemented. Individual images are individual library entries; image-folder-to-issue grouping is outstanding.
4. Filename parsing is basic. Embedded ComicInfo metadata, publisher/year/genre/length filters, decimal/annual/volume edge cases, metadata enrichment, duplicate-content detection, uncertain-match review and missing-issue health tools are outstanding. Exact URI de-duplication is not content de-duplication.
5. No remote server, OPDS/Komga/Kavita connector, source extension compatibility, download queue, cloud sync, panel detection, guided panel mode or volume-key navigation is implemented. Unimplemented integrations are described honestly in Settings rather than exposed as fake working controls.
6. Glass uses translucent tonal surfaces and highlights; it does not reproduce live frosted-background blur.
7. Archive/PDF files are copied temporarily into app cache to provide seekable local input; input is limited to 1 GB and archive image entries to 128 MB. No extraction using archive paths occurs. Large-library performance, memory-pressure recovery and full error/retry coverage need more work.
8. SAF persisted grants are requested. Providers can deny or revoke grants; the reader then reports an error and the user must reselect the source. Periodic scans depend on Android scheduling and do not constitute instantaneous file watching. Deleted/moved source entries are not yet automatically reconciled.
9. SQLite is used directly, rather than Room. Progress/settings have no export/restore yet; clearing app data or uninstalling removes the index and progress. Comics themselves remain in their original locations.
10. The debug APK is for private testing. Production signing, stable update signing, Play listing, release shrinking, policy/name checks and launch pricing setup are outstanding. A subsequent CI runner's temporary debug key may require uninstall/reinstall, which loses progress; do not treat debug signing as a production update channel.

## Validation

**Verified CI:** [Real Reader Android run 40768009986](https://github.com/OmaMahmud/mihon-physical-reader/actions/runs/40768009986), code commit `fa4da96fb69c7fdc76197dd9ba95d20f0d72f61b`. The final report update does not change app source.

| Check | Result |
| --- | --- |
| Debug app and instrumentation APK assembly | Passed |
| Unit tests | 4 passed; 0 failures, 0 skipped |
| Android lint | 0 errors, 24 warnings |
| Android 35 Pixel 2 emulator tests | 6 passed; 0 failures/errors/skips |
| Visual evidence | Reader, held curl, Home/Continue Reading and settled Library captures retained and reviewed |
| APK package inspection | Correct app ID/API levels; 37,328-byte Material Symbols font; no test comic assets/test provider; no Mihon namespace in DEX |
| Internet access | No `INTERNET` permission; WorkManager contributes network-state/background-work permissions |
| Artifact integrity | Downloaded ZIP SHA-256 matched GitHub's recorded artifact digest; APK signing block present; same CI build installed for emulator testing |

The device tests exercise native navigation/Home/back, actual CBZ pages, a held/cancelled curl, forward turning, persisted bookmarks and resume, recursive SAF indexing with a scoped tree grant and duplicate suppression, native PDF rendering, and controlled corrupt-archive failure. Geometry/spine invariants, zoom-edge rules, natural page order and cover/wide-page pagination are unit tested. The real-comic fixture is Pepper&Carrot Episode 1 (David Revoy, CC BY 4.0), included only in instrumentation assets.

A reader initialization race was fixed: rendering/progress updates now wait until the page-layout index is ready, preventing a resumed comic from being temporarily marked finished. Tests wait for the loaded reader UI. The SAF fixture now honors query projections and grants access from its owner process. System bars use light icons against the dark theme. The enlarged Continue Reading button was visually confirmed.

Lint warnings remain: outdated target/dependency versions, window-size API recommendations, backup-rule configuration and Kotlin API style suggestions. They are not suppressed. Emulator animations are disabled; passing functional tests does not certify real-time animation quality, 60 FPS, physical-device performance, every storage provider or exact v4.0 parity.

**Delivered APK:** `Real-Reader-0.1.0-debug.apk` — 10,261,942 bytes, version `0.1.0-alpha01`, application ID `com.realreader.app`.

- APK SHA-256: `0acadcc6b1fa54bd151784443a927f6cb2aa0ee791f9493af209a0dcca946123`
- GitHub artifact ZIP SHA-256: `168e94272ae17e3a2f3f805b8831aa7eefb3506679522e9384e494c6cb3744a4`
- Minimum API: 26 (Android 8.0); target/compile API: 35 (Android 15).

The evidence ZIP contains the original CI test reports, screenshots, package-inspection results and attribution. Test artwork is excluded from the installable app APK.

## Device acceptance checklist

Install the APK, open Settings and choose supported formats (PDF off until explicitly enabled). Add a small folder or use + for individual files. Check your own CBZ/PDF, a wide scan, a nested folder and a renamed series. Open a comic, turn forward/back, cancel a short drag, show/hide controls, bookmark, seek, return Home and Continue Reading. Pinch to zoom, pan to each horizontal limit, and swipe again to turn. Rotate between single/double, test RTL and brightness/orientation, background/reopen and verify position. Confirm cover/spread behavior against the exact approved v4.0 prototype. Phone/tablet, SD-card providers, revoked grants, sustained memory pressure and prolonged reading remain device acceptance work.

## Installing over an earlier test build

The APK can install alongside Mihon because its package is separate. If an earlier Real Reader debug APK is already installed, a different temporary signing key may require uninstalling it first. That removes its local progress/index; there is no progress export yet. Keep original comic files, which the app does not delete.
