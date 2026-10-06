# 🧩 2eno Patches

Ad blocking and anti-tracking patches for apps without official ad free alternatives,
compatible with [Morphe](https://morphe.software).

| App | What the patches do |
|-----|---------------------|
| Spotify | Mute audio ads, hide ad banners, ad sections, popup ads, playlist and video ads, the Premium tab and Premium upsells, remove link tracking. **No Premium unlock.** |
| Kleinanzeigen | Hide ads and "Kleinanzeigen Pur" offers, remove link tracking |
| Untappd | Hide Google ads, feed ad slots and sponsored content |
| InterPals | Hide Google ads |

The same patches are also available for rooted devices as part of the
[NexAlloy](https://github.com/2eno/NexAlloy) LSPosed module, which uses the extension code of this repository.

## ❓ How to use these patches

Add this repository as a patch source in Morphe Manager:
https://morphe.software/add-source?github=2eno/2eno-patches

Or manually: Morphe Manager → Patch sources → ➕ → enter `https://github.com/2eno/2eno-patches`.

Morphe Manager checks the source for updates on its own. To get pre-releases from the `dev` branch,
enable pre-releases for this source.

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->

<!-- Do not modify this section by hand. The patch list is generated when release.yml creates a new release. -->

#### The list of patches will be shown here after the first release.

<!-- PATCHES_END -->

## 🧑‍💻 Development

- All changes go to the `dev` branch. Merge `dev` into `main` (merge commit, no squash) for a stable release.
- Use [semantic commit](https://kapeli.com/cheat_sheets/Semantic_Commits.docset/Contents/Resources/Documents/index)
  messages: `feat:` and `fix:` create a new release, `chore:` does not.
- Releases, `patches-bundle.json`, `patches-list.json`, `CHANGELOG.md` and the patch list above
  are created by [release.yml](.github/workflows/release.yml). Do not edit them by hand.

### Project layout

- `patches/`: Bytecode patches (Kotlin), one package per app.
- `extensions/twoeno/`: Java code merged into the patched apps.
  It only uses the Android framework and reflection, so [NexAlloy](https://github.com/2eno/NexAlloy)
  compiles the very same code into its Xposed hooks.

### 🛠️ Building locally

Building requires a GitHub token with the `read:packages` scope, because the Morphe
build tools are hosted on GitHub Packages. Add it to `~/.gradle/gradle.properties`:

```properties
gpr.user = your-github-username
gpr.key = ghp_...
```

- Run `./gradlew buildAndroid`
- The patches file is `patches/build/libs/patches-*.mpp`.
  Apply it with [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop) like any other patch bundle.

## 📜 License

2eno Patches are licensed under the [GNU General Public License v3.0](LICENSE).
This project is not affiliated with Morphe. See [NOTICE](NOTICE).
