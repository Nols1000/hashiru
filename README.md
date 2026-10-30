This is a Kotlin Multiplatform project targeting Android, iOS, Web, Server.

* [/app/iosApp](./app/iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/app/iosApp/watchApp](./app/iosApp/watchApp) is the watchOS companion, embedded in the iOS app. It runs a
  workout session for heart rate during a walk and controls the treadmill through the phone. It has its own
  copy of the phone–watch wire format (`WatchCodec.swift`), which mirrors `WatchMessageCodec` in sharedLogic.

* [/app/wearApp](./app/wearApp/src/main) is the Wear OS companion. It shares the phone app's package name and
  signing key, which the Wear OS Data Layer requires, and uses sharedLogic for the wire format.

* [/app/webApp](./app/webApp/src) is the web viewer for live shares, a React application. It uses the Kotlin/JS
  library produced by the sharedLogic module, which follows the share and decrypts it; the React code only renders.
  It is built for a static host that passes `/api` on to the relay ([/server](./server/src/main/kotlin)) on the same
  origin, as the cluster's gateway does (see [Deployment](#deployment)). Set `VITE_PMTILES_URL` to a PMTiles extract for a basemap; without it, routes show on a plain background.

* [/app/sharedLogic](./app/sharedLogic/src) is for the code that will be shared between app targets in the project.
  The most important subfolder is [commonMain](./app/sharedLogic/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/app/sharedUI](./app/sharedUI/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./app/sharedUI/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./app/sharedUI/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./app/sharedUI/src/jvmMain/kotlin)
    folder is the appropriate location.

* [/core](./core/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

### App icon

[app/hashiru-app-icon.svg](app/hashiru-app-icon.svg) is the square 走 master. Its PNG exports live in the iOS/watchOS `AppIcon.appiconset` catalogs and the Android/Wear OS `mipmap-*` resources; update every export when the master changes.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Wear OS app: `./gradlew :app:wearApp:assembleDebug`
- Server: `./gradlew :server:run`
- Web viewer: install [Node.js](https://nodejs.org/en/download/) (with npm), which Kotlin/JS also uses instead of
  downloading its own. Build the shared Kotlin/JS code with `./gradlew :app:sharedLogic:jsBrowserDevelopmentLibraryDistribution`,
  then run `npm install --ignore-scripts` and `npm run start`. The dev server proxies `/api` to the server on port 8080.
  The IDE's `webApp` run configuration does all of this. `npm run build` puts the static site in `app/webApp/dist`.
- iOS app: open the [/app/iosApp](./app/iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :app:sharedUI:testAndroidHostTest :app:sharedLogic:testAndroidHostTest`
- Server tests: `./gradlew :server:test`
- JS tests (on Node): `./gradlew :app:sharedLogic:jsNodeTest`
- iOS tests: `./gradlew :app:sharedLogic:iosSimulatorArm64Test`

### CI/CD

[CI](.github/workflows/ci.yml) builds and tests both apps on every pull request and push to `main`.
[Release](.github/workflows/release.yml) runs when a `vX.Y.Z` tag is pushed. It releases version `X.Y.Z` to the Google Play
internal track (the Wear OS app to `wear:internal`, with version codes offset by 1,000,000,000) and TestFlight,
then creates a GitHub Release. The build number is the workflow run number.

Publishing is done by two project-agnostic Gradle plugins in [/gradle-plugins](./gradle-plugins):

- `io.github.nols1000.release.android`: `publishPlay`, `bootstrapPlayPublishing`
- `io.github.nols1000.release.ios`: `archiveIos`, `publishTestFlight`, `bootstrapTestFlight`

Google Play uses GitHub OIDC with GCP Workload Identity Federation, so no Google key is stored anywhere.
Apple has no OIDC, so the App Store Connect API key is stored in the `testflight` GitHub environment.
The Android upload key is stored in the `google-play-store-internal` environment.

#### One-time setup

Run the bootstrap tasks in the [dev container](./.devcontainer), which has `java`, `gh` and `gcloud`, or on a host
that has those tools. Build the apps on the macOS host: the container is not meant for app builds, because Google
ships the Linux Android build tools for x86_64 only and iOS needs Xcode.

1. Set `release.gcpProject`, `release.playDeveloperId` and `release.appleTeamId` (the `OU` of your signing
   certificate, or developer.apple.com/account → Membership details) in `gradle.properties`.
2. Log in with `gh auth login`, `gcloud auth login` and
   `gcloud auth application-default login --scopes=openid,https://www.googleapis.com/auth/cloud-platform,https://www.googleapis.com/auth/androidpublisher`.
3. Google Play (its API cannot create apps or their first release):
   1. Create the app in Play Console.
   2. Run `./gradlew bootstrapPlayPublishing -PkeystoreOnly` to create the upload key in `~/.hashiru-secrets`.
      **Back up that folder.**
   3. On the macOS host, build `./gradlew :app:androidApp:bundleRelease`. Upload the bundle to the internal track by hand and enroll in Play App Signing.
   4. Run `./gradlew :app:androidApp:bootstrapPlayPublishing`. It creates the Workload Identity Federation pool, the
      provider and the `play-publisher` service account. It also grants that service account access in Play Console
      and fills the `google-play-store-internal` environment.
4. App Store Connect (its API cannot create apps):
   1. Create the app with bundle ID `com.github.nols1000.hashiru`.
   2. Create an API key with the Admin role, which Xcode needs for cloud-managed distribution certificates.
   3. Run `./gradlew bootstrapTestFlight -PascKeyPath=AuthKey_XXX.p8 -PascKeyId=XXX -PascIssuerId=YYY`.

Both bootstrap tasks are safe to run again.

### Deployment

The relay and the web viewer run on the destiny-ascension Kubernetes cluster at https://hashiru.tw-esport.de, with the viewer
at `/` and the relay at `/api`. [/deploy](./deploy) describes them, and Flux on the cluster applies whatever is on `main`.
The cluster's side lives in the `destiny-ascension-infra` repository: Flux itself, the `hashiru` namespace and the host name.

[Deploy](.github/workflows/deploy.yml) runs for every `vX.Y.Z` tag, or by hand. It publishes both images for arm64 to
`ghcr.io/nols1000/hashiru-server` and `ghcr.io/nols1000/hashiru-web`, then commits their digests to
[deploy/kustomization.yaml](./deploy/kustomization.yaml) on `main`, which deploys them within about 5 minutes. Reverting that commit rolls back.
The relay keeps shares only in memory, so a deploy drops all live shares. Phones open theirs again, and viewers wait for that.

To build the images locally, run `./gradlew :server:publishImageToLocalRegistry` (it builds `hashiru-server`). Build the viewer
as above, then run `docker build app/webApp`.

One-time setup: after the first run, make both packages public, so the cluster can pull them without credentials.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…