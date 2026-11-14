#!/usr/bin/env python3
"""Build the pinned Ghostty + Rust JNI library; no adb/device operations."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT.parent / "rexling-android"
TARGET = "aarch64-linux-android"


def run(command, **kwargs):
    subprocess.run([str(v) for v in command], check=True, **kwargs)


def download(url, expected, path):
    if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == expected:
        return
    temporary = path.with_suffix(path.suffix + ".part")
    run(["curl", "-fL", "--retry", "2", url, "-o", temporary])
    if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
        raise RuntimeError(f"Checksum mismatch: {url}")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-test", action="store_true")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "native/ghostty-source.json").read_text())
    arch = {"arm64": "aarch64", "aarch64": "aarch64", "x86_64": "x86_64"}[platform.machine()]
    host = {"Darwin": "macos", "Linux": "linux"}[platform.system()]
    cache = ROOT / ".build/cache"
    cache.mkdir(parents=True, exist_ok=True)
    tool = manifest["zig"][f"{arch}-{host}"]
    zig_archive = cache / Path(tool["tarball"]).name
    source_archive = cache / "ghostty-source.tar.gz"
    # Optional cache seed within this repository; no sibling checkout is needed.
    old_cache = ROOT / "target/ghostty-build"
    for destination, old_name in [(zig_archive, zig_archive.name), (source_archive, "ghostty-source.tar.gz")]:
        if not destination.exists() and (old_cache / old_name).exists():
            shutil.copyfile(old_cache / old_name, destination)
    download(tool["tarball"], tool["shasum"], zig_archive)
    download(manifest["source_url"], manifest["source_sha256"], source_archive)
    zig_root = cache / zig_archive.name.removesuffix(".tar.xz")
    source_root = cache / ("ghostty-" + manifest["revision"])
    for archive, extracted in [(zig_archive, zig_root), (source_archive, source_root)]:
        if not extracted.exists():
            with tarfile.open(archive) as tar:
                # macOS may provide Python 3.9, before tarfile's data filter.
                for member in tar.getmembers():
                    (cache / member.name).resolve().relative_to(cache.resolve())
                    if member.issym() or member.islnk():
                        parent = (cache / member.name).parent if member.issym() else cache
                        (parent / member.linkname).resolve().relative_to(cache.resolve())
                    if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                        raise RuntimeError("Unsupported archive member")
                tar.extractall(cache)
    env = os.environ.copy()
    cargo = shutil.which("cargo") or str(Path.home() / ".cargo/bin/cargo")
    rustup = shutil.which("rustup") or str(Path.home() / ".cargo/bin/rustup")
    env["CARGO_TARGET_DIR"] = str(ROOT / ".build/rust")
    prefix = ROOT / (".build/ghostty-host" if args.host_test else ".build/ghostty-arm64")
    command = [zig_root / "zig", "build", "-Demit-lib-vt", "-Demit-macos-app=false", "-Demit-xcframework=false", "-Doptimize=ReleaseFast", "--prefix", prefix]
    if not args.host_test:
        sdk = Path(env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT") or Path.home() / "Library/Android/sdk")
        ndks = sdk / "ndk"
        ndk = Path(env["ANDROID_NDK_HOME"]) if env.get("ANDROID_NDK_HOME") else ndks / manifest["android_ndk_version"]
        env["ANDROID_HOME"] = str(sdk)
        env["ANDROID_NDK_HOME"] = str(ndk)
        command += ["-Dtarget=aarch64-linux-android.28"]
        compiler_root = ndk / "toolchains/llvm/prebuilt" / ("darwin-x86_64" if host == "macos" else "linux-x86_64")
        compiler = compiler_root / "bin/aarch64-linux-android28-clang"
        env["CC_aarch64_linux_android"] = str(compiler)
        env["CXX_aarch64_linux_android"] = str(compiler) + "++"
        env["AR_aarch64_linux_android"] = str(compiler_root / "bin/llvm-ar")
        env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = str(compiler)
        env["CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
    if not args.host_test:
        network = ROOT.parent / "rexling-network"
        network_prefix = ROOT / ".build/network-arm64"
        network_prefix.mkdir(parents=True, exist_ok=True)
        network_env = env.copy()
        network_env.update({"GOOS":"android", "GOARCH":"arm64", "CGO_ENABLED":"1", "CC":str(compiler), "CGO_LDFLAGS":"-Wl,-z,max-page-size=16384"})
        run(["go", "build", "-mod=readonly", "-trimpath", "-buildmode=c-shared", "-ldflags=-s -w", "-o", network_prefix / "librex_network.so", "."], cwd=network, env=network_env)
        env["REXLING_NETWORK_PREFIX"] = str(network_prefix)
    run(command, cwd=source_root, env=env)
    env["GHOSTTY_PREFIX"] = str(prefix)
    if args.host_test:
        run([cargo, "test", "--locked", "--workspace", "--all-features", "--manifest-path", ROOT / "Cargo.toml"], cwd=ROOT, env=env)
        return
    run([rustup, "target", "add", TARGET], env=env)
    run([cargo, "build", "--locked", "--release", "--target", TARGET, "--manifest-path", ROOT / "Cargo.toml", "-p", "rexling-jni"], cwd=ROOT, env=env)
    destination = ANDROID / "app/src/main/jniLibs/arm64-v8a"
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / f".build/rust/{TARGET}/release/librex_android.so", destination / "librex_android.so")
    shutil.copyfile(network_prefix / "librex_network.so", destination / "librex_network.so")
    shutil.copyfile(compiler_root / "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so", destination / "libc++_shared.so")
    print(f"Android arm64 JNI libraries built in {destination}")


if __name__ == "__main__":
    main()
