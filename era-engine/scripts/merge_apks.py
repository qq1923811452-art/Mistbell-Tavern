"""把引擎 APK 的内容注入主应用 APK，产出单包。"""

import sys
import zipfile

ABI = "arm64-v8a"
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA")
# .NET 10 把程序集打成一个 .so（libassemblies.<abi>.blob.so），不再是 assemblies/ 目录；
# 这两个包里出现即代表引擎运行时，和主包重名时必须硬报错而不是静默跳过。
CRITICAL_ENGINE_SOS = {
    f"lib/{ABI}/libmonodroid.so",
    f"lib/{ABI}/libmonosgen-2.0.so",
    f"lib/{ABI}/libxamarin-app.so",
    f"lib/{ABI}/libe_sqlite3.so",
    f"lib/{ABI}/libassemblies.{ABI}.blob.so",
}


def is_signature(name: str) -> bool:
    upper = name.upper()
    return upper.startswith("META-INF/") and upper.endswith(SIGNATURE_SUFFIXES)


def copy_entry(
    source: zipfile.ZipFile,
    target: zipfile.ZipFile,
    info: zipfile.ZipInfo,
    force_stored: bool = False,
) -> None:
    data = source.read(info.filename)
    clone = zipfile.ZipInfo(info.filename, date_time=info.date_time)
    clone.compress_type = zipfile.ZIP_STORED if force_stored else info.compress_type
    clone.external_attr = info.external_attr
    target.writestr(clone, data)


def next_dex_index(names: list[str]) -> int:
    index = 1
    for name in names:
        if not name.startswith("classes") or not name.endswith(".dex"):
            continue
        middle = name[len("classes"):-len(".dex")]
        if middle == "":
            index = max(index, 2)
        elif middle.isdigit():
            index = max(index, int(middle) + 1)
    return index


def main(app_apk: str, engine_apk: str, out_apk: str) -> int:
    with zipfile.ZipFile(app_apk) as app, zipfile.ZipFile(engine_apk) as engine:
        app_names = app.namelist()
        dex_index = next_dex_index(app_names)
        engine_names = engine.namelist()

        injected_assemblies = [n for n in engine_names if n.startswith("assemblies/")]
        injected_libs = [n for n in engine_names if n.startswith(f"lib/{ABI}/") and n.endswith(".so")]
        engine_dex = "classes.dex"

        # .NET 8：assemblies/ 目录 + lib/<abi>/*.so
        # .NET 10：程序集合并进 lib/<abi>/libassemblies.<abi>.blob.so，没有 assemblies/
        bundled_assemblies = [n for n in injected_libs if "libassemblies." in n and n.endswith(".blob.so")]
        if not injected_assemblies and not bundled_assemblies:
            print("ERROR: 引擎 APK 里既没有 assemblies/ 也没有 libassemblies.*.blob.so", file=sys.stderr)
            return 2
        if engine_dex not in engine_names:
            print("ERROR: 引擎 APK 里没有 classes.dex", file=sys.stderr)
            return 2

        existing_libs = {n for n in app_names if n.startswith("lib/")}
        critical_conflicts = [n for n in injected_libs if n in existing_libs and n in CRITICAL_ENGINE_SOS]
        if critical_conflicts:
            print(f"ERROR: 核心引擎 .so 与主包冲突: {critical_conflicts}", file=sys.stderr)
            return 3

        skipped = [n for n in injected_libs if n in existing_libs]
        if skipped:
            print(f"WARNING: 跳过已存在的共享 .so: {skipped}", file=sys.stderr)

        print(f"assemblies: {len(injected_assemblies)} 项，libassemblies.blob: {len(bundled_assemblies)} 项")
        print(f"native libs: {len(injected_libs)} 项（跳过共用 {len(skipped)}）")
        print(f"engine dex -> classes{'' if dex_index == 1 else dex_index}.dex")

        with zipfile.ZipFile(out_apk, "w") as out:
            for info in app.infolist():
                if is_signature(info.filename):
                    continue
                is_uncompressed_native = info.filename.startswith("lib/") and info.filename.endswith(".so")
                copy_entry(app, out, info, force_stored=is_uncompressed_native)

            for name in injected_assemblies:
                must_store = name.endswith(".blob")
                copy_entry(engine, out, engine.getinfo(name), force_stored=must_store)

            for name in injected_libs:
                if name in existing_libs:
                    continue
                copy_entry(engine, out, engine.getinfo(name), force_stored=True)

            dex_name = "classes.dex" if dex_index == 1 else f"classes{dex_index}.dex"
            data = engine.read(engine_dex)
            clone = zipfile.ZipInfo(dex_name, date_time=engine.getinfo(engine_dex).date_time)
            clone.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(clone, data)

    print(f"wrote {out_apk}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("usage: merge_apks.py <app.apk> <engine.apk> <out.apk>", file=sys.stderr)
        sys.exit(1)
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3]))
