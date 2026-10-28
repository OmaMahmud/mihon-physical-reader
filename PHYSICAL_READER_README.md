# Physical book reader

Open a chapter. The **Physical book** switch stays above the reader:
- **On** uses the bundled WebGL book renderer with the current chapter's images.
- **Off (Classic)** returns to Mihon's original viewer at the current reading position.
- The choice persists between launches. Physical book is enabled by default.
- Tap the center of the physical page or use **Menu** for Mihon's chapter controls.
- The physical toolbar also provides previous/next chapter controls.

The adapter reuses Mihon's page loader, including extension requests, cached pages and downloaded/local page streams. Page turns update Mihon's progress and history. The bundled renderer supplies page curl, portrait/book layout, wide scans, pinch zoom, pan, and fit controls. Its source asset is retained without replacing it with a different renderer.

The APK version is 0.21.3-physical-book (version code 40), under the existing fork application ID. The debug artifact contains a single universal APK.

## Validation and limits

Android build, format checks, unit tests, and migration checks run in GitHub Actions. The JavaScript adapter is also checked for resume mapping, wide-page positions, progress reporting, seeks, and load failures. Device testing is still required; CI does not verify touch behavior or graphics on a phone.

This first connection loads and decodes the whole chapter before displaying it. Large chapters can take time and use substantial memory. A failed image shows a Reload control instead of silently skipping the page. Classic remains available through the persistent switch. Physical mode follows the bundled renderer's page order; Mihon's RTL and all viewer-specific settings are not yet mapped to it. The bundled HTML identifies its original source as v3.9; a separate v4.0 source has not been supplied in this chat.
