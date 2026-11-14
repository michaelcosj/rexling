fn main() {
    println!("cargo:rerun-if-env-changed=GHOSTTY_PREFIX");
    println!("cargo:rerun-if-changed=native/ghostty_shim.c");
    if std::env::var_os("CARGO_FEATURE_GHOSTTY").is_none() {
        return;
    }
    let prefix = std::env::var_os("GHOSTTY_PREFIX")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|| {
            std::path::PathBuf::from(std::env::var_os("CARGO_MANIFEST_DIR").unwrap())
                .join(".build/ghostty-host")
        });
    let prefix = std::fs::canonicalize(prefix).expect(
        "build Ghostty first with python3 scripts/build_native.py --host-test (or set GHOSTTY_PREFIX)",
    );
    println!(
        "cargo:rerun-if-changed={}",
        prefix.join("include").display()
    );
    println!(
        "cargo:rerun-if-changed={}",
        prefix.join("lib/libghostty-vt.a").display()
    );
    cc::Build::new()
        .file("native/ghostty_shim.c")
        .include(prefix.join("include"))
        .define("GHOSTTY_STATIC", None)
        .warnings(true)
        .compile("rex_ghostty_shim");
    // Use an archive-only basename: Darwin's linker may otherwise select the
    // neighboring dylib for -lghostty-vt despite Rust's static-library intent.
    let out = std::path::PathBuf::from(std::env::var_os("OUT_DIR").unwrap());
    std::fs::copy(
        prefix.join("lib/libghostty-vt.a"),
        out.join("librex_ghostty_vt.a"),
    )
    .expect("copy Ghostty static archive");
    println!("cargo:rustc-link-lib=static=rex_ghostty_vt");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        let network = std::env::var("REXLING_NETWORK_PREFIX").expect("network library must be built for Android");
        println!("cargo:rustc-link-search=native={network}");
        println!("cargo:rustc-link-lib=dylib=rex_network");
        println!("cargo:rustc-link-lib=c++_shared");
    }
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("macos") {
        println!("cargo:rustc-link-lib=c++");
    }
}
