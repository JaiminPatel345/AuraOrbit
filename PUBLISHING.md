# Publishing AuraOrbit — Release Guide

This guide covers how to release a new version so it appears on F-Droid and GitHub Releases.

---

## Overview

F-Droid works like this:
- It builds your app **from source** using the commit hash in `fdroiddata/metadata/dev.jaimin.auraorbit.yml`
- It compares that build to your **signed APK** on GitHub Releases (reproducible build check)
- If they match, it distributes your APK to users

So every release = update code → tag → build signed APK → upload to GitHub → update fdroiddata.

---

## Step 1 — Bump the version in `app/build.gradle`

```groovy
versionCode 3          // increment by 1 every release, never reuse
versionName "1.1.0"    // human-readable, matches your git tag
```

Rules:
- `versionCode` must always go up (F-Droid and Android both require this)
- `versionName` should match your git tag (e.g. tag `v1.1.0` → versionName `"1.1.0"`)

---

## Step 2 — Commit all changes and push

```bash
git add -A
git commit -m "Release v1.1.0"
git push origin main
```

Note the full commit hash after pushing:
```bash
git rev-parse HEAD
# e.g. a1b2c3d4e5f6...
```

---

## Step 3 — Create and push a git tag

```bash
git tag v1.1.0
git push origin v1.1.0
```

The tag name must start with `v` and match `versionName` — F-Droid's `UpdateCheckMode: Tags` scans for this.

---

## Step 4 — Build the signed APK in Android Studio

1. Open Android Studio
2. **Build → Generate Signed App Bundle / APK**
3. Choose **APK**
4. Select your keystore file (`auraorbit-release.jks`)
5. Enter keystore password, key alias, key password
6. Choose **release** build variant → **Create**
7. Output: `app/release/app-release.apk`

> The keystore is what signs your identity into the APK. Always use the same keystore — if you lose it, you can never update the app for existing users.

---

## Step 5 — Create a GitHub Release and upload the APK

```bash
# Create the release (first time for this version)
gh release create v1.1.0 \
  --title "AuraOrbit v1.1.0" \
  --notes "Your changelog here" \
  --repo JaiminPatel345/AuraOrbit

# Upload the APK with the correct filename (must match Binaries URL pattern)
cp app/release/app-release.apk /tmp/AuraOrbit-1.1.0.apk
gh release upload v1.1.0 /tmp/AuraOrbit-1.1.0.apk \
  --repo JaiminPatel345/AuraOrbit
```

If you need to replace an already-uploaded APK:
```bash
gh release upload v1.1.0 /tmp/AuraOrbit-1.1.0.apk --clobber --repo JaiminPatel345/AuraOrbit
```

The APK filename **must** follow the pattern `AuraOrbit-<versionName>.apk` because the `Binaries` URL in fdroiddata uses `AuraOrbit-%v.apk`.

---

## Step 6 — Update fdroiddata metadata

Edit `metadata/dev.jaimin.auraorbit.yml` in your fdroiddata fork on GitLab (web editor is fine).

Add a new entry to `Builds` and update the bottom fields:

```yaml
Builds:
  - versionName: 1.0.1          # old entry, keep it
    versionCode: 2
    commit: e2eb97a58bdfce16fb40c59157fcd8929d8b9cf7
    subdir: app
    gradle:
      - yes

  - versionName: 1.1.0          # new entry
    versionCode: 3
    commit: <full hash from Step 2>
    subdir: app
    gradle:
      - yes

# Update these two lines at the bottom:
CurrentVersion: 1.1.0
CurrentVersionCode: 3
```

Do **not** change `AllowedAPKSigningKeys` — that never changes.

---

## Step 7 — Trigger CI

Commit the fdroiddata change. The GitLab CI pipeline will run automatically. Jobs that must pass:

| Job | What it checks |
|-----|----------------|
| `fdroid lint` | YAML is valid |
| `fdroid rewritemeta` | Field order / formatting |
| `fdroid checkupdates` | Version detection works |
| `check apk` | No forbidden signing blocks |
| `fdroid build` | Builds from source successfully |
| Reproducibility | Source build == your uploaded APK |

If reproducibility fails, see the Troubleshooting section below.

---

## Troubleshooting

### "version-control-info.textproto revision mismatch"
The APK was built when HEAD was a different commit than what's in `commit:`.
- Make sure you build the APK **after** your final push (Step 4 comes after Step 2–3)
- Check the embedded hash: `unzip -p app/release/app-release.apk META-INF/version-control-info.textproto`
- That hash must exactly match what's in `commit:` in the metadata

### "found extra signing block 'Dependency metadata'"
The `dependenciesInfo` block is already disabled in `build.gradle`. If this reappears, ensure that code is still present:
```groovy
dependenciesInfo {
    includeInApk = false
    includeInBundle = false
}
```

### "Bk.xml differs" (or any res/ file differs)
A resource file changed between the commit used for metadata and the APK build.
- Ensure no uncommitted changes exist when you build: `git status` should be clean
- Build the APK only after committing everything and pushing

---

## Quick checklist for every release

- [ ] `versionCode` incremented in `app/build.gradle`
- [ ] `versionName` updated to match new tag
- [ ] All changes committed and pushed to `main`
- [ ] New tag pushed (`git tag vX.Y.Z && git push origin vX.Y.Z`)
- [ ] APK built in Android Studio **after** the push (so embedded hash matches)
- [ ] APK uploaded to GitHub Release as `AuraOrbit-X.Y.Z.apk`
- [ ] New `Builds` entry added to fdroiddata metadata
- [ ] `CurrentVersion` and `CurrentVersionCode` updated in fdroiddata
- [ ] fdroiddata CI passes all jobs
