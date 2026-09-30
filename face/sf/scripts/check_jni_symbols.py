"""Cross-check Java native declarations against JNI C++ exported symbols.

JNI resolves a native method by exact symbol name derived from the Java
declaring class: Java_<package>_<Class>_<method>, plus an overload suffix
(__<TypeChars>) when the method is overloaded in the class. Upstream
SeetaFace6JNI has (at least) one class-name typo mismatch
(QualityOfBirghtness vs JNIQualityOfBrightness.cpp) and several Java
accessors with no upstream implementation at all; both are bridged from
src/main/cpp/bridges, which this script folds into the expected symbol set.

Usage: python check_jni_symbols.py <SeetaFace6JNI dir> [bridges dir]
Exit 1 if any declaration has no matching symbol anywhere.
"""
import re
import sys
from pathlib import Path

# JNI type encoding chars for the overload suffix, enough for upstream signatures.
PRIM = {"void": "V", "boolean": "Z", "int": "I", "long": "J",
        "float": "F", "double": "D", "byte": "B", "char": "C", "short": "S"}
NATIVE_RE = re.compile(r"\bnative\s+[\w\[\]<>.]+\s+(\w+)\s*\(([^)]*)\)")


def java_symbols(java_root: Path):
    """Yield (class_fqn, method, has_overload_suffix_expected) for native methods."""
    classes = {}
    for f in java_root.rglob("*.java"):
        text = f.read_text(encoding="utf-8", errors="replace")
        pkg_m = re.search(r"\bpackage\s+([\w.]+)\s*;", text)
        cls_m = re.search(r"\bclass\s+(\w+)", text)
        if not pkg_m or not cls_m:
            continue
        fqn = f"{pkg_m.group(1)}.{cls_m.group(1)}"
        natives = []
        for m in NATIVE_RE.finditer(text):
            args = m.group(2).strip()
            natives.append((m.group(1), args))
        if natives:
            classes.setdefault(fqn, []).extend(natives)
    out = []
    for fqn, natives in classes.items():
        by_name = {}
        for name, _ in natives:
            by_name[name] = by_name.get(name, 0) + 1
        for name, args in natives:
            overloaded = by_name[name] > 1
            out.append((fqn, name, overloaded,
                        "OVERLOADED" if overloaded else args or "()"))
    return out


def cpp_symbols(cpp_root: Path):
    syms = set()
    for f in list(cpp_root.rglob("*.cpp")) + list(cpp_root.rglob("*.c")):
        for m in re.finditer(r"Java_[A-Za-z0-9_]+", f.read_text(encoding="utf-8", errors="replace")):
            syms.add(m.group(0))
    return syms


def main():
    jni_dir = Path(sys.argv[1])
    natives = java_symbols(jni_dir)
    syms = cpp_symbols(jni_dir)
    for extra in sys.argv[2:]:
        syms |= cpp_symbols(Path(extra))
    bad = []
    for fqn, name, _overloaded, sig in natives:
        base = "Java_" + fqn.replace(".", "_") + "_" + name
        # The JVM tries the short name first, then the overload-suffixed long
        # name, so either form satisfies the lookup.
        ok = any(s == base or s.startswith(base + "__") for s in syms)
        if not ok:
            bad.append((fqn, name, sig, base))
    if not bad:
        print(f"OK: {len(natives)} native declarations all matched.")
        return 0
    print(f"MISMATCHES ({len(bad)} of {len(natives)}):")
    for fqn, name, sig, base in sorted(bad):
        print(f"  {fqn}.{name}{sig}")
        print(f"    expected symbol prefix: {base}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
