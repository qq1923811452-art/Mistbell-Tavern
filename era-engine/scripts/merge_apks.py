"""把引擎 APK 的内容注入主应用 APK，产出单包。

只做三件确定性的事：
  1. 引擎的 `assemblies/` 复制到 APK 根目录（Gradle 打不出这个顶层目录）
  2. 引擎的 `lib/arm64-v8a/*.so` 并入主应用的 native 库
  3. 引擎的 `classes.dex` 追加为 `classesN.dex`（minSdk 26 的 ART 会加载所有 classes*.dex）

签名与对齐由外部 apksigner/zipalign 负责；manifest 里的 provider/service 由主工程清单声明。
"""

import sys
import zipfile

ABI = "arm64-v8a"
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA")


def is_signature(name: str) -> bool:
    upper = name.upper()
    return upper.startswith("META-INF/") and upper.endswith(SIGNATURE_SUFFIXES)


def copy_entry(source: zipfile.ZipFile, target: zipfile.ZipFile, info: zipfile.ZipInfo) -> None:
    data = source.read(info.filename)
    clone = zipfile.ZipInfo(info.filename, date_time=info.date_time)
    clone.compress_type = info.compress_type
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

        if not injected_assemblies:
            print("ERROR: 引擎 APK 里没有 assemblies/，无法单包", file=sys.stderr)
            return 2
        if engine_dex not in engine_names:
            print("ERROR: 引擎 APK 里没有 classes.dex", file=sys.stderr)
            return 2

        existing_libs = {n for n in app_names if n.startswith("lib/")}
        skipped = [n for n in injected_libs if n in existing_libs]

        print(f"assemblies: {len(injected_assemblies)} 项")
        print(f"native libs: {len(injected_libs)} 项（重名跳过 {len(skipped)}）")
        print(f"engine dex -> classes{'' if dex_index == 1 else dex_index}.dex")

        with zipfile.ZipFile(out_apk, "w") as out:
            for info in app.infolist():
                if is_signature(info.filename):
                    continue
                copy_entry(app, out, info)

            for name in injected_assemblies:
                copy_entry(engine, out, engine.getinfo(name))

            for name in injected_libs:
                if name in existing_libs:
                    continue
                copy_entry(engine, out, engine.getinfo(name))

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
