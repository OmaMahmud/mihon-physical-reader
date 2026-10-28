# Mihon Physical Reader — first APK test

This is a complete Mihon 0.21.3 source snapshot with a **demo** physical-book activity and a GitHub Actions debug APK build workflow. The demo contains 12 synthetic placeholder pages, not copyrighted comic content.

**Not yet connected to Mihon chapter images, page progress, or reading preferences.** The demo is launched from a `Physical book demo` button in the reader UI. This is an engineering test, not a finished reader. No APK has yet been built or tested.

## Upload to your private GitHub repository

GitHub's website **does not automatically extract ZIP uploads**. On a PC, extract this ZIP, then use GitHub Desktop or Git to commit/push its *contents* (not the ZIP) to your repository. Because the repository already has a README commit, GitHub Desktop may require cloning the repository first and copying the extracted files into the clone. No application code needs to be written.

## Get the APK

After the files are committed on `main`, open your repository > Actions > Build Physical Reader Test APK > latest successful run > Artifacts > `mihon-physical-reader-test-apk`. Extract the downloaded artifact ZIP to find the APK.

The debug APK uses a different application ID (`app.mihon.physicaldemo`) so it can install alongside standard Mihon. GitHub Actions build success is not guaranteed until dependencies are fetched and compilation is checked.
