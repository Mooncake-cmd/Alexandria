#!/usr/bin/env python3
"""
Compila os motores do OpenReadEra (eramobi, eradjvu, eracomic) para Linux, só para os testes na JVM.

No app, os motores são compilados pelo NDK para Android. Aqui, os mesmos Android.mk são lidos por
dump.mk (sem o NDK) e cada fonte é compilada com gcc/g++/nasm do sistema. Assim os testes conversam
com os motores de verdade pelo mesmo protocolo de pipes que o app usa.

Uso: build_host_engines.py <pasta-das-fontes-openreadera> <pasta-de-saída>
Requer: make, gcc, g++, nasm, zlib (cabeçalhos).
"""
import concurrent.futures
import os
import shlex
import subprocess
import sys
from pathlib import Path

ENGINES = ["eramobi", "eradjvu", "eracomic", "erapdf"]
HERE = Path(__file__).resolve().parent

# Mesmas exceções do Application.mk: o código foi escrito para o NDK r20.
COMMON_C = ["-O1", "-g0", "-fPIC", "-w", "-ffunction-sections", "-fdata-sections", "-DANDROID", "-D__ANDROID__",
            "-Wno-error=implicit-function-declaration", "-Wno-error=int-conversion",
            "-Wno-error=incompatible-pointer-types", "-Wno-error=implicit-int",
            "-fcommon"]
# No Android, os cabeçalhos do sistema já trazem <cstring>, <cmath>... indiretamente; no Linux, não.
COMMON_CXX = ["-std=gnu++14", "-fexceptions", "-frtti", "-fpermissive",
              "-include", "cstring", "-include", "cmath", "-include", "cstdlib", "-include", "cstdio",
              "-include", "climits", "-include", "cstdint", "-include", "algorithm",
              "-include", "memory", "-include", "unistd.h"]


def parse_dump(root: Path):
    out = subprocess.run(
        ["make", "-s", "-f", str(HERE / "dump.mk"), f"SHIM={HERE}", f"ROOT={root}", "dump"],
        check=True, capture_output=True, text=True).stdout
    modules, current = {}, None
    for line in out.splitlines():
        key, _, value = line.partition(" ")
        if key == "MODULE":
            current = modules.setdefault(value.strip(), {})
        elif current is not None:
            current[key] = value.strip()
    return modules


def deps(modules, name, seen=None):
    """Bibliotecas estáticas de um módulo, recursivamente, na ordem de ligação."""
    seen = seen if seen is not None else []
    for dep in modules[name].get("STATIC", "").split():
        if dep in modules and dep not in seen:
            seen.append(dep)
            deps(modules, dep, seen)
    return seen


def compile_one(job):
    src, obj, cmd = job
    obj.parent.mkdir(parents=True, exist_ok=True)
    if obj.exists() and obj.stat().st_mtime >= src.stat().st_mtime:
        return None
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        return f"{src}:\n{result.stderr[-3000:]}"
    return None


def main():
    root, out = Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve()
    modules = parse_dump(root)
    needed = []
    for engine in ENGINES:
        for m in [engine] + deps(modules, engine):
            if m not in needed:
                needed.append(m)

    jobs, objects = [], {}
    for name in needed:
        mod = modules[name]
        path = Path(mod["PATH"])
        includes = [str(HERE / "stub")]
        for inc in mod.get("INC", "").split():
            includes.append(inc)
        for dep in deps(modules, name):
            includes += modules[dep].get("EXPORT", "").split()
        includes.append(str(path))
        cflags = shlex.split(mod.get("CFLAGS", "")) + COMMON_C + [f"-I{i}" for i in includes]
        cppflags = shlex.split(mod.get("CPPFLAGS", "")) + COMMON_CXX
        objects[name] = []
        for src_name in mod.get("SRC", "").split():
            src = Path(src_name) if os.path.isabs(src_name) else path / src_name
            obj = out / "obj" / name / (src.relative_to(root).as_posix().replace("/", "_") + ".o")
            if src.suffix in (".cpp", ".cc", ".cxx"):
                cmd = ["g++", "-c", str(src), "-o", str(obj)] + cflags + cppflags
            elif src.suffix == ".asm":
                simd = src.parent
                cmd = ["nasm", "-felf64", "-DELF", "-D__x86_64__", f"-I{simd}/", f"-I{simd.parent}/nasm/",
                       f"-I{root}/jpeg-turbo/", str(src), "-o", str(obj)]
            elif src.suffix in (".c", ".S", ".neon"):
                cmd = ["gcc", "-c", "-x", "c" if src.suffix != ".S" else "assembler-with-cpp", str(src), "-o", str(obj)] + cflags
            else:
                continue
            jobs.append((src, obj, cmd))
            objects[name].append(obj)

    with concurrent.futures.ThreadPoolExecutor(max_workers=os.cpu_count() or 2) as pool:
        errors = [e for e in pool.map(compile_one, jobs) if e]
    if errors:
        print("\n\n".join(errors[:5]), file=sys.stderr)
        sys.exit(1)

    log_obj = out / "obj" / "stub_log.o"
    subprocess.run(["gcc", "-c", str(HERE / "stub" / "log.c"), "-I", str(HERE / "stub"), "-o", str(log_obj)], check=True)
    for engine in ENGINES:
        archives = []
        for dep in deps(modules, engine):
            lib = out / "obj" / f"lib{dep}.a"
            subprocess.run(["ar", "rcs", str(lib)] + [str(o) for o in objects[dep]], check=True)
            archives.append(str(lib))
        target = out / engine
        cmd = (["g++", "-o", str(target)] + [str(o) for o in objects[engine]] + [str(log_obj)] +
               ["-Wl,--start-group"] + archives + ["-Wl,--end-group", "-Wl,--gc-sections", "-lz", "-lpthread", "-lm"])
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            print(result.stderr[-4000:], file=sys.stderr)
            sys.exit(1)
        print(f"ok {target}")


if __name__ == "__main__":
    main()
