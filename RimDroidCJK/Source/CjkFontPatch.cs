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

        // Verse.Text indexes its fonts by GameFont {Tiny,Small,Medium}. Rendering goes through
        // CurFontStyle -> fontStyles[fontInt], so the GUIStyle's .font is what actually draws. Those
        // style arrays (fontStyles/textFieldStyles/textAreaStyles/textAreaReadOnlyStyles) are PUBLIC
        // static, while the backing Font[] fonts is PRIVATE. We set the GUIStyles' .font directly
        // (compile-checked via the public API) and also overwrite fonts[] via reflection for good
        // measure. Each GUIStyle keeps its own fontSize, so text stays the right size but now resolves
        // through our font (Latin+Cyrillic+CJK). The dynamic-font atlas is built at runtime by FreeType
        // (works under box64) and never touches the streamed TMP SDF atlases, so no texture corruption.
        private static void ReplaceVerseTextFonts(Font cjk)
        {
            int styled = 0;
            foreach (GUIStyle[] arr in new[] { Text.fontStyles, Text.textFieldStyles,
                                               Text.textAreaStyles, Text.textAreaReadOnlyStyles })
            {
                if (arr == null) continue;
                foreach (GUIStyle s in arr)
                    if (s != null) { s.font = cjk; styled++; }
            }

            // private static Font[] fonts — reflection (NonPublic is correct here).
            var fonts = typeof(Text).GetField("fonts", BindingFlags.NonPublic | BindingFlags.Static)
                                    ?.GetValue(null) as Font[];
            if (fonts != null)
                for (int i = 0; i < fonts.Length; i++)
                    fonts[i] = cjk;

            string verify = (Text.fontStyles != null && Text.fontStyles.Length > 1 && Text.fontStyles[1] != null
                             && Text.fontStyles[1].font != null) ? Text.fontStyles[1].font.name : "<null>";
            Log.Message("[RimDroidCJK] styles set: " + styled + ", fonts[] len: " + (fonts != null ? fonts.Length : 0)
                        + ", verify fontStyles[1].font = " + verify);
        }
    }
}
