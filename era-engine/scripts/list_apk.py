import sys
import zipfile

apk = sys.argv[1]
pattern = sys.argv[2] if len(sys.argv) > 2 else ""
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    print("entries: %d" % len(names))
    for name in sorted(names):
        if not pattern:
            interesting = True
        else:
            interesting = pattern in name
        interesting = interesting or name.startswith("assemblies") or "assembl" in name
        if interesting and (name.startswith("lib/") or name.startswith("assemblies") or name.endswith(".blob")):
            print("%10d  %s" % (archive.getinfo(name).file_size, name))
