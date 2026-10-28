# Native physical reader v4.0

Opening a chapter uses the native physical renderer by default. Center-screen taps show and hide Mihon's existing reader controls. The separate demo, embedded browser, sample pages, renderer switch and custom toolbar have been removed.

## Approved source and rendering

The source of truth is the supplied `virtual_comic_prototype_v40.html`, SHA-256 `DC25869C0890BEF2740559F082D09022DCBBA10494E097BF0B2A36E9D19A84D4`. Its HTML title still says v3.9; the supplied file, rather than that title, identifies the approved v4.0 experience. No embedded comic images from that file are included in the repository or production APK.

`PhysicalPageMesh` ports the prototype's 88 by 56 crease-clipped mesh, reflected reverse face, spine constraints and curl lift. Native OpenGL ES shaders retain its paper material, grain, lighting and layered shadows. Portrait pages, landscape book spreads, wide scans, dragging, turn timing, zoom and panning use the prototype's layout and gesture rules. Right-to-left reading mirrors interaction and book placement while retaining readable page textures. Seven geometry cases compare native output against values generated directly from the supplied JavaScript implementation, including diagonal and reverse folds.

## Mihon integration

`ReadingMode.toViewer` creates `PhysicalBookViewer` inside the existing `ReaderActivity`. The viewer consumes actual `ReaderPage.stream` data from Mihon's current chapter loader, including downloaded/local archives, extension HTTP requests and the chapter cache. Pages are decoded through Mihon's native image decoder and nearby textures are retained in a bounded window. Source dimensions determine spread layout; online dimensions are learned as pages arrive. Full-resolution pages are retained up to the device texture limit, capped at 4096 pixels per edge; larger scans are sampled to fit.

Existing center-tap menus, chapter loading, page actions, progress/history persistence, volume navigation, brightness, orientation, grayscale and inversion remain connected to Mihon's activity and settings. Pager and webtoon-specific layouts and effects do not apply to the physical renderer. Decode jobs cancel on backgrounding or chapter changes; GL commands run only inside a current rendering context. Errors use Mihon's page error UI and retry handling.

## Installation identity

The launcher label is **Mihon Physical** and the debug package is `app.mihon.physicalreader.dev`. Earlier test APKs used `app.mihon.physicaldemo.dev` and were signed with different temporary keys on each Actions runner. They cannot reliably update one another. This build installs alongside them without removing their library. Use Mihon's existing backup/restore to transfer library and history; choose the existing storage folder for downloaded chapters and install required source extensions in the new app. Keep the original app until the transferred library is confirmed.

The workflow encrypts the generated signing keystore to `.github/native-recovery-public.pem`; only the corresponding private key, retained outside this public repository, can recover it. Private signing keys must never enter this repository or a public Actions cache. Configure repository secret `MIHON_NATIVE_DEBUG_KEYSTORE_BASE64` with the recovered Android debug keystore before distributing subsequent builds as updates. The default debug keystore passwords remain Android's standard `android`. Recovery artifacts contain encrypted bytes only.

## Verification

The `Native Physical v4.0 Verification` workflow builds the universal APK, runs unit tests and drives the actual `ReaderActivity` on an Android 35 emulator. Instrumentation creates CBZ chapters containing three original Pepper&Carrot pages plus synthetic size/spread checks through the real local loader. The comic pages are fetched into androidTest assets during CI and never enter the production APK. Test artwork: Pepper&Carrot, Episode 1, David Revoy, proofreading Amireeti and Alex Gryson, [original source](https://www.peppercarrot.com/en/webcomic/ep01_Potion-of-Flight.html), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Screenshots render the original artwork; the reader may show partial pages during a curl. It exercises native controls, held curl, forward/reverse turns, rapid turns, landscape spreads, reading direction, chapter changes, progress/history, early pause/resume and backgrounding. A separate actual `HttpPageLoader` test exercises HTTP failure, retry and cached reading after disconnection. Screenshots and logs accompany the run; a missing held-curl screenshot fails verification.

Compilation alone is not visual verification. Check the successful run's test report and screenshots before distributing its APK. Emulator fixture tests do not establish physical-tablet performance, arbitrary extension compatibility, or prolonged reading of the user's own library. Those remain device-level verification limits.

## Build outputs

The native verification workflow uploads `mihon-native-v40-apk` (one universal debug APK) and `native-v40-verification` (tests, logs and screenshots). The normal upstream build also checks formatting, unit tests and SQLDelight migrations. Gradle dependency/build caching is enabled; signing keys are excluded from caches.
