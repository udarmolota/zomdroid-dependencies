using System;
using System.IO;
using System.Reflection;
using UnityEngine;
using Verse;

namespace RimDroidCJK
{
    // Runs once at startup, after Verse.Text's static constructor has built its font tables.
    // No Harmony needed: we only read/replace fields, we don't patch methods.
    [StaticConstructorOnStartup]
    public static class RimDroidCJKFontPatch
    {
        private const string PackageId = "rimdroid.cjkfont";
        private const string BundleRelPath = "Resources/rimdroidcjk.bundle";
        // Optional explicit asset name; if it doesn't match we fall back to "first Font in bundle".
        private const string FontAssetName = "RimDroidCJK";

        static RimDroidCJKFontPatch()
        {
            try
            {
                Font cjk = LoadBundledFont();
                if (cjk == null) return; // LoadBundledFont already logged the reason
                ReplaceVerseTextFonts(cjk);
                Log.Message("[RimDroidCJK] CJK UI font applied: " + cjk.name);
            }
            catch (Exception e)
            {
                Log.Error("[RimDroidCJK] font injection failed: " + e);
            }
        }

        private static Font LoadBundledFont()
        {
            ModContentPack mod = null;
            foreach (var m in LoadedModManager.RunningModsListForReading)
            {
                if (m.PackageId != null &&
                    m.PackageId.Equals(PackageId, StringComparison.OrdinalIgnoreCase))
                {
                    mod = m;
                    break;
                }
            }
            if (mod == null)
            {
                Log.Error("[RimDroidCJK] mod content pack '" + PackageId + "' not found");
                return null;
            }

            string bundlePath = Path.Combine(mod.RootDir, BundleRelPath.Replace('/', Path.DirectorySeparatorChar));
            if (!File.Exists(bundlePath))
            {
                Log.Error("[RimDroidCJK] font bundle missing: " + bundlePath);
                return null;
            }

            AssetBundle bundle = AssetBundle.LoadFromFile(bundlePath);
            if (bundle == null)
            {
                Log.Error("[RimDroidCJK] AssetBundle.LoadFromFile returned null (platform/version mismatch?): " + bundlePath);
                return null;
            }

            Font font = bundle.LoadAsset<Font>(FontAssetName);
            if (font == null)
            {
                Font[] all = bundle.LoadAllAssets<Font>();
                if (all != null && all.Length > 0) font = all[0];
            }
            if (font == null)
                Log.Error("[RimDroidCJK] bundle loaded but contains no Font asset");
            return font;
        }

        // Verse.Text holds (private static): Font[] fonts; GUIStyle[] fontStyles, textFieldStyles,
        // textAreaStyles, textAreaReadOnlyStyles — all indexed by GameFont {Tiny,Small,Medium}.
        // Swapping the Font (keeping each GUIStyle's fontSize) makes the whole legacy IMGUI UI render
        // through our font, which carries Latin+Cyrillic+CJK, so existing text is unchanged and CJK
        // now resolves. The dynamic-font atlas is built at runtime by FreeType (works under box64) and
        // never touches the streamed TMP SDF atlases, so no UI-texture corruption.
        private static void ReplaceVerseTextFonts(Font cjk)
        {
            const BindingFlags BF = BindingFlags.NonPublic | BindingFlags.Static;
            Type t = typeof(Verse.Text);

            var fonts = t.GetField("fonts", BF)?.GetValue(null) as Font[];
            if (fonts != null)
                for (int i = 0; i < fonts.Length; i++)
                    fonts[i] = cjk;

            string[] styleArrays = { "fontStyles", "textFieldStyles", "textAreaStyles", "textAreaReadOnlyStyles" };
            foreach (string name in styleArrays)
            {
                var arr = t.GetField(name, BF)?.GetValue(null) as GUIStyle[];
                if (arr == null) continue;
                foreach (GUIStyle s in arr)
                    if (s != null) s.font = cjk;
            }
        }
    }
}
