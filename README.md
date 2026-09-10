# ike-version-management-extension

[![Maven Central](https://img.shields.io/maven-central/v/network.ike.tooling/ike-version-management-extension)](https://central.sonatype.com/artifact/network.ike.tooling/ike-version-management-extension)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Documentation](https://img.shields.io/badge/docs-ike.network%2Fike--version--management--extension-blue)](https://ike.network/ike-version-management-extension/)
[![IKE Network](https://img.shields.io/badge/IKE-Network-green)](https://ike.network/)

`network.ike.tooling:ike-version-management-extension` is a Maven 4
build extension that enforces the IKE typed-marker convention for
version properties. A version pin is named
`${groupId__GA__artifactId__VERSION}`, a release policy for the same
coordinate is `${groupId__GA__artifactId__POLICY}`, and the legacy
short names an artifact also goes by are listed in
`${groupId__GA__artifactId__ALIAS}`. The legacy U+00B7 form
(`${groupId·artifactId}`) is still read during the transition
([IKE-Network/ike-issues#525](https://github.com/IKE-Network/ike-issues/issues/525)).

The canonical documentation is the Maven site at
<https://ike.network/ike-version-management-extension/>. The
convention itself is
[`IKE-VERSIONS.md`](https://github.com/IKE-Network/ike-tooling/blob/main/ike-build-standards/src/main/standards/IKE-VERSIONS.md)
in `ike-tooling`.

## What it does

Registered in a repository's `.mvn/extensions.xml`, the extension
hooks Maven 4's `ModelTransformer` SPI and applies five rules to every
POM under that directory. POMs resolved from a repository pass through
untouched.

1. **Alias injection** (file-model stage). For each `__ALIAS`
   property, inject `<short>${G__GA__A__VERSION}</short>` for every
   listed short name the POM does not already declare. Maven builds
   the consumer POM from the transformed model, so the indirections
   travel with every installed or deployed POM, snapshot or release.
2. **Unresolved canonical references** (effective-model stage). A
   `<dependency>`, `<plugin>`, dependency-management or
   plugin-management `<version>` that still reads
   `${G__GA__A__VERSION}` fails the build, naming the property and the
   coordinate.
3. **Dot typos** (effective-model stage). An unresolved `${G.A}`
   whose dotted name matches a declared typed-marker property fails
   with the intended name.
4. **Release policies** (effective-model stage). Every `__POLICY`
   value must be one of `notify`, `verify`, `propose`, `integrate`,
   `release`; anything else fails with the closest rung.
5. **Reactor-root `<scm>`** (file-model stage). The POM whose
   directory holds the `.git` entry must declare a local `<scm>`
   with a `<url>` or `<connection>`; the release cascade keys
   repositories by it
   ([#496](https://github.com/IKE-Network/ike-issues/issues/496)).
   An empty `.git` directory is a husk, not a repository root.

## Wiring

Every IKE working set carries the extension in the managed block of
its `.mvn/extensions.xml`, written by `ws:scaffold-init` and refreshed
by `ws:scaffold-publish` from the pin in `ike-platform`. Foundation
repositories that declare `__ALIAS` metadata register it by hand in
the same file. The version is a literal; Maven 4 does not interpolate
that file.

```xml
<extension>
    <groupId>network.ike.tooling</groupId>
    <artifactId>ike-version-management-extension</artifactId>
    <version>11</version>
</extension>
```

## Build and test

```bash
mvn install
```

The in-repo suite exercises each rule on Maven 4 API models. The
`scm-lint-*` invoker cases in `workspace-reactor-example` run the
released artifact inside a real Maven build.

## Scope

The extension enforces the convention and nothing else. Version
alignment, cascade walking and lint reports live in
`ike-workspace-maven-plugin` and `ike-maven-plugin`. Tracking:
[IKE-Network/ike-issues#470](https://github.com/IKE-Network/ike-issues/issues/470)
(the convention),
[#472](https://github.com/IKE-Network/ike-issues/issues/472) (this
artifact),
[#1094](https://github.com/IKE-Network/ike-issues/issues/1094)
(fleet-wide registration and the retirement of release-time alias
baking).
