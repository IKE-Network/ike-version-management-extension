---
date_published: 1980-01-31
date_modified: 1980-01-31
canonical_url: https://ike.network/ike-version-management-extension/index.html
---

# IKE Version Management Extension

[https://central.sonatype.com/artifact/network.ike.tooling/ike-version-management-extension](https://central.sonatype.com/artifact/network.ike.tooling/ike-version-management-extension)[1]

`network.ike.tooling:ike-version-management-extension` is a Maven 4 build extension that enforces the IKE typed-marker convention for version properties. A version pin is named `${groupId*GA*artifactId*VERSION}*`*, a release policy for the same coordinate is `${groupId`*`GA*artifactId*POLICY}`, and the legacy short names an artifact also goes by are listed in `${groupId*GA*artifactId*ALIAS}*`*. The rule: ``* separates adjacent name parts, `GA` marks the boundary between groupId and artifactId, and the terminal marker names the facet. The XML terminator (`>` for declarations, `}` for references) closes the last segment, so a name never ends in `__`.

The legacy U+00B7 form (`${groupId·artifactId}`) is still read during the transition. See [#525](https://github.com/IKE-Network/ike-issues/issues/525)[2] for the change and its rationale.

## [#what-it-does](#what-it-does)What it does

Registered in a repository’s `.mvn/extensions.xml`, the extension hooks Maven 4’s `ModelTransformer` SPI and applies five rules to every POM under that directory. A POM resolved from a repository — anything under the local repository cache — passes through untouched.

1. **Alias injection** (file-model stage). For each `*ALIAS*`* property, carrying a comma-separated list of legacy short names, the extension injects `<short>${G`*`GA*A*VERSION}</short>` for every short name the POM does not already declare. Maven builds the consumer POM from the transformed model, so the indirections travel with every installed or deployed POM, snapshot or release, and descendants resolve the short names through ordinary inheritance. The release flow checks that the consumer POM holds them before tagging ([#1094](https://github.com/IKE-Network/ike-issues/issues/1094)[3]).
2. **Unresolved canonical references** (effective-model stage). A `<dependency>`, `<plugin>`, `<dependencyManagement>` or `<pluginManagement>` `<version>` that still reads `${G*GA*A__VERSION}` (or the legacy `${G·A}`) after interpolation fails the build. The message names the property, the section and the coordinate.
3. **Dot typos** (effective-model stage). An unresolved `${G.A}` is checked at every dot, right to left, against three candidates: `*GA*` in place of the dot, the same with `__VERSION` appended, and the legacy `·`. If one is declared, the build fails with "did you mean `${…}`?". A dotted name with no declared candidate is left alone; dots are legal in property names.
4. **Release policies** (effective-model stage). Every property ending in `__POLICY` (or the legacy `·policy`) must hold one of `notify`, `verify`, `propose`, `integrate`, `release`. Any other value fails with the valid set and the closest rung.
5. **Reactor-root `<scm>`** (file-model stage). The POM whose directory holds the `.git` entry must declare a local `<scm>` with a `<url>` or `<connection>`. The release cascade keys repositories by that block, and Maven’s default `<scm>` inheritance would append the artifactId to the parent’s URL, so inheriting it is not enough ([#496](https://github.com/IKE-Network/ike-issues/issues/496)[4]). Subprojects inherit and are not checked. A `.git` entry counts only when it is a file — the gitdir pointer of a worktree or submodule — or a directory holding `HEAD`; an empty `.git` directory left in a synced subproject is a husk, not a repository root.

Reports go through Maven’s logger. A rule that fails names every violation in one message, each with the fix.

## [#wiring](#wiring)Wiring

Every IKE working set carries the extension in the managed block of its `.mvn/extensions.xml`, beside `ike-workspace-extension` and `ike-build-report-extension`. `ws:scaffold-init` writes the block and `ws:scaffold-publish` refreshes it from the pin `network.ike.tooling*GA*ike-version-management-extension*VERSION*`* in `ike-platform`. Foundation repositories that declare ``*`ALIAS` metadata — `ike-base-parent`, `ike-platform`, `ike-lease-plugin` — register it by hand in the same file. The version is a literal; Maven 4 does not interpolate `.mvn/extensions.xml`.

```
<extensions>
    <extension>
        <groupId>network.ike.tooling</groupId>
        <artifactId>ike-version-management-extension</artifactId>
        <version>11</version>
    </extension>
</extensions>
```

## [#build-and-test](#build-and-test)Build and test

```
mvn install
```

The in-repo suite exercises each rule on Maven 4 API models built through the API builders. The `scm-lint-*` invoker cases in `workspace-reactor-example/integration-tests-example` run the released artifact inside a real Maven build.

## [#scope](#scope)Scope

The extension enforces the convention and nothing else. Version alignment (`ws:align`), cascade walking (`ike:release-cascade`) and lint reports (`ws:scaffold-draft`) live in `ike-workspace-maven-plugin` and `ike-maven-plugin`. The dependency surface is the Maven 4 API jars at compile scope, `ike-java-support` for the shared value types, and SLF4J at provided scope — the core realm supplies it.

Versioned independently of the [ike-platform](https://github.com/IKE-Network/ike-platform)[5] release cadence.

## [#tracking](#tracking)Tracking

- [IKE-Network/ike-issues#470](https://github.com/IKE-Network/ike-issues/issues/470)[6] — the convention.
- [IKE-Network/ike-issues#471](https://github.com/IKE-Network/ike-issues/issues/471)[7] — IKE-VERSIONS.md, the standard.
- [IKE-Network/ike-issues#472](https://github.com/IKE-Network/ike-issues/issues/472)[8] — this artifact.
- [IKE-Network/ike-issues#525](https://github.com/IKE-Network/ike-issues/issues/525)[2] — the typed-marker family.
- [IKE-Network/ike-issues#526](https://github.com/IKE-Network/ike-issues/issues/526)[9] — `__ALIAS` metadata.
- [IKE-Network/ike-issues#1094](https://github.com/IKE-Network/ike-issues/issues/1094)[3] — fleet-wide registration; retirement of release-time alias baking.
