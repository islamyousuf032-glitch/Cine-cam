#!/usr/bin/env python3
"""Checks that every Kotlin `external fun` matches its JNI implementation byte for byte.

A JNI function is called only by name (no overloads here), so the JVM never checks the parameter
types: a Kotlin `Long` read as `jint` silently truncates. That exact mistake made every 180 s
segmented session refuse to start (the 64-bit sensor epoch arrived negative). This script compares
the declared Kotlin types with the JNI types so the bug cannot come back unnoticed.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "app/src/main/java/com/cinecam/engine/NativeRawEngine.kt"
JNI = ROOT / "app/src/main/cpp/src/jni_bridge.cpp"
PREFIX = "Java_com_cinecam_engine_NativeRawEngine_name_"

TYPE_MAP = {
    "Int": "jint", "Long": "jlong", "Boolean": "jboolean", "Float": "jfloat",
    "Double": "jdouble", "String": "jstring", "IntArray": "jintArray",
    "DoubleArray": "jdoubleArray", "ByteArray": "jbyteArray",
    "ByteBuffer": "jobject",
}


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


def split_top_level(text: str) -> list[str]:
    parts, depth, current = [], 0, []
    for char in text:
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        if char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    if "".join(current).strip():
        parts.append("".join(current))
    return [part.strip() for part in parts if part.strip()]


def kotlin_signatures() -> dict[str, list[str]]:
    text = strip_comments(KOTLIN.read_text(encoding="utf-8"))
    result: dict[str, list[str]] = {}
    # The tempered group stops a declaration from swallowing the next one across newlines.
    # A declaration ends either with ": ReturnType" or (for Unit functions) at the end of the line.
    pattern = r"external\s+fun\s+(\w+)\s*\(((?:(?!external\s+fun)[\s\S])*?)\)(?:\s*:|[ \t]*$)"
    for match in re.finditer(pattern, text, flags=re.M):
        name, params = match.group(1), match.group(2)
        types = []
        for param in split_top_level(params):
            declared = param.split(":", 1)[1].strip().rstrip("?").strip()
            base = declared.replace("?", "")
            if base not in TYPE_MAP:
                raise SystemExit(f"{name}: unknown Kotlin parameter type '{declared}'")
            types.append(TYPE_MAP[base])
        result[name] = types
    if not result:
        raise SystemExit("no Kotlin external declarations found")
    return result


def jni_signatures() -> dict[str, list[str]]:
    text = strip_comments(JNI.read_text(encoding="utf-8"))
    result: dict[str, list[str]] = {}
    # JNI parameters never contain parentheses, so a simple non-greedy group is exact.
    for match in re.finditer(
            r"Java_com_cinecam_engine_NativeRawEngine_(\w+)\s*\(([^)]*)\)\s*\{", text):
        name, params = match.group(1), match.group(2)
        declared = split_top_level(params)
        if len(declared) < 2:
            raise SystemExit(f"{name}: JNI implementation is missing JNIEnv/jobject")
        types = []
        # The first two arguments are always JNIEnv* and jobject (the Kotlin `this`).
        for param in declared[2:]:
            found = re.match(r"(j\w+)", param.strip())
            if not found:
                types.append(param.strip())
            else:
                types.append(found.group(1))
        result[name] = types
    if not result:
        raise SystemExit("no JNI implementations found")
    return result


def main() -> int:
    kotlin, jni = kotlin_signatures(), jni_signatures()
    failures = []
    for name, expected in kotlin.items():
        actual = jni.get(name)
        if actual is None:
            failures.append(f"{name}: Kotlin declares it but JNI does not implement it")
            continue
        if len(actual) != len(expected):
            failures.append(f"{name}: {len(expected)} Kotlin parameters but {len(actual)} JNI parameters")
            continue
        for index, (want, got) in enumerate(zip(expected, actual), start=1):
            if want != got:
                failures.append(f"{name}: parameter {index} is {want} in Kotlin but {got} in JNI "
                                f"(a wider JVM value read narrow silently truncates)")
    for name in jni:
        if name not in kotlin:
            failures.append(f"{name}: JNI implementation has no Kotlin declaration")
    if failures:
        print("JNI signature check failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print(f"JNI signatures match for {len(kotlin)} native methods.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
