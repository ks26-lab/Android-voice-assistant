#!/usr/bin/env python3
"""Compile Person 2 + unchanged dependencies and run JVM tests using cached project tooling.

No Gradle/source exclusions are changed. Android adapter is compiled against the real SDK;
JUnit tests use a fake driver. This is not an APK build or an on-device execution test.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"


def jar(group, artifact, version):
    matches = sorted((CACHE / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
    if not matches:
        raise SystemExit(f"Missing cached dependency: {group}:{artifact}:{version}. Populate the project's Gradle cache first.")
    return str(matches[0])


def main():
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    kotlin = lambda artifact, version="1.9.22": jar("org.jetbrains.kotlin", artifact, version)
    compiler = [kotlin("kotlin-compiler-embeddable"), kotlin("kotlin-stdlib"),
                kotlin("kotlin-script-runtime"), kotlin("kotlin-reflect", "1.6.10"),
                kotlin("kotlin-daemon-embeddable"), jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                jar("org.jetbrains", "annotations", "13.0")]
    dependencies = [kotlin("kotlin-stdlib"), jar("org.jetbrains", "annotations", "13.0"),
                    jar("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.6.2"),
                    jar("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.6.2"),
                    jar("junit", "junit", "4.13.2"), jar("org.hamcrest", "hamcrest-core", "1.3")]
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        props = ROOT / "local.properties"
        if props.exists():
            sdk = next((line.split("=", 1)[1] for line in props.read_text().splitlines() if line.startswith("sdk.dir=")), None)
    if not sdk or not (Path(sdk) / "platforms/android-34/android.jar").exists():
        raise SystemExit("Android SDK 34 is required (ANDROID_HOME or local.properties).")
    android_jar = str(Path(sdk) / "platforms/android-34/android.jar")
    base = ROOT / "app/src/main/java/com/chockXlate/teachablevoice"
    folders = ["contract", "runtime", "safety", "execution", "app/service", "teach/capture"]
    sources = sorted(str(p) for folder in folders for p in (base / folder).rglob("*.kt"))
    sources.append(str(base / "skill/repository/SkillRepository.kt"))
    tests_root = ROOT / "app/src/test/java/com/chockXlate/teachablevoice/runtime"
    tests = sorted(tests_root.rglob("*Test.kt"))
    sources.extend(str(p) for p in tests)
    with tempfile.TemporaryDirectory(prefix="person2-tests-") as output:
        command = [java, "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                   "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                   "-Xplugin=" + kotlin("kotlin-serialization-compiler-plugin-embeddable"),
                   "-classpath", os.pathsep.join(dependencies + [android_jar]), "-d", output] + sources
        print("Compiling Person 2, frozen contracts, repository interface, service, and teaching capture dependencies.", flush=True)
        subprocess.run(command, cwd=ROOT, check=True)
        classes = ["com.chockXlate.teachablevoice.runtime." + p.stem for p in tests]
        subprocess.run([java, "-cp", os.pathsep.join([output] + dependencies), "org.junit.runner.JUnitCore"] + classes,
                       cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
