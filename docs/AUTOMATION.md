# Automatic APK updates

This fork rebuilds a signed Android APK whenever Freenet core publishes a new
`vX.Y.Z` GitHub release, without waiting for a manual `workflow_dispatch`.

## What happens

1. [`.github/workflows/auto-bump.yml`](../.github/workflows/auto-bump.yml) runs
   every six hours (and on demand).
2. It reads `https://github.com/freenet/freenet-core/releases/latest`.
3. If this repository already has a GitHub Release for that core — the exact
   tag `vX.Y.Z` **or** a fork suffix `vX.Y.Z.N` such as `v0.2.135.1` — it
   stops. The first APK for a new core should use the core tag; later app-only
   builds use `.1`, `.2`, …
4. Otherwise it:
   - checks out that core tag next to this repo
   - runs `cargo update` in `native/` so `Cargo.lock` matches the new crate graph
   - rewrites the documented pins (`README`, `docs/BASELINE.md`, CI default, …)
   - pushes a `[skip ci]` commit
   - dispatches [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) with
     `skip_prechecks=true` so the signed APK is built once, not three times
5. On success CI publishes `freenet-android-node-release.apk` and
   `SHA256SUMS.txt` under `https://github.com/HostFat/freenet-android-node/releases`.
6. On failure it opens (or comments on) a GitHub issue assigned to `HostFat`.

The JNI adapter is compiled **from Freenet core source** for
`aarch64-linux-android` and `x86_64-linux-android`. It cannot load the
prebuilt `freenet` binaries from `freenet/freenet-core` releases: those are
desktop/server executables (Linux musl, macOS, Windows), not Android JNI
libraries.

Core's own auto-update is disabled in the Android adapter
(`disable_auto_update = true` in `native/src/runtime.rs`) for the same reason:
it would download a host binary that cannot run inside this APK.

Installing a newly published APK on a phone still requires a user tap
(sideload / Package Installer). Android does not allow a silent replace of a
sideloaded app.

The app itself also checks GitHub (and, while a network node is running, the
highest Freenet version already reported by peers) on launch, at most every
4 hours by default (2 / 4 / 6 / 12 hours from the drawer), plus from
**Check for updates**. If a newer APK exists it shows a
quiet banner and a silent notification whose tap opens the GitHub release page
in the browser. If core is newer but this fork has not published an APK yet,
only the in-app banner appears.

## Pull request CI

Pull requests never publish an APK. They also skip the Docker/NDK compile unless
the diff touches native code, the Dockerfile, build scripts, Gradle/NDK config,
`AndroidManifest.xml`, or `.github/workflows/ci.yml`. Kotlin/UI-only PRs run
Gradle unit tests on the runner instead. Docs-only PRs skip compilation.

Pushes to `main` and signed `workflow_dispatch` releases still always compile
native. The release concurrency policy is unchanged: a PR cannot cancel an
in-progress signed APK job.

## Notifications

Watch this repository's **Releases** on GitHub to get an email when an APK is
published. Failed auto-bumps and failed release jobs open GitHub issues.

## When automation cannot recover by itself

A new core release that **breaks the JNI compile** (renamed public API, new
mandatory config, Android-hostile dependency) will fail CI. That needs a source
change in `native/` and a human (or coding agent) to look at the issue.

## Manual override

```bash
gh workflow run ci.yml \
  -f freenet_core_version=v0.2.135 \
  -f release_version=0.2.135 \
  -f skip_prechecks=false
```

Omit `skip_prechecks` or set it `false` to also run lint, unit tests, and the
debug APK.
