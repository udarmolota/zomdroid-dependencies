using System.IO;
using UnityEditor;
using UnityEngine;

// Headless AssetBundle builder. Run in CI via:
//   unity-editor -batchmode -nographics -quit -projectPath unity -executeMethod BuildBundle.Build
// Produces unity/bundle-out/rimdroidcjk.bundle for StandaloneLinux64, containing the dynamic
// Noto CJK font as asset "RimDroidCJK" (Font), which the RimDroid CJK mod loads at runtime.
public static class BuildBundle
{
    private const string OutDir = "bundle-out";
    private const string FontAsset = "Assets/Fonts/RimDroidCJK.ttf";
    private const string BundleName = "rimdroidcjk.bundle";

    [MenuItem("RimDroid/Build CJK Bundle")]
    public static void Build()
    {
        // Force the TTF to import as a Dynamic font (runtime FreeType rasterization),
        // which is what works under box64 and never touches the streamed TMP SDF atlases.
        var importer = AssetImporter.GetAtPath(FontAsset) as TrueTypeFontImporter;
        if (importer != null)
        {
            importer.fontTextureCase = FontTextureCase.Dynamic;
            importer.SaveAndReimport();
        }

        Directory.CreateDirectory(OutDir);
        var build = new AssetBundleBuild
        {
            assetBundleName = BundleName,
            assetNames = new[] { FontAsset }
        };

        BuildPipeline.BuildAssetBundles(
            OutDir,
            new[] { build },
            BuildAssetBundleOptions.None,
            BuildTarget.StandaloneLinux64);

        string produced = Path.Combine(OutDir, BundleName);
        Debug.Log(File.Exists(produced)
            ? "[BuildBundle] OK -> " + Path.GetFullPath(produced) + " (" + new FileInfo(produced).Length + " bytes)"
            : "[BuildBundle] FAILED: bundle not produced");
    }
}
