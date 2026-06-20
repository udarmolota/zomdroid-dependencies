# RimDroid CJK Font — runtime mod (no resources.assets edit)

## Why this approach
Editing `resources.assets` to add CJK glyphs **always** corrupts RimWorld's UI textures on
box64 + Zink/Turnip: any byte change invalidates the cached **TMP/SDF "non-english" atlas**, the game
fails to stream it (`async texture load: failed to load LiberationSans SDF misc non-english Atlas`) and
regenerates it at runtime, which trashes GPU textures. Proven: the original asset is the only clean one.

So we leave `resources.assets` **untouched** and add CJK at runtime instead:
- RimWorld draws almost all UI via legacy IMGUI (`Verse.Text` → `GUIStyle`/`GameFont`), not TMP.
- The mod swaps those fonts for an **embedded Noto Sans CJK** dynamic font (Latin+Cyrillic+CJK).
- A dynamic font is rasterized by Unity's built-in FreeType (works under box64) and **never touches the
  streamed SDF atlases**, so no corruption. The original asset still loads cleanly.

Unity has no runtime "make a Font from a TTF" API, and OS-font discovery is dead under box64, so the font
must arrive as a prebuilt **AssetBundle**. That bundle is the only thing that needs Unity — and we build
it in **GitHub Actions** (GameCI), so **no local Unity** (same trick as the Zink build). The mod DLL needs
no Unity at all.

## What's here
```
RimDroidCJK/                       the mod (source)
  About/About.xml
  Source/CjkFontPatch.cs           [StaticConstructorOnStartup] -> reflect Verse.Text -> swap fonts
  Source/RimDroidCJK.csproj        builds with Krafs.Rimworld.Ref (no game DLLs, no Unity)
RimDroidCJK-bundle/
  unity/                           tiny Unity project that bakes the font bundle
    Assets/Fonts/RimDroidCJK.ttf   Noto SC subset, glyf, Latin+Cyrillic+20976 CJK, 12.7 MB
    Assets/Editor/BuildBundle.cs   headless AssetBundle builder (StandaloneLinux64)
    ProjectSettings/ProjectVersion.txt   2019.4.30f1 (must match the game)
  cjk-bundle.yml                   GitHub Actions: bundle + DLL + assembled mod zip
  README.md                        this file
```

## Build (cloud, no local Unity / no local .NET)
1. Push `RimDroidCJK/` and `RimDroidCJK-bundle/` to a GitHub repo, and put `cjk-bundle.yml` at
   `.github/workflows/cjk-bundle.yml`. (Can reuse the RimDroid repo that already runs the Zink CI.)
2. One-time: add a **free Unity Personal license** as repo secret `UNITY_LICENSE`
   (request .alf → https://license.unity3d.com/manual → .ulf → paste full contents as the secret).
   See https://game.ci/docs/github/activation
3. Actions tab → "Build RimDroid CJK Mod" → Run workflow.
4. Download the **`RimDroidCJK-mod`** artifact = a ready `RimDroidCJK/` folder
   (`About/` + `Assemblies/RimDroidCJK.dll` + `Resources/rimdroidcjk.bundle`).

## Install (on device)
1. Keep `resources.assets` **ORIGINAL** (this is the whole point).
2. Copy the `RimDroidCJK/` folder into the instance's `.../RimWorldLinux_Data/../Mods/`
   (same Mods dir RimWorld loads from; next to Core/Royalty/…).
3. Enable "RimDroid CJK Font" in the in-game mod list, restart, set language to Chinese.
4. Expected: CJK renders across the UI, no texture glitches. Player.log shows
   `[RimDroidCJK] CJK UI font applied: RimDroidCJK`.

## Notes / fallback
- First CI run is a shakedown — GameCI's `buildMethod` path occasionally needs a tweak; the bundle is
  written to `unity/bundle-out/rimdroidcjk.bundle` regardless of GameCI's own build-output expectations.
- If line spacing looks slightly off (Noto metrics ≠ Arial), that's cosmetic; can tune later via the
  GUIStyle line-height fields.
- TMP-rendered text (the few non-IMGUI spots) keeps the original SDF atlas (still loads fine) — if any
  CJK there stays blank, add a static TMP fallback as a v2 (also a bundle asset).
