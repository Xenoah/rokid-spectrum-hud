#!/usr/bin/env python3
"""Dependency-free Android build using installed SDK tools, Java 17 and Python 3.

ANDROID_JAR: installed platforms/android-35/android.jar or newer
ANDROID_BUILD_TOOLS: SDK build-tools directory (aapt2, zipalign, lib/d8.jar, lib/apksigner.jar)
SPECTRUM_KEYSTORE: persistent signing key; default is signing/development.p12
SPECTRUM_STOREPASS: password (defaults to Android's conventional development password)
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build" / "manual"
DIST = ROOT / "dist"
ANDROID = Path(os.environ.get("ANDROID_JAR", ""))
TOOLS = Path(os.environ.get("ANDROID_BUILD_TOOLS", ""))
KEY = Path(os.environ.get("SPECTRUM_KEYSTORE", str(ROOT / "signing" / "development.p12")))
PASSWORD = os.environ.get("SPECTRUM_STOREPASS", "android")

def run(*args):
    args = [str(x) for x in args]
    p = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if p.stdout:
        print(p.stdout, end="", flush=True)
    if p.returncode:
        raise SystemExit(p.returncode)

def main():
    if not ANDROID.is_file() or not (TOOLS / "aapt2").is_file():
        raise SystemExit("Set ANDROID_JAR and ANDROID_BUILD_TOOLS to an installed Android SDK.")
    if BUILD.exists():
        shutil.rmtree(BUILD)
    for d in [BUILD / "classes", BUILD / "dex", DIST, ROOT / "verification"]:
        d.mkdir(parents=True, exist_ok=True)
    app = ROOT / "app" / "src" / "main"
    run(TOOLS / "aapt2", "compile", "--dir", app / "res", "-o", BUILD / "resources.zip")
    run(TOOLS / "aapt2", "link", "-I", ANDROID, "--manifest", app / "AndroidManifest.xml",
        "--min-sdk-version", "26", "--target-sdk-version", "32", "-o", BUILD / "unsigned.apk", BUILD / "resources.zip")
    sources = sorted((ROOT / "core" / "src" / "main" / "java").rglob("*.java")) + sorted((app / "java").rglob("*.java"))
    run("java", "com.sun.tools.javac.Main", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options",
        "-bootclasspath", ANDROID, "-d", BUILD / "classes", *sources)
    classes_jar = BUILD / "classes.jar"
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted((BUILD / "classes").rglob("*.class")):
            z.write(path, path.relative_to(BUILD / "classes"))
    run("java", "-cp", TOOLS / "lib" / "d8.jar", "com.android.tools.r8.D8", "--release", "--min-api", "26",
        "--lib", ANDROID, "--output", BUILD / "dex", classes_jar)
    with zipfile.ZipFile(BUILD / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as z:
        for dex in (BUILD / "dex").glob("*.dex"):
            z.write(dex, dex.name)
    run(TOOLS / "zipalign", "-f", "4", BUILD / "unsigned.apk", BUILD / "aligned.apk")
    if not KEY.is_file():
        KEY.parent.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy(); env["SPECTRUM_SIGNING_PASSWORD"] = PASSWORD
        p = subprocess.run(["keytool", "-genkeypair", "-keystore", str(KEY), "-storetype", "PKCS12",
            "-storepass:env", "SPECTRUM_SIGNING_PASSWORD", "-keypass:env", "SPECTRUM_SIGNING_PASSWORD", "-alias", "rokid-spectrum",
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000", "-dname", "CN=Xenoah Rokid Spectrum Development"], env=env,
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if p.returncode: raise SystemExit("Signing key creation failed")
        KEY.chmod(0o600)
    os.environ["SPECTRUM_SIGNING_PASSWORD"] = PASSWORD
    apk = DIST / "RokidSpectrum-1.0.0.apk"
    run("java", "-jar", TOOLS / "lib" / "apksigner.jar", "sign", "--ks", KEY, "--ks-key-alias", "rokid-spectrum",
        "--ks-pass", "env:SPECTRUM_SIGNING_PASSWORD", "--key-pass", "env:SPECTRUM_SIGNING_PASSWORD",
        "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
        "--out", apk, BUILD / "aligned.apk")
    run("java", "-jar", TOOLS / "lib" / "apksigner.jar", "verify", "--verbose", "--print-certs", apk)
    run(TOOLS / "zipalign", "-c", "-v", "4", apk)
    run(TOOLS / "aapt2", "dump", "badging", apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (DIST / "SHA256SUMS.txt").write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
    print(f"APK: {apk}\nSize: {apk.stat().st_size} bytes\nSHA-256: {digest}")

if __name__ == "__main__": main()
