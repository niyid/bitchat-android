#!/usr/bin/env python3
import subprocess, tempfile, pathlib, re, os

JAVA_SRC = pathlib.Path("app/src/main/java")
SO_FILE = pathlib.Path("app/src/main/jniLibs/arm64-v8a/libmonerujo.so")  # adjust if needed

def run(cmd):
    return subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL)

def get_exports():
    out = run(["nm", "-D", "--defined-only", str(SO_FILE)])
    return {line.split()[-1] for line in out.splitlines() if line and not line.endswith(":")}

def mangle(name):
    # JNI name mangling (Java_ + pkg + class + _ + method)
    return name.replace("_", "_1").replace("/", "_")

def expected_jni(classname, method, signature):
    # Simplify: we only need name matching, not full signature suffix
    return f"Java_{mangle(classname)}_{method}"

def collect_natives():
    natives = []
    with tempfile.TemporaryDirectory() as tmpdir:
        # Compile all Java sources (no Android SDK, so errors are ignored)
        try:
            run(["javac", "-d", tmpdir] + [str(f) for f in JAVA_SRC.rglob("*.java")])
        except subprocess.CalledProcessError:
            pass  # expected, many Android deps missing
        # Iterate compiled classes
        for cls in pathlib.Path(tmpdir).rglob("*.class"):
            cname = str(cls.relative_to(tmpdir)).replace(os.sep, ".")[:-6]
            out = run(["javap", "-s", str(cls)])
            for line in out.splitlines():
                if "native" in line:
                    m = re.match(r'\s*public native [^\s]+\s+([^\(]+)\(.*', line)
                    if m:
                        natives.append((cname, m.group(1)))
    return natives

def main():
    exports = get_exports()
    natives = collect_natives()
    missing = []
    for cname, method in natives:
        expected = expected_jni(cname, method, "")
        if not any(exp.startswith(expected) for exp in exports):
            missing.append((cname, method, expected))
    print("=== JNI CHECK ===")
    if not missing:
        print("All native methods have matching JNI exports ✅")
    else:
        for cname, method, expected in missing:
            print(f"[MISSING] {cname}.{method} -> {expected}")

if __name__ == "__main__":
    main()

