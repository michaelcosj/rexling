# Third-party notices

The repository's Apache 2.0 license covers Rexling project code. It does not
replace licenses or grant trademark rights for the following upstream material.

| Material | Retained terms and provenance |
| --- | --- |
| Ghostty/libghostty-vt | MIT; `rexling-core/native/GHOSTTY_LICENSE.txt`, pinned source in `ghostty-source.json`, and Android `assets/licenses/GHOSTTY_LICENSE.txt` |
| JetBrains Mono / Nerd Font font files | SIL Open Font License and Nerd Fonts notices, sources and checksums in `rexling-android/app/src/main/assets/licenses/` |
| Noto Sans Symbols 2 fallback font | SIL Open Font License; `NOTO_SYMBOLS_OFL.txt` and pinned source/checksum in `NOTO_SYMBOLS_SOURCE.json` in the Android license assets |
| Simple Icons logos | CC0-1.0; `simple-icons-CC0.txt` and `program-icons.json` in the same license assets; brand marks remain their owners' property |
| User-supplied Superlogical program tiles and Cloudflare glyph | Retained SVG sources, source catalog and checksums in `rexling-android/artwork/program-icons/superlogical/metadata.json`; trademarks and logos remain their owners' property |
| Expanded program catalog and SVG compiler additions | Adapted from [lemon-supply-chain/rexling](https://github.com/lemon-supply-chain/rexling) at `ed05beb791122a005695ecbd5fbd51d9c12f2dfe`; Apache 2.0 project code, with upstream icon provenance/checksums retained in the catalog metadata |
| Rex theme color projection | Original provenance and source hash retained in `rexling-android/app/src/main/assets/themes/SOURCE.json`; Apache 2.0 is not asserted over upstream Rex material |
| OkHttp / Okio test fixtures (not shipped in the release app) | Apache 2.0; pinned OkHttp 4.12.0 and its resolved Okio dependency in the Gradle manifests and Maven metadata |
| Haze backdrop blur | Apache 2.0; Chris Banes and contributors, pinned to 1.5.4; https://github.com/chrisbanes/haze |
| PNG decoder (`png` Rust crate) | MIT OR Apache 2.0; pinned in `rexling-core/Cargo.lock`; retained `assets/licenses/PNG_LICENSE-MIT.txt` and `PNG_LICENSE-APACHE.txt` |
| Rust / AndroidX / other dependencies | Their respective upstream licenses; pinned dependency versions in Cargo.lock and the Gradle manifests |

The launcher artwork supplied for Rexling is retained with its preparation
provenance under `rexling-android/artwork/`. No third-party notice files were
removed or replaced by the repository consolidation. A copy of the project
Apache 2.0 license and NOTICE is also packaged in the APK's license assets.

Official Apache 2.0 text: https://www.apache.org/licenses/LICENSE-2.0.txt
