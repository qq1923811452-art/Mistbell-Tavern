import os
import shutil
import sys
import zipfile

# 把 dotnet publish 出来的 APK 重组成一个 AGP 可消费的 AAR：
#   jni/<abi>/*.so  ← APK 的 lib/<abi>/*.so
#   classes.jar     ← mono.android.jar + obj 里的 JCW classes
#   AndroidManifest.xml ← obj 里的纯文本 manifest（保留 provider，加 :era 进程）
# 只做结构搬运，不做 APK 后处理；用来验证「AGP 能否原生合并」这条路。

apk, manifest_src, mono_jar, out = sys.argv[1:5]
stage = sys.argv[5] if len(sys.argv) > 5 else "/tmp/aar_stage"

shutil.rmtree(stage, ignore_errors=True)
os.makedirs(os.path.join(stage, "jni", "arm64-v8a"), exist_ok=True)
os.makedirs(os.path.join(stage, "res"), exist_ok=True)

with zipfile.ZipFile(apk) as archive:
    apk_names = archive.namelist()
    for name in apk_names:
        if name.startswith("lib/arm64-v8a/") and name.endswith(".so"):
            target = os.path.join(stage, "jni", "arm64-v8a", os.path.basename(name))
            with archive.open(name) as source, open(target, "wb") as sink:
                shutil.copyfileobj(source, sink)
    blob_so = [n for n in apk_names if "libassemblies" in n]
    print("libassemblies entries in APK: %s" % (blob_so or "NONE"))
    root_entries = sorted({n.split("/")[0] for n in apk_names if "/" in n})
    print("APK top-level dirs: %s" % root_entries)
    assemblies = [n for n in apk_names if n.startswith("assemblies/")]
    print("assemblies/ entries in APK: %d" % len(assemblies))

# classes.jar：mono.android.jar + JCW
java_dir = os.path.join(stage, "_java")
os.makedirs(java_dir, exist_ok=True)
if mono_jar and os.path.isfile(mono_jar):
    with zipfile.ZipFile(mono_jar) as jar:
        jar.extractall(java_dir)
    print("mono.android.jar extracted")
else:
    print("WARN: mono.android.jar not found: %s" % mono_jar)

jcw_dir = os.path.join(os.path.dirname(apk), "..", "..", "..", "obj", "Release", "net8.0-android34.0", "android-arm64", "android", "bin", "classes")
jcw_dir = os.path.normpath(jcw_dir)
if os.path.isdir(jcw_dir):
    for root, _, files in os.walk(jcw_dir):
        for name in files:
            source = os.path.join(root, name)
            target = os.path.join(java_dir, os.path.relpath(source, jcw_dir))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copyfile(source, target)
    print("JCW classes merged from %s" % jcw_dir)
else:
    print("WARN: JCW dir not found: %s" % jcw_dir)

with zipfile.ZipFile(os.path.join(stage, "classes.jar"), "w", zipfile.ZIP_DEFLATED) as jar:
    for root, _, files in os.walk(java_dir):
        for name in files:
            path = os.path.join(root, name)
            jar.write(path, os.path.relpath(path, java_dir))

# manifest
if manifest_src and os.path.isfile(manifest_src):
    shutil.copyfile(manifest_src, os.path.join(stage, "AndroidManifest.xml"))
    print("manifest copied from %s" % manifest_src)
else:
    print("WARN: manifest not found: %s" % manifest_src)

open(os.path.join(stage, "R.txt"), "w").close()

os.makedirs(os.path.dirname(out), exist_ok=True)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as aar:
    for root, _, files in os.walk(stage):
        for name in files:
            path = os.path.join(root, name)
            rel = os.path.relpath(path, stage)
            if rel.startswith("_java"):
                continue
            aar.write(path, rel)
print("wrote %s (%d bytes)" % (out, os.path.getsize(out)))
