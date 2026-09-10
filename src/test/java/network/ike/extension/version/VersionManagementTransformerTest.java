package network.ike.extension.version;

import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginManagement;
import org.apache.maven.api.model.Scm;
import org.apache.maven.api.spi.ModelTransformerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each rule of {@link VersionManagementTransformer} on Maven 4 API
 * models: alias injection at the file-model stage; the
 * unresolved-canonical, dot-typo and {@code __POLICY} checks at the
 * effective-model stage; the reactor-root {@code <scm>} rule and the
 * git-entry detection behind it. The workspace guard is exercised on
 * both stages.
 */
class VersionManagementTransformerTest {

    private static final String JUNIT_PIN = "org.junit.jupiter__GA__junit-jupiter__VERSION";
    private static final String JUNIT_ALIAS = "org.junit.jupiter__GA__junit-jupiter__ALIAS";

    @TempDir
    Path tmp;

    private final VersionManagementTransformer transformer = new VersionManagementTransformer();

    // ── fixtures ──────────────────────────────────────────────────

    /** A directory the extension treats as its own workspace. */
    private Path workspace() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(ws.resolve(".mvn"));
        Files.writeString(ws.resolve(".mvn/extensions.xml"), "<extensions/>",
                StandardCharsets.UTF_8);
        return ws;
    }

    /** A real git entry: {@code .git/HEAD}. */
    private static void gitRoot(Path dir) throws IOException {
        Path git = Files.createDirectories(dir.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n", StandardCharsets.UTF_8);
    }

    private static Model.Builder model(Path dir, Map<String, String> properties) {
        return Model.newBuilder()
                .groupId("network.ike.test")
                .artifactId("subject")
                .version("1")
                .pomFile(dir.resolve("pom.xml"))
                .properties(properties);
    }

    private static Map<String, String> props(String... nameValuePairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            map.put(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return map;
    }

    private static Dependency dependency(String version) {
        return Dependency.newBuilder()
                .groupId("org.example").artifactId("lib").version(version).build();
    }

    private static Plugin plugin(String version) {
        return Plugin.newBuilder()
                .groupId("org.example").artifactId("lib-maven-plugin").version(version).build();
    }

    private static Scm scmWithUrl() {
        return Scm.newBuilder().url("https://github.com/IKE-Network/subject").build();
    }

    private String effectiveFailure(Model model) {
        ModelTransformerException e = assertThrows(ModelTransformerException.class,
                () -> transformer.transformEffectiveModel(model));
        return e.getMessage();
    }

    private String fileFailure(Model model) {
        ModelTransformerException e = assertThrows(ModelTransformerException.class,
                () -> transformer.transformFileModel(model));
        return e.getMessage();
    }

    // ── rule 1: alias injection ───────────────────────────────────

    @Test
    void injectsOneIndirectionPerAliasShortName() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props(
                JUNIT_PIN, "6.0.0",
                JUNIT_ALIAS, "junit-jupiter.version, junit.version")).build();

        Model out = transformer.transformFileModel(in);

        Map<String, String> p = out.getProperties();
        assertEquals("${" + JUNIT_PIN + "}", p.get("junit-jupiter.version"));
        assertEquals("${" + JUNIT_PIN + "}", p.get("junit.version"));
        assertEquals("6.0.0", p.get(JUNIT_PIN), "declared properties are preserved");
        assertEquals("junit-jupiter.version, junit.version", p.get(JUNIT_ALIAS),
                "the __ALIAS metadata itself stays");
    }

    @Test
    void leavesAShortNameTheFileModelAlreadyDeclares() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props(
                JUNIT_PIN, "6.0.0",
                JUNIT_ALIAS, "junit-jupiter.version,junit.version",
                "junit.version", "5.11.0")).build();

        Model out = transformer.transformFileModel(in);

        assertEquals("5.11.0", out.getProperties().get("junit.version"),
                "the author's own value stands");
        assertEquals("${" + JUNIT_PIN + "}", out.getProperties().get("junit-jupiter.version"));
    }

    @Test
    void returnsTheSameModelWhenNothingNeedsInjecting() throws Exception {
        Path ws = workspace();
        Model noAlias = model(ws, props(JUNIT_PIN, "6.0.0")).build();
        assertSame(noAlias, transformer.transformFileModel(noAlias));

        Model blankAlias = model(ws, props(JUNIT_PIN, "6.0.0", JUNIT_ALIAS, "  ")).build();
        assertSame(blankAlias, transformer.transformFileModel(blankAlias));
    }

    @Test
    void ignoresAPomOutsideTheRegisteringWorkspace() throws Exception {
        Path elsewhere = Files.createDirectories(tmp.resolve("m2-cache"));
        Model in = model(elsewhere, props(JUNIT_ALIAS, "junit.version")).build();

        assertSame(in, transformer.transformFileModel(in));
    }

    // ── rule 2: unresolved canonical references ───────────────────

    @Test
    void failsOnAnUndeclaredCanonicalReferenceInADependency() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props())
                .dependencies(List.of(dependency("${org.example__GA__lib__VERSION}")))
                .build();

        String message = effectiveFailure(in);

        assertTrue(message.contains("[UNRESOLVED] dependency org.example:lib"), message);
        assertTrue(message.contains("${org.example__GA__lib__VERSION} is not declared"), message);
        assertTrue(message.contains("IKE-Network/ike-issues#525"), message);
    }

    @Test
    void passesWhenTheCanonicalPropertyIsDeclared() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props("org.example__GA__lib__VERSION", "2.1"))
                .dependencies(List.of(dependency("${org.example__GA__lib__VERSION}")))
                .build();

        assertSame(in, transformer.transformEffectiveModel(in));
    }

    @Test
    void scansDependencyManagementPluginsAndPluginManagement() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props())
                .dependencyManagement(DependencyManagement.newBuilder()
                        .dependencies(List.of(dependency("${a__GA__managed__VERSION}")))
                        .build())
                .build(Build.newBuilder()
                        .plugins(List.of(plugin("${a__GA__live__VERSION}")))
                        .pluginManagement(PluginManagement.newBuilder()
                                .plugins(List.of(plugin("${a__GA__managed-plugin__VERSION}")))
                                .build())
                        .build())
                .build();

        String message = effectiveFailure(in);

        assertTrue(message.contains("3 convention violations"), message);
        assertTrue(message.contains("[UNRESOLVED] dependencyManagement.dependency org.example:lib"), message);
        assertTrue(message.contains("[UNRESOLVED] build.plugin org.example:lib-maven-plugin"), message);
        assertTrue(message.contains("[UNRESOLVED] build.pluginManagement.plugin org.example:lib-maven-plugin"),
                message);
    }

    @Test
    void stillRecognisesTheLegacyMiddleDotForm() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props())
                .dependencies(List.of(dependency("${org.example·lib}")))
                .build();

        String message = effectiveFailure(in);

        assertTrue(message.contains("[UNRESOLVED] dependency org.example:lib"), message);
    }

    @Test
    void ignoresAnEffectiveModelOutsideTheRegisteringWorkspace() throws Exception {
        Path elsewhere = Files.createDirectories(tmp.resolve("m2-cache"));
        Model in = model(elsewhere, props())
                .dependencies(List.of(dependency("${org.example__GA__lib__VERSION}")))
                .build();

        assertSame(in, transformer.transformEffectiveModel(in));
    }

    // ── rule 3: dot typos ─────────────────────────────────────────

    @Test
    void suggestsTheDeclaredCanonicalFormForADottedTypo() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props(JUNIT_PIN, "6.0.0"))
                .dependencies(List.of(dependency("${org.junit.jupiter.junit-jupiter}")))
                .build();

        String message = effectiveFailure(in);

        assertTrue(message.contains("[TYPO] dependency org.example:lib"), message);
        assertTrue(message.contains("Did you mean ${" + JUNIT_PIN + "}?"), message);
    }

    @Test
    void suggestsTheBareGaFormWhenThatIsWhatIsDeclared() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props("org.junit.jupiter__GA__junit-jupiter", "6.0.0"))
                .dependencies(List.of(dependency("${org.junit.jupiter.junit-jupiter}")))
                .build();

        String message = effectiveFailure(in);

        assertTrue(message.contains("Did you mean ${org.junit.jupiter__GA__junit-jupiter}?"), message);
    }

    @Test
    void suggestsTheLegacyFormAsATransitionFallback() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props("org.junit.jupiter·junit-jupiter", "6.0.0"))
                .dependencies(List.of(dependency("${org.junit.jupiter.junit-jupiter}")))
                .build();

        String message = effectiveFailure(in);

        assertTrue(message.contains("Did you mean ${org.junit.jupiter·junit-jupiter}?"), message);
    }

    @Test
    void leavesADottedPropertyAloneWhenNoCandidateIsDeclared() throws Exception {
        Path ws = workspace();
        // Dots are legal in property names; only a declared __GA__
        // candidate turns an unresolved dotted name into a typo.
        Model in = model(ws, props())
                .dependencies(List.of(dependency("${my.legacy.version}")))
                .build();

        assertSame(in, transformer.transformEffectiveModel(in));
    }

    // ── rule 4: release policies ──────────────────────────────────

    @Test
    void acceptsEveryReleasePolicyRung() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props(
                "a__GA__one__POLICY", "notify",
                "a__GA__two__POLICY", "verify",
                "a__GA__three__POLICY", "propose",
                "a__GA__four__POLICY", "integrate",
                "a__GA__five__POLICY", "release",
                "a__GA__unresolved__POLICY", "${some.reference}")).build();

        assertSame(in, transformer.transformEffectiveModel(in));
    }

    @Test
    void failsAMisspelledPolicyWithTheClosestRung() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props("a__GA__lib__POLICY", "integrat")).build();

        String message = effectiveFailure(in);

        assertTrue(message.contains("[INVALID-POLICY] property a__GA__lib__POLICY"), message);
        assertTrue(message.contains("Value \"integrat\" is not a release policy."), message);
        assertTrue(message.contains("Did you mean \"integrate\"?"), message);
        assertTrue(message.contains("notify, verify, propose, integrate, release"), message);
    }

    @Test
    void offersNoSuggestionWhenNothingIsClose() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props("a__GA__lib__POLICY", "whenever-you-like")).build();

        String message = effectiveFailure(in);

        assertTrue(message.contains("[INVALID-POLICY]"), message);
        assertFalse(message.contains("Did you mean"), message);
    }

    @Test
    void validatesTheLegacyPolicySuffixToo() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props("a·lib·policy", "integrat")).build();

        String message = effectiveFailure(in);

        assertTrue(message.contains("[INVALID-POLICY] property a·lib·policy"), message);
    }

    // ── rule 5: reactor-root <scm> ────────────────────────────────

    @Test
    void failsARepositoryRootWithoutALocalScm() throws Exception {
        Path ws = workspace();
        gitRoot(ws);
        Model in = model(ws, props()).build();

        String message = fileFailure(in);

        assertTrue(message.contains("[MISSING-SCM] project network.ike.test:subject:1"), message);
        assertTrue(message.contains("IKE-Network/ike-issues#496"), message);
    }

    @Test
    void passesARepositoryRootDeclaringUrlOrConnection() throws Exception {
        Path ws = workspace();
        gitRoot(ws);

        Model withUrl = model(ws, props()).scm(scmWithUrl()).build();
        assertSame(withUrl, transformer.transformFileModel(withUrl));

        Model withConnection = model(ws, props())
                .scm(Scm.newBuilder().connection("scm:git:git@github.com:IKE-Network/subject.git").build())
                .build();
        assertSame(withConnection, transformer.transformFileModel(withConnection));
    }

    @Test
    void doesNotRequireScmOnASubproject() throws Exception {
        Path ws = workspace();
        gitRoot(ws);
        Path sub = Files.createDirectories(ws.resolve("subproject"));
        Model in = model(sub, props()).build();

        assertSame(in, transformer.transformFileModel(in));
    }

    @Test
    void treatsAnEmptyGitDirectoryAsAHuskNotARoot() throws Exception {
        Path ws = workspace();
        gitRoot(ws);
        Path sub = Files.createDirectories(ws.resolve("subproject"));
        Files.createDirectories(sub.resolve(".git")); // synced husk, no HEAD inside
        Model in = model(sub, props()).build();

        assertSame(in, transformer.transformFileModel(in),
                "a husk must not move the <scm> requirement onto the subproject");
    }

    @Test
    void treatsAGitFileAsARepositoryRoot() throws Exception {
        Path ws = workspace();
        Files.writeString(ws.resolve(".git"), "gitdir: ../.git/worktrees/ws\n",
                StandardCharsets.UTF_8);
        Model in = model(ws, props()).build();

        String message = fileFailure(in);

        assertTrue(message.contains("[MISSING-SCM]"), message);
    }

    @Test
    void passesAPomWithNoGitAncestorAtAll() throws Exception {
        Path ws = workspace();
        Model in = model(ws, props()).build();

        assertSame(in, transformer.transformFileModel(in));
    }

    @Test
    void scmRuleRunsBeforeAliasInjection() throws Exception {
        Path ws = workspace();
        gitRoot(ws);
        Model in = model(ws, props(JUNIT_PIN, "6.0.0", JUNIT_ALIAS, "junit.version")).build();

        assertTrue(fileFailure(in).contains("[MISSING-SCM]"));
    }

    // ── git-entry detection ───────────────────────────────────────

    @Test
    void hasGitEntryRecognisesRealEntriesOnly() throws Exception {
        Path none = Files.createDirectories(tmp.resolve("none"));
        assertFalse(VersionManagementTransformer.hasGitEntry(none));

        Path husk = Files.createDirectories(tmp.resolve("husk"));
        Files.createDirectories(husk.resolve(".git"));
        assertFalse(VersionManagementTransformer.hasGitEntry(husk));

        Path real = Files.createDirectories(tmp.resolve("real"));
        gitRoot(real);
        assertTrue(VersionManagementTransformer.hasGitEntry(real));

        Path pointer = Files.createDirectories(tmp.resolve("pointer"));
        Files.writeString(pointer.resolve(".git"), "gitdir: elsewhere\n", StandardCharsets.UTF_8);
        assertTrue(VersionManagementTransformer.hasGitEntry(pointer));
    }

    @Test
    void aPomWithoutAFileIsOutsideEveryRule() throws Exception {
        Model in = Model.newBuilder().artifactId("synthetic")
                .properties(props(JUNIT_ALIAS, "junit.version")).build();

        assertNull(in.getPomFile());
        assertSame(in, transformer.transformFileModel(in));
        assertSame(in, transformer.transformEffectiveModel(in));
    }
}
