# Third-party notices

The liquidOS launcher app (`launcher/`, built as `liquidOS.apk`) bundles the libraries and assets below. Each
keeps its own license; this file and the license texts it points to travel with every copy of the app.

The project itself is MIT licensed, see [LICENSE](LICENSE).

## Libraries (Apache License 2.0)

Full text: [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt), also at
https://www.apache.org/licenses/LICENSE-2.0

| Library | Version | Copyright |
|---|---|---|
| ZXing core (`com.google.zxing:core`), QR codes for "Upload from phone" and the support QR | 3.5.3 | Copyright ZXing authors |
| AndroidX RecyclerView (`androidx.recyclerview:recyclerview`) | 1.3.2 | Copyright The Android Open Source Project |
| AndroidX DynamicAnimation (`androidx.dynamicanimation:dynamicanimation`) | 1.0.0 | Copyright The Android Open Source Project |
| AndroidX libraries they depend on: annotation, annotation-experimental, arch core, collection, concurrent-futures, core, core-ktx, customview, customview-poolingcontainer, documentfile, legacy-support-core-utils, lifecycle (common, runtime, livedata, livedata-core, viewmodel), loader, localbroadcastmanager, print, versionedparcelable | as resolved by Gradle | Copyright The Android Open Source Project |
| Guava ListenableFuture (`com.google.guava:listenablefuture`) | 1.0 | Copyright The Guava Authors |
| Kotlin standard library (`org.jetbrains.kotlin:kotlin-stdlib`, `kotlin-stdlib-common`) | 1.6.21 | Copyright JetBrains s.r.o. and Kotlin Programming Language contributors |
| JetBrains annotations (`org.jetbrains:annotations`) | 13.0 | Copyright JetBrains s.r.o. |

Release builds shrink these with R8, so the app carries only the parts it uses.

## Font (SIL Open Font License 1.1)

| Asset | Copyright |
|---|---|
| Outfit typeface (`launcher/app/src/main/res/font/outfit.ttf`) | Copyright The Outfit Project Authors |

Full text: [launcher/FONT-LICENSE-OFL.txt](launcher/FONT-LICENSE-OFL.txt).

## Pictures

- **Rotating wallpapers** are downloaded at run time from Wikimedia Commons; each one shows its author and
  license on screen while it is the wallpaper. None is stored in this repository.
- **NASA wallpapers** (Hubble and Webb images) are credited to NASA, ESA, CSA and STScI in the wallpaper menu.
  Webb images by ESA/Webb are CC BY 4.0. They are not stored in this repository.
- **Screenshots** in `docs/liquidos/` show some of those wallpapers; their author and license are visible in
  each screenshot.

## Trademarks

liquidOS is an independent project. It is not affiliated with, endorsed by or sponsored by Apple, Google,
Samsung, Walmart (onn.), X Corp. or any app maker whose icon appears in the screenshots. "Liquid Glass", "Apple
TV", "Google TV", "Android TV", "onn.", "Samsung", "X" and the app names and logos shown belong to their
owners and are used only to describe compatibility or to link to an account.
