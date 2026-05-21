---
date_published: 1980-01-31
date_modified: 1980-01-31
canonical_url: https://github.com/IKE-Network/ike-version-management-extension/index.html
---

# IKE Version Management Extension

[https://central.sonatype.com/artifact/network.ike.tooling/ike-version-management-extension](https://central.sonatype.com/artifact/network.ike.tooling/ike-version-management-extension)[1]

`network.ike.tooling:ike-version-management-extension` is a Maven 4 build extension that implements the IKE GA·convention for version-property naming: every property that pins an artifact version is named `${groupId·artifactId}` (U+00B7 MIDDLE DOT) with the version as its value.

## [#what-it-does](#what-it-does)What it does

When registered in a workspace’s `.mvn/extensions.xml`, the extension hooks Maven 4’s `ModelTransformer` SPI to:

1. **Inject alias indirections** at file-model stage. The bundled manifest (`META-INF/ike/version-aliases.yaml`) maps legacy short-name property idioms — `junit-jupiter.version`, `maven-compiler-plugin.version`, `ike-tooling.version`, etc. — to their canonical `${G·A}` form. When a consumer’s POM declares one side of an alias pair, the extension injects the other side as a one-line indirection, so both names resolve to the same value.
2. **Fail fast on unresolved canonical references** at effective-model stage. If a `<dependency>` or `<plugin>` `<version>` references `${G·A}` and no such property is declared (locally, by inheritance, or by alias injection), the build fails with an actionable error message naming the property, the artifact block, and the manifest sources consulted.
3. **Detect `${G.A}` typos** — the common mistake of typing regular periods where the middle dot was meant. If replacing every period in an unresolved property name with `·` produces a name that IS declared, the build fails with a "did you mean `${G·A}`?" hint.

A project-local `.mvn/version-aliases.yaml` can override or extend the bundled defaults. Anything declared project-local takes precedence per-key over the bundled manifest.

See [IKE-VERSIONS.md](https://github.com/IKE-Network/ike-tooling/blob/main/ike-build-standards/src/main/standards/IKE-VERSIONS.md)[2] for the full convention specification.

## [#stability-commitment](#stability-commitment)Stability commitment

This artifact does exactly the three things above. It never grows scope. Future version-management features (alignment, cascade integration, lint) ship as goals in `ike-workspace-maven-plugin` or as separate extensions. The extension’s dependency surface stays at the Maven 4 API jars (compile scope, loaded into the extension classloader). Manifest parsing is hand-rolled to keep the artifact dependency-free.

Versioned independently of the [ike-platform](https://github.com/IKE-Network/ike-platform)[3] release cadence. If different behavior is ever needed, a separate extension is added rather than evolving this one.

## [#wiring](#wiring)Wiring

Declared in a workspace’s `.mvn/extensions.xml` alongside the existing IKE workspace extension:

```
<extensions>
    <extension>
        <groupId>network.ike.tooling</groupId>
        <artifactId>ike-workspace-extension</artifactId>
        <version>4</version>
    </extension>
    <extension>
        <groupId>network.ike.tooling</groupId>
        <artifactId>ike-version-management-extension</artifactId>
        <version>1</version>
    </extension>
</extensions>
```

`ws:scaffold-init` writes both entries into new workspaces. `ws:scaffold-publish` keeps them in sync. Versions are sourced from properties declared in `ike-parent` — operators do not maintain them by hand.

## [#coordinates](#coordinates)Coordinates

```
<dependency>
    <groupId>network.ike.tooling</groupId>
    <artifactId>ike-version-management-extension</artifactId>
    <version>1</version>
</dependency>
```

Available from Maven Central.

## [#tracking](#tracking)Tracking

- [IKE-Network/ike-issues#470](https://github.com/IKE-Network/ike-issues/issues/470)[4] — the parent epic.
- [IKE-Network/ike-issues#471](https://github.com/IKE-Network/ike-issues/issues/471)[5] — IKE-VERSIONS.md, the convention standard.
- [IKE-Network/ike-issues#472](https://github.com/IKE-Network/ike-issues/issues/472)[6] — this extension.
- [IKE-Network/ike-issues#473](https://github.com/IKE-Network/ike-issues/issues/473)[7] — `ike-base-parent` ships the alias manifest.
