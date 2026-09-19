#!/usr/bin/env python3
"""Publish the checked-out historical build, without rebuilding or uploading keys.

Run with --prepare-only to verify and package assets without network access.
GitHub Actions uses its repository-scoped GITHUB_TOKEN with contents: write.
"""
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "Xenoah/rokid-spectrum-hud"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare():
    meta = json.loads((ROOT / ".release/current.json").read_text())
    version = meta["version"]
    if not re.fullmatch(r"1\.0\.[0-3]", version):
        raise SystemExit("This workflow publishes the four archived prereleases only.")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    for name in filter(None, tracked):
        p = Path(name)
        if "signing" in p.parts or p.suffix.lower() in {".p12", ".jks", ".keystore", ".pem", ".key"}:
            raise SystemExit("Refusing to publish signing material.")
    apk = ROOT / meta["apk"]
    if sha256(apk) != meta["apk_sha256"]:
        raise SystemExit("Archived APK checksum mismatch.")
    with zipfile.ZipFile(apk) as z:
        if z.testzip() is not None or not {"AndroidManifest.xml", "classes.dex"}.issubset(z.namelist()):
            raise SystemExit("Invalid APK container.")
    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
    if f'android:versionName="{version}"' not in manifest:
        raise SystemExit("Source version does not match release metadata.")
    out = ROOT / "_publish_assets"
    out.mkdir(exist_ok=True)
    assets = []
    for source, name in [(meta["apk"], apk.name)] + [(x["path"], x["name"]) for x in meta["images"]]:
        if Path(name).name != name:
            raise SystemExit("Invalid asset name.")
        target = out / name
        shutil.copyfile(ROOT / source, target)
        assets.append(target)
    source = out / f"RokidSpectrum-{version}-source.zip"
    subprocess.run(["git", "archive", "--format=zip", f"--prefix=RokidSpectrum-{version}/",
                    "--output", str(source), commit], cwd=ROOT, check=True)
    assets.append(source)
    checksums = out / "SHA256SUMS.txt"
    checksums.write_text("".join(f"{sha256(p)}  {p.name}\n" for p in assets))
    assets.append(checksums)
    print(json.dumps({"version": version, "commit": commit, "assets": [p.name for p in assets]}, indent=2))
    return meta, commit, assets


def publish(meta, commit, assets):
    if os.environ.get("GITHUB_REPOSITORY") != REPOSITORY:
        raise SystemExit("Publication is restricted to the intended repository.")
    if os.environ.get("GITHUB_SHA") != commit:
        raise SystemExit("The checkout must match the triggering commit.")
    token = os.environ["GITHUB_TOKEN"]
    base = f"https://api.github.com/repos/{REPOSITORY}"

    def api(method, path, data=None, binary=None, content_type=None, missing_ok=False):
        url = path if path.startswith("https://") else base + path
        if urllib.parse.urlparse(url).netloc not in {"api.github.com", "uploads.github.com"}:
            raise RuntimeError("Unexpected API host.")
        headers = {"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "rokid-spectrum-prerelease"}
        body = binary
        if data is not None:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        elif binary is not None:
            headers["Content-Type"] = content_type or "application/octet-stream"
        req = urllib.request.Request(url, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=90) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            if missing_ok and exc.code == 404:
                return None
            raise RuntimeError(f"GitHub API {method} failed with HTTP {exc.code}") from None

    tag = "v" + meta["version"]
    ref = api("GET", "/git/ref/tags/" + tag, missing_ok=True)
    if ref is None:
        api("POST", "/git/refs", {"ref": "refs/tags/" + tag, "sha": commit})
    elif ref["object"]["type"] != "commit" or ref["object"]["sha"] != commit:
        raise SystemExit("Existing tag points to a different commit; refusing to overwrite it.")
    release = api("GET", "/releases/tags/" + tag, missing_ok=True)
    body = (ROOT / "releases" / (tag + ".md")).read_text()
    if release is None:
        release = api("POST", "/releases", {"tag_name": tag, "target_commitish": commit,
            "name": meta["title"], "body": body, "draft": True, "prerelease": True, "make_latest": "false"})
    elif not release["prerelease"] or release["body"] != body:
        raise SystemExit("Existing release differs; refusing to change it.")
    existing = {a["name"]: a for a in release["assets"]}
    wanted = {p.name for p in assets}
    if set(existing) - wanted:
        raise SystemExit("Existing release has unexpected assets.")
    for asset in assets:
        digest = "sha256:" + sha256(asset)
        old = existing.get(asset.name)
        if old is not None:
            if old["size"] != asset.stat().st_size or old.get("digest") != digest:
                raise SystemExit("Existing asset differs; refusing to replace it: " + asset.name)
            continue
        if not release["draft"]:
            raise SystemExit("Published release is incomplete; refusing to mutate it.")
        url = release["upload_url"].split("{")[0] + "?" + urllib.parse.urlencode({"name": asset.name})
        uploaded = api("POST", url, binary=asset.read_bytes(),
                       content_type=mimetypes.guess_type(asset.name)[0])
        if uploaded["size"] != asset.stat().st_size or uploaded.get("digest") != digest:
            raise SystemExit("Uploaded asset verification failed: " + asset.name)
        print("Uploaded", asset.name)
    if release["draft"]:
        release = api("PATCH", "/releases/" + str(release["id"]),
                      {"draft": False, "prerelease": True, "make_latest": "false"})
    confirmed = api("GET", "/releases/" + str(release["id"]))
    if confirmed["draft"] or not confirmed["prerelease"] or {a["name"] for a in confirmed["assets"]} != wanted:
        raise SystemExit("Final prerelease verification failed.")
    print("Published prerelease:", confirmed["html_url"])


if __name__ == "__main__":
    prepared = prepare()
    if "--prepare-only" not in sys.argv:
        publish(*prepared)
