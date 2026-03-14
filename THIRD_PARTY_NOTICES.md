# Third-Party Notices

This file is a convenience summary of the major upstream components used by
this repository. It is not a replacement for the original license texts in each
upstream project.

## Project License Scope

The top-level project license for this repository is Apache-2.0, but that scope
applies only to the files authored for this repository, such as:

- `app/`
- `scripts/`
- `patches/`
- top-level Gradle and project configuration files
- this repository's documentation

The following bundled submodules and vendored sources keep their own original
licenses and are not relicensed by the top-level `LICENSE` file:

- `scrcpy/`
- `android-tools/`
- `SDL/`
- `android-deps/`

## Upstream Summary

### scrcpy

- Path: `scrcpy/`
- Upstream: <https://github.com/Genymobile/scrcpy>
- License: Apache-2.0
- Relevance: core Android screen/control server implementation used by this project

### android-tools

- Path: `android-tools/`
- Upstream: <https://github.com/XiaoTong6666/android-tools>
- License: Apache-2.0 at repository top level
- Relevance: used to build the embedded and host-side `adb`
- Note: its vendor tree contains multiple third-party components under their own
  licenses; this project uses the `adb`-focused build path, and the submodule
  remains under its own upstream licensing terms

### SDL

- Path: `SDL/`
- Upstream: <https://github.com/libsdl-org/SDL/>
- License: zlib
- Relevance: used for the Android-side rendering/activity integration

### abseil-cpp

- Path: `android-deps/abseil-cpp/`
- Upstream: <https://github.com/abseil/abseil-cpp>
- License: Apache-2.0

### brotli

- Path: `android-deps/brotli/`
- Upstream: <https://github.com/google/brotli>
- License: MIT

### lz4

- Path: `android-deps/lz4/`
- Upstream: <https://github.com/lz4/lz4>
- License summary:
  - `lib/` is BSD-2-Clause
  - other repository areas include GPL-2.0-or-later material
- Relevance: current build flow uses the library portion required by the adb
  build; the repository itself still retains its mixed upstream licensing

### pcre2

- Path: `android-deps/pcre2/`
- Upstream: <https://github.com/PCRE2Project/pcre2>
- License: BSD-3-Clause WITH PCRE2-exception

### protobuf

- Path: `android-deps/protobuf/`
- Upstream: <https://github.com/protocolbuffers/protobuf>
- License: BSD-3-Clause

### zstd

- Path: `android-deps/zstd/`
- Upstream: <https://github.com/facebook/zstd>
- License: BSD-3-Clause

## Why Apache-2.0 Was Chosen

Apache-2.0 is a reasonable top-level license for this repository because:

- the two most important upstream bases for this project, `scrcpy` and
  `android-tools`, are both Apache-2.0
- the other directly referenced dependencies in this repository are permissive
  licenses such as zlib, MIT, BSD, or Apache
- Apache-2.0 keeps patent language and contribution rules explicit, which fits
  a project that patches and redistributes build/runtime components

## Practical Distribution Rule

When redistributing this project, keep the top-level `LICENSE` for this
repository's own code, and also preserve each bundled upstream project's own
license files and notices.
