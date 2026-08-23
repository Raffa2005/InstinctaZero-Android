# Release build

This repository's current release target is the offline local analysis board
(version 0.2.x). Before producing a release APK, rebuild and verify the two
checked-in browser assets from their retained sources:

```bash
cd web
npm ci
npm run check
cd ..
```

Then build the signed APK with JDK 17 and the Android SDK configured:

Create a local keystore outside version control, then supply its path and secrets as Gradle properties:

```bash
./gradlew \
  -PINSTINCTAZERO_STORE_FILE=/absolute/path/instinctazero-release.keystore \
  -PINSTINCTAZERO_STORE_PASSWORD='…' \
  -PINSTINCTAZERO_KEY_ALIAS=instinctazero \
  -PINSTINCTAZERO_KEY_PASSWORD='…' \
  assembleRelease
```

The resulting APK is `app/build/outputs/apk/release/app-release.apk`. The
signing key is required for every future update; keep it backed up and never
commit it. Do not put a keystore, passwords, or Gradle signing properties in a
release artifact, issue tracker, or source repository.

Before publishing, at minimum verify the APK installs, opens directly to the
offline board, and requests no network access. The app must contain no account,
pairing, sync, remote-engine, or Leela feature claim for this release.
