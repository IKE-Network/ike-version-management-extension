# ike-version-management-extension

[![Maven Central](https://img.shields.io/maven-central/v/network.ike.tooling/ike-version-management-extension)](https://central.sonatype.com/artifact/network.ike.tooling/ike-version-management-extension)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Documentation](https://img.shields.io/badge/docs-ike.network%2Fike--version--management--extension-blue)](https://ike.network/ike-version-management-extension/)
[![IKE Network](https://img.shields.io/badge/IKE-Network-green)](https://ike.network/)

`network.ike.tooling:ike-version-management-extension` — a Maven 4
build extension implementing the IKE typed-marker family for
version-property naming. A version pin is named
`${groupId__GA__artifactId__VERSION}`; a release policy for the
same coordinate is `${groupId__GA__artifactId__POLICY}`. The
extension also accepts the legacy U+00B7 form (`${groupId·artifactId}`)
during the transition. See
[IKE-Network/ike-issues#525](https://github.com/IKE-Network/ike-issues/issues/525)
for the migration plan.

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
injection, hard-fail on unresolved canonical (`${G__GA__A}` /
`${G·A}`) references, and `${G.A}` typo detection. Future
version-management features ship as separate extensions or as
goals in `ike-workspace-maven-plugin`.
