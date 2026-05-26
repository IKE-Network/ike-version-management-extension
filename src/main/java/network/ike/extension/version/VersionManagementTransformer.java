package network.ike.extension.version;

import network.ike.support.enums.ConstantBackedEnum;
import network.ike.support.enums.ReleasePolicy;
import network.ike.support.enums.TypedMarker;

import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginManagement;
import org.apache.maven.api.model.Scm;
import org.apache.maven.api.spi.ModelTransformer;
import org.apache.maven.api.spi.ModelTransformerException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven 4 build extension implementing the IKE typed-marker
 * family convention for version-property naming. Hooks Maven's
 * {@link ModelTransformer#transformFileModel(Model) file-model}
 * and {@link ModelTransformer#transformEffectiveModel(Model)
 * effective-model} stages to inject alias indirections and to
 * fail fast on convention violations.
 *
 * <p>The convention's rule: {@code __} is a separator between
 * adjacent name-parts in a property name; trailing {@code __} only
 * appears when something follows. A version-pin property is named
 * {@code <G>__GA__<A>__VERSION} (the {@code GA} marker separates
 * the groupId from the artifactId, the terminal {@code VERSION}
 * marker identifies the facet, and the XML structural terminator
 * — {@code >} for declarations, {@code }} for references — closes
 * the final segment). The release-policy facet uses
 * {@code <G>__GA__<A>__POLICY}. See {@code IKE-VERSIONS.md} in
 * the {@code ike-build-standards} module and
 * IKE-Network/ike-issues#470, #471, #472, #525 for the full
 * standard.
 *
 * <p>The transformer recognizes both the typed-marker forms and
 * the legacy U+00B7 ({@code ·}) forms during the transition
 * period — old POMs still resolve, but suggestions and error
 * messages point at the typed-marker form. See
 * IKE-Network/ike-issues#525 for the migration plan; the legacy
 * forms are removed in a future major.
 *
 * <h2>What this transformer does</h2>
 *
 * <h3>1. Alias injection (file-model stage)</h3>
 *
 * <p>For each alias {@code short → canonical} in the merged
 * {@link ManifestLoader manifest}: if the consumer's POM declares
 * {@code short} but not {@code canonical}, inject
 * {@code <canonical>${short}</canonical>} so canonical-form
 * references resolve to the same value. The mirror also holds —
 * if {@code canonical} is declared but {@code short} is not,
 * inject {@code <short>${canonical}</short>}. If neither is
 * declared, the POM is not modified (we do not inject the
 * manifest's default value over an absent property, which would
 * otherwise leak the IKE manifest into POMs that don't reference
 * any of its pins).
 *
 * <h3>2. Hard failure on unresolved canonical references
 * (effective-model stage)</h3>
 *
 * <p>If a {@code <dependency>} or {@code <plugin>} {@code <version>}
 * still contains a literal {@code ${...}} after effective-model
 * interpolation, and the property name contains the {@code __GA__}
 * typed-marker (or the legacy {@code ·}), the build fails with a
 * {@link ModelTransformerException} naming the property, the
 * artifact, and the manifest paths consulted.
 *
 * <h3>3. Typo detection (effective-model stage)</h3>
 *
 * <p>If an unresolved {@code ${X}} reference contains regular
 * periods, the transformer tries three corrections at each dot
 * position (right-to-left): substitute the dot with
 * {@code __GA__}; same plus a {@code __VERSION} terminal facet;
 * substitute with the legacy {@code ·}. If any candidate is
 * declared in the effective model, the build fails with a "did
 * you mean {@code ${G__GA__A__VERSION}}?" hint pointing at the
 * resolved form.
 *
 * <h3>4. Release-policy validation (effective-model stage)</h3>
 *
 * <p>A project declares how it responds to an upstream release with
 * a {@code ${G__GA__A__POLICY}} property whose value is a
 * {@link ReleasePolicy} rung (legacy {@code ${G·A·policy}} is also
 * accepted). For every effective-model property whose name ends in
 * {@code __POLICY} or {@code ·policy}, the transformer checks that
 * the value is one of {@code notify}, {@code verify},
 * {@code propose}, {@code integrate}, {@code release}; an
 * unrecognized value fails the build with the valid set and a
 * closest-match "did you mean" hint.
 *
 * <h3>5. Reactor-root SCM declaration (file-model stage)</h3>
 *
 * <p>The release cascade keys repositories by {@code <scm>}: every
 * coordinate a reactor produces collapses onto a single cascade node
 * via the reactor-root's {@code <scm>}. To map a consumed coordinate
 * back to its producing repository, the cascade walks to the POM's
 * {@code .git} ancestor and reads <em>that</em> POM's {@code <scm>}.
 * Subproject {@code <scm>} declarations are not consulted — the
 * git-boundary read in {@code SiblingRepositoryKeyResolver}
 * (ike-tooling/ike-workspace-model) bypasses Maven's default
 * {@code <scm>} inheritance, which appends each subproject's
 * {@code <artifactId>} to the parent URL and produces non-existent
 * paths like {@code .../ike-base-parent/<child>}.
 *
 * <p>The lint therefore fires on a single POM per repository: the one
 * whose project directory contains the {@code .git} entry. That POM
 * — the reactor root in IKE convention — must declare a local
 * {@code <scm>} with a {@code <url>} or {@code <connection>}.
 * Subproject POMs in the same git repository skip the check and
 * inherit normally; standalone POMs (no {@code .git} ancestor we can
 * identify) are outside the lint's domain and pass through. The
 * check runs at the file-model stage — before parent merging — so
 * an absent local block on the reactor root cannot be masked by
 * inheritance from {@code ike-base-parent}. See
 * IKE-Network/ike-issues#496.
 *
 * <h2>Scope</h2>
 *
 * <p>The transformer enforces the IKE version and release-policy
 * conventions. It grew from three rules to five when release-policy
 * validation (#498) and the cascade's {@code <scm>}-presence
 * requirement (#496) were added for the release-cascade redesign;
 * further convention checks are added here as the conventions
 * themselves grow.
 */
@Named("ike-version-management")
@Singleton
public class VersionManagementTransformer implements ModelTransformer {

    /** Matches a single {@code ${...}} property reference. */
    private static final Pattern PROPERTY_REF = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * Legacy GA separator (U+00B7 MIDDLE DOT), kept for
     * transition-period detection. vm-ext v6+ recognizes both
     * {@link TypedMarker#GA the typed-marker family} (preferred) and
     * this legacy form in consumer POMs. Will be removed in a future
     * major version after the foundation cascade has migrated to the
     * typed-marker family. See IKE-Network/ike-issues#525.
     *
     * <p>The typed-marker family lives in {@link TypedMarker} — a
     * closed enum the compiler enforces. The legacy form is a plain
     * String here because it has no place in the enum (it's the
     * thing being replaced).
     */
    private static final String LEGACY_GA_SEPARATOR = "·";

    /** Legacy policy suffix ({@code ·policy}), kept for transition. */
    private static final String LEGACY_POLICY_SUFFIX = "·policy";

    /**
     * Path-tail marker identifying the workspace that registered
     * this extension. A POM is "in our workspace" if a {@code .mvn/}
     * directory containing an {@code extensions.xml} exists at or
     * above its location.
     */
    private static final String MVN_EXTENSIONS = ".mvn/extensions.xml";

    /** Creates the transformer. DI-only; not for direct construction. */
    public VersionManagementTransformer() {}

    /**
     * Injects alias indirections and enforces the reactor-root
     * {@code <scm>} requirement. See class Javadoc for the rules.
     *
     * @param model the file-stage parsed Maven model — the raw POM
     *              before parent merging, so {@link Model#getScm()}
     *              returns the locally-declared block (or {@code null})
     *              rather than the inherited one
     * @return the (possibly augmented) model — same instance when
     *         no aliases needed injection
     * @throws ModelTransformerException if this POM sits at the
     *                                    {@code .git} repository root
     *                                    and declares no local
     *                                    {@code <scm>} with a
     *                                    {@code <url>} or
     *                                    {@code <connection>}
     */
    @Override
    public Model transformFileModel(Model model) throws ModelTransformerException {
        Path projectDir = projectDirOf(model);
        if (!isInOurWorkspace(projectDir)) {
            return model;
        }
        List<Violation> violations = new ArrayList<>();
        scanScm(model, violations);
        if (!violations.isEmpty()) {
            throw new ModelTransformerException(buildErrorMessage(model, violations));
        }
        AliasManifest manifest = ManifestLoader.load(projectDir);
        Map<String, String> props = model.getProperties();
        if (props == null) {
            props = Collections.emptyMap();
        }
        Map<String, String> injected = computeAliasInjections(props, manifest);
        if (injected.isEmpty()) {
            return model;
        }
        Map<String, String> merged = new LinkedHashMap<>(props);
        merged.putAll(injected);
        System.err.println("[ike-version-management-extension] injected "
                + injected.size() + " alias " + (injected.size() == 1 ? "indirection" : "indirections")
                + " into " + describe(model) + ": " + injected.keySet());
        return model.withProperties(merged);
    }

    /**
     * Scans the effective model for convention violations —
     * unresolved {@code ${...}} version references and unrecognized
     * release-policy values — and fails the build with an actionable
     * message if any are found. The {@code <scm>}-presence rule
     * runs at the file-model stage; see
     * {@link #transformFileModel(Model)}.
     *
     * @param model the effective Maven model (post-inheritance,
     *              post-interpolation)
     * @return the model unchanged
     * @throws ModelTransformerException if an unresolved
     *                                    {@code ${G__GA__A}}, a
     *                                    {@code ${G.A}} typo, or an
     *                                    invalid release policy is
     *                                    detected
     */
    @Override
    public Model transformEffectiveModel(Model model) throws ModelTransformerException {
        if (!isInOurWorkspace(projectDirOf(model))) {
            return model;
        }
        Map<String, String> props = model.getProperties();
        if (props == null) {
            props = Collections.emptyMap();
        }
        List<Violation> violations = new ArrayList<>();
        scanDependencies(model.getDependencies(), "dependency", props, violations);
        DependencyManagement dm = model.getDependencyManagement();
        if (dm != null) {
            scanDependencies(dm.getDependencies(), "dependencyManagement.dependency", props, violations);
        }
        if (model.getBuild() != null) {
            scanPlugins(model.getBuild().getPlugins(), "build.plugin", props, violations);
            PluginManagement pm = model.getBuild().getPluginManagement();
            if (pm != null) {
                scanPlugins(pm.getPlugins(), "build.pluginManagement.plugin", props, violations);
            }
        }
        scanPolicyProperties(props, violations);
        if (violations.isEmpty()) {
            return model;
        }
        throw new ModelTransformerException(buildErrorMessage(model, violations));
    }

    private static Map<String, String> computeAliasInjections(
            Map<String, String> existing, AliasManifest manifest) {
        Map<String, String> injected = new LinkedHashMap<>();
        for (Map.Entry<String, String> alias : manifest.aliases().entrySet()) {
            String shortName = alias.getKey();
            String canonical = alias.getValue();
            boolean hasShort = existing.containsKey(shortName);
            boolean hasCanonical = existing.containsKey(canonical);
            if (hasShort && !hasCanonical) {
                // Consumer pinned the legacy name; mirror to canonical.
                injected.put(canonical, "${" + shortName + "}");
            } else if (hasCanonical && !hasShort) {
                // Consumer pinned the canonical name; mirror to legacy
                // so transitive deps using the short-name idiom still
                // resolve.
                injected.put(shortName, "${" + canonical + "}");
            }
        }
        // Sanity: never inject a property that is somehow already
        // declared (covers the edge case where alias chains overlap).
        injected.keySet().removeAll(existing.keySet());
        return injected;
    }

    private static void scanDependencies(List<Dependency> deps, String section,
                                          Map<String, String> props, List<Violation> out) {
        if (deps == null) {
            return;
        }
        for (Dependency d : deps) {
            String v = d.getVersion();
            if (v == null) {
                continue;
            }
            scanVersionString(v, props, section, d.getGroupId() + ":" + d.getArtifactId(), out);
        }
    }

    private static void scanPlugins(List<Plugin> plugins, String section,
                                     Map<String, String> props, List<Violation> out) {
        if (plugins == null) {
            return;
        }
        for (Plugin p : plugins) {
            String v = p.getVersion();
            if (v == null) {
                continue;
            }
            scanVersionString(v, props, section, p.getGroupId() + ":" + p.getArtifactId(), out);
        }
    }

    private static void scanVersionString(String version, Map<String, String> props,
                                           String section, String coords, List<Violation> out) {
        Matcher m = PROPERTY_REF.matcher(version);
        while (m.find()) {
            String name = m.group(1);
            if (props.containsKey(name)) {
                // Property is declared; the literal ${X} in the
                // version field is a Model-view artifact, not an
                // unresolved reference.
                continue;
            }
            if (name.contains(TypedMarker.GA.token()) || name.contains(LEGACY_GA_SEPARATOR)) {
                out.add(new Violation(ViolationKind.UNRESOLVED_CANONICAL, name, section, coords, null));
                continue;
            }
            if (name.indexOf('.') >= 0) {
                String suggestion = findDotTypoSuggestion(name, props);
                if (suggestion != null) {
                    out.add(new Violation(ViolationKind.DOT_TYPO, name, section, coords, suggestion));
                }
            }
        }
    }

    /**
     * Looks for a typed-marker property that would resolve if a
     * single {@code .} in {@code name} were instead the IKE GA
     * separator. Tries every dot position (right-to-left, since
     * the most common typo is the last dot before the artifactId),
     * checking three candidates per position in order:
     * <ol>
     *   <li>{@code <prefix>__GA__<suffix>} — direct typed-marker
     *       substitution
     *   <li>{@code <prefix>__GA__<suffix>__VERSION} — same with
     *       the version facet appended (handles "typed the GA but
     *       forgot the facet marker")
     *   <li>{@code <prefix>·<suffix>} — legacy U+00B7 substitution
     *       (transition fallback for projects still on the old
     *       convention)
     * </ol>
     * Returns the first declared candidate, or {@code null} if no
     * substitution resolves.
     */
    private static String findDotTypoSuggestion(String name, Map<String, String> props) {
        // Walk dot positions right-to-left; the typo is usually the
        // last dot before the artifactId, so this finds the
        // most-likely suggestion first.
        for (int i = name.lastIndexOf('.'); i >= 0; i = name.lastIndexOf('.', i - 1)) {
            String prefix = name.substring(0, i);
            String suffix = name.substring(i + 1);
            // 1. Direct typed-marker substitution
            String withMarker = prefix + TypedMarker.GA.token() + suffix;
            if (props.containsKey(withMarker)) {
                return withMarker;
            }
            // 2. Typed-marker + __VERSION facet (user typed the GA
            //    but omitted the terminal facet marker)
            String withMarkerAndVersion = withMarker + TypedMarker.VERSION.token();
            if (props.containsKey(withMarkerAndVersion)) {
                return withMarkerAndVersion;
            }
            // 3. Legacy U+00B7 substitution (transition fallback)
            String legacy = prefix + LEGACY_GA_SEPARATOR + suffix;
            if (props.containsKey(legacy)) {
                return legacy;
            }
        }
        return null;
    }

    /**
     * Scans effective-model properties for release-policy
     * declarations — names ending in {@code __POLICY} (typed-marker
     * family) or {@code ·policy} (legacy U+00B7 form, accepted for
     * transition) — and records a violation for any whose value is
     * not a {@link ReleasePolicy} rung.
     */
    private static void scanPolicyProperties(Map<String, String> props, List<Violation> out) {
        Map<String, ReleasePolicy> policies = ConstantBackedEnum.index(ReleasePolicy.class);
        for (Map.Entry<String, String> entry : props.entrySet()) {
            String key = entry.getKey();
            if (key == null
                    || (!key.endsWith(TypedMarker.POLICY.token()) && !key.endsWith(LEGACY_POLICY_SUFFIX))) {
                continue;
            }
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            value = value.trim();
            if (value.isEmpty() || value.contains("${")) {
                // Empty, or an unresolved reference — not a literal
                // policy value; leave it to other diagnostics.
                continue;
            }
            if (!policies.containsKey(value)) {
                out.add(new Violation(ViolationKind.INVALID_POLICY, value,
                        "property", key, closestPolicy(value)));
            }
        }
    }

    /**
     * Records a violation when the POM at a git repository's root has
     * no locally-declared {@code <scm>} with at least a {@code <url>}
     * or {@code <connection>}. Subproject POMs — those whose project
     * directory is a descendant of the nearest {@code .git} ancestor,
     * not the ancestor itself — skip the check; the IKE convention
     * has them inherit {@code <scm>} from the reactor root.
     *
     * <p>Inheritance is deliberately not consulted for the root's own
     * check: Maven's default {@code <scm>} inheritance appends the
     * child's {@code artifactId} to the parent URL, so a reactor root
     * that "inherits" {@code <scm>} from {@code ike-base-parent}
     * resolves to a non-existent path like
     * {@code .../ike-base-parent/<root-artifactId>}. The cascade's
     * coordinate-to-repository join key reads the reactor-root POM's
     * {@code <scm>} directly (see {@code SiblingRepositoryKeyResolver}
     * in ike-tooling/ike-workspace-model), which only a local
     * declaration on the root populates. POMs with no identifiable
     * {@code .git} ancestor — synthetic test models, ad-hoc local
     * POMs outside any checkout — are outside the lint's domain and
     * pass through. See IKE-Network/ike-issues#496.
     */
    private static void scanScm(Model model, List<Violation> out) {
        if (!isAtGitRepoRoot(projectDirOf(model))) {
            return;
        }
        Scm scm = model.getScm();
        boolean hasLocalIdentity = scm != null
                && ((scm.getUrl() != null && !scm.getUrl().isBlank())
                    || (scm.getConnection() != null && !scm.getConnection().isBlank()));
        if (!hasLocalIdentity) {
            out.add(new Violation(ViolationKind.MISSING_SCM, null, "project",
                    describe(model), null));
        }
    }

    /**
     * Whether {@code projectDir} is the root of a git repository — the
     * directory that contains the {@code .git} entry. Returns
     * {@code false} when {@code projectDir} is {@code null}, when no
     * ancestor contains {@code .git}, or when the {@code .git} entry
     * is in some ancestor (meaning this POM is a subproject within a
     * larger git checkout).
     *
     * <p>The same algorithm is used by
     * {@code SiblingRepositoryKeyResolver} (ike-tooling/ike-workspace-model)
     * to derive the cascade's per-repository join key from the
     * reactor-root POM. Keeping the two in step ensures the lint
     * fires on exactly the POMs whose {@code <scm>} the cascade
     * actually reads.
     */
    private static boolean isAtGitRepoRoot(Path projectDir) {
        if (projectDir == null) {
            return false;
        }
        Path gitRoot = gitRoot(projectDir);
        return gitRoot != null && projectDir.equals(gitRoot);
    }

    /**
     * The nearest ancestor directory (including {@code start} itself)
     * that contains a {@code .git} entry, or {@code null} when none
     * exists at or above {@code start}. Matches the algorithm in
     * {@code SiblingRepositoryKeyResolver}.
     */
    private static Path gitRoot(Path start) {
        Path cursor = start;
        while (cursor != null) {
            if (Files.exists(cursor.resolve(".git"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    /**
     * The closest {@link ReleasePolicy} rung to {@code value} by edit
     * distance, or {@code null} when nothing is close enough to be a
     * plausible typo.
     */
    private static String closestPolicy(String value) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ReleasePolicy policy : ReleasePolicy.values()) {
            int distance = levenshtein(value, policy.literalName());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = policy.literalName();
            }
        }
        return bestDistance <= 3 ? best : null;
    }

    /** The comma-separated list of valid release-policy rungs. */
    private static String validPolicyList() {
        StringBuilder sb = new StringBuilder();
        for (ReleasePolicy policy : ReleasePolicy.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(policy.literalName());
        }
        return sb.toString();
    }

    /** Standard Levenshtein edit distance between two strings. */
    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitute = previous[j - 1]
                        + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                int delete = previous[j] + 1;
                int insert = current[j - 1] + 1;
                current[j] = Math.min(substitute, Math.min(delete, insert));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static String buildErrorMessage(Model model, List<Violation> violations) {
        Path projectDir = projectDirOf(model);
        StringBuilder sb = new StringBuilder();
        sb.append("ike-version-management-extension: ")
          .append(violations.size())
          .append(violations.size() == 1 ? " convention violation" : " convention violations")
          .append(" in ").append(describe(model)).append(":\n");
        for (Violation v : violations) {
            sb.append("\n  [").append(switch (v.kind()) {
                case UNRESOLVED_CANONICAL -> "UNRESOLVED";
                case DOT_TYPO -> "TYPO";
                case INVALID_POLICY -> "INVALID-POLICY";
                case MISSING_SCM -> "MISSING-SCM";
            }).append("] ")
              .append(v.section()).append(" ").append(v.coords()).append("\n");
            switch (v.kind()) {
                case UNRESOLVED_CANONICAL -> {
                    sb.append("    Property ${").append(v.name()).append("} is not declared.\n")
                      .append("    The IKE convention requires every ${groupId__GA__artifactId__VERSION}\n")
                      .append("    typed-marker property to be defined either locally, in an inherited\n")
                      .append("    parent POM (e.g., ike-base-parent), or in an alias manifest. The\n")
                      .append("    legacy U+00B7 form (${groupId·artifactId}) is also accepted during\n")
                      .append("    the transition. See IKE-Network/ike-issues#525.\n");
                }
                case DOT_TYPO -> {
                    sb.append("    Property ${").append(v.name()).append("} is not declared.\n")
                      .append("    Did you mean ${").append(v.suggestion()).append("}?\n")
                      .append("    The IKE typed-marker family uses __GA__ between groupId and\n")
                      .append("    artifactId, and __VERSION as the terminal facet marker on a\n")
                      .append("    version pin. Typed dots ARE valid in property names; only\n")
                      .append("    __GA__ (or the legacy U+00B7 ·) signals the IKE GA convention.\n")
                      .append("    See IKE-VERSIONS.md and IKE-Network/ike-issues#525.\n");
                }
                case INVALID_POLICY -> {
                    sb.append("    Value \"").append(v.name()).append("\" is not a release policy.\n");
                    if (v.suggestion() != null) {
                        sb.append("    Did you mean \"").append(v.suggestion()).append("\"?\n");
                    }
                    sb.append("    Valid release policies: ").append(validPolicyList()).append(".\n")
                      .append("    A ${groupId__GA__artifactId__POLICY} property declares how this\n")
                      .append("    project responds when that upstream is released. The legacy\n")
                      .append("    ${groupId·artifactId·policy} form is also accepted during the\n")
                      .append("    transition. See IKE-Network/ike-issues#525.\n");
                }
                case MISSING_SCM -> {
                    sb.append("    Reactor-root POM declares no local <scm> with a <url> or\n")
                      .append("    <connection>. This POM sits at the git repository root\n")
                      .append("    (its directory contains the .git entry), so the release\n")
                      .append("    cascade reads its <scm> as the repository's identity —\n")
                      .append("    the join key that collapses every coordinate produced in\n")
                      .append("    this repo onto one cascade node.\n")
                      .append("    Inheriting <scm> from ike-base-parent is NOT enough: Maven's\n")
                      .append("    default <scm> inheritance appends this project's artifactId\n")
                      .append("    to the parent URL, yielding a non-existent path like\n")
                      .append("    .../ike-base-parent/<artifactId>. Declare a <scm> block on\n")
                      .append("    this root POM with at least a <url> (web URL) or\n")
                      .append("    <connection> (scm:git:... URL) naming the actual repository.\n")
                      .append("    Subprojects inherit and need no local block of their own.\n")
                      .append("    See IKE-Network/ike-issues#496.\n");
                }
            }
        }
        sb.append("\nAlias manifest sources consulted:\n        ")
          .append(ManifestLoader.consultedPaths(projectDir));
        return sb.toString();
    }

    private static Path projectDirOf(Model model) {
        Path pomFile = model.getPomFile();
        return pomFile == null ? null : pomFile.getParent();
    }

    /**
     * Walks up from {@code dir} looking for a directory containing
     * {@code .mvn/extensions.xml}. If found, the POM is within
     * the workspace that registered this extension and should be
     * processed; if not, it is a transitively resolved upstream
     * artifact (typically under {@code ~/.m2/repository/}) and
     * should pass through untouched.
     */
    private static boolean isInOurWorkspace(Path dir) {
        Path cursor = dir;
        while (cursor != null) {
            if (Files.exists(cursor.resolve(MVN_EXTENSIONS))) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    private static String describe(Model model) {
        String g = model.getGroupId();
        String a = model.getArtifactId();
        String v = model.getVersion();
        StringBuilder sb = new StringBuilder();
        if (g != null) {
            sb.append(g).append(':');
        }
        sb.append(a == null ? "<unknown>" : a);
        if (v != null) {
            sb.append(':').append(v);
        }
        return sb.toString();
    }

    private enum ViolationKind {
        UNRESOLVED_CANONICAL,
        DOT_TYPO,
        INVALID_POLICY,
        MISSING_SCM
    }

    /**
     * One convention violation captured during a model scan. The
     * record reaches {@link #buildErrorMessage} which formats each
     * one according to its {@link #kind}. The {@code suggestion}
     * is the "did you mean..." hint where applicable; {@code null}
     * when no suggestion is available.
     */
    private record Violation(ViolationKind kind, String name,
                              String section, String coords,
                              String suggestion) {}
}
