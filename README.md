# ike-version-management-extension

`network.ike.tooling:ike-version-management-extension` — a Maven 4
build extension implementing the IKE GA·convention for version-
property naming (`${groupId·artifactId}`, U+00B7 MIDDLE DOT).

See [IKE-Network/ike-issues#470](https://github.com/IKE-Network/ike-issues/issues/470)
for the convention's parent epic and
[IKE-Network/ike-issues#472](https://github.com/IKE-Network/ike-issues/issues/472)
for this artifact's scope.

The canonical documentation is the Maven Site at
<https://ike.network/ike-version-management-extension/>. The
convention itself is documented in
[`IKE-VERSIONS.md`](https://github.com/IKE-Network/ike-tooling/blob/main/ike-build-standards/src/main/standards/IKE-VERSIONS.md)
in the `ike-tooling` repo.

## Build

```bash
mvn install
```

## Stability

This artifact does three things and never grows scope: alias
injection, hard-fail on unresolved `${G·A}` references, and
`${G.A}` typo detection. Future version-management features ship
as separate extensions or as goals in
`ike-workspace-maven-plugin`.
