package network.ike.extension.version;

import network.ike.support.enums.ConstantBackedEnum;
import network.ike.support.enums.ReleasePolicy;

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
 * Maven 4 build extension implementing the IKE GA·convention for
 * version-property naming. Hooks Maven's
 * {@link ModelTransformer#transformFileModel(Model) file-model}
 * and {@link ModelTransformer#transformEffectiveModel(Model)
 * effective-model} stages to inject alias indirections and to
 * fail fast on convention violations.
 *
 * <p>The convention is: a Maven property that pins an artifact
 * version is named {@code groupId·artifactId} (U+00B7 MIDDLE DOT)
 * and its value is the version. See {@code IKE-VERSIONS.md} in
 * the {@code ike-build-standards} module and
 * IKE-Network/ike-issues#470, #471, #472 for the full standard.
 *
 * <h2>What this transformer does</h2>
 *
 * <h3>1. Alias injection (file-model stage)</h3>
 *
 * <p>For each alias {@code short → G·A} in the merged
 * {@link ManifestLoader manifest}: if the consumer's POM declares
 * {@code short} but not {@code G·A}, inject {@code <G·A>${short}</G·A>}
 * so canonical-form references resolve to the same value. The
 * mirror also holds — if {@code G·A} is declared but {@code short}
 * is not, inject {@code <short>${G·A}</short>}. If neither is
 * declared, the POM is not modified (we do not inject the manifest's
 * default value over an absent property, which would otherwise
 * leak the IKE manifest into POMs that don't reference any of
 * its pins).
 *
 * <h3>2. Hard failure on unresolved canonical references
 * (effective-model stage)</h3>
 *
 * <p>If a {@code <dependency>} or {@code <plugin>} {@code <version>}
 * still contains a literal {@code ${...}} after effective-model
 * interpolation, and the property name contains {@code ·}, the
 * build fails with a {@link ModelTransformerException} naming the
 * property, the artifact, and the manifest paths consulted.
 *
 * <h3>3. Typo detection (effective-model stage)</h3>
 *
 * <p>If an unresolved {@code ${X}} reference contains regular
 * periods, the transformer checks whether replacing every period
 * with {@code ·} produces a property name that IS declared in the
 * effective model. If so, the build fails with a "did you mean
 * {@code ${G·A}}?" hint pointing at the middle-dot replacement.
 *
 * <h3>4. Release-policy validation (effective-model stage)</h3>
 *
 * <p>A project declares how it responds to an upstream release with
 * a {@code ${groupId·artifactId·policy}} property whose value is a
 * {@link ReleasePolicy} rung. For every effective-model property
 * whose name ends in {@code ·policy}, the transformer checks that the
 * value is one of {@code notify}, {@code verify}, {@code propose},
 * {@code integrate}, {@code release}; an unrecognized value fails the
 * build with the valid set and a closest-match "did you mean" hint.
 *
 * <h3>5. SCM presence (effective-model stage)</h3>
 *
 * <p>The release cascade keys repositories by {@code <scm>}: every
 * coordinate a reactor produces inherits one {@code <scm>}, so to map
 * a consumed coordinate back to its producing repository the cascade
 * resolves the coordinate's POM and reads its inherited
 * {@code <scm>}. If a project has no {@code <scm>} with a
 * {@code <url>} or {@code <connection>} — declared or inherited —
 * there is no join key, and the cascade cannot place it on the graph.
 * The build fails with a message that points at the missing block.
 * See IKE-Network/ike-issues#496.
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

    /** The IKE GA·convention separator: U+00B7 MIDDLE DOT. */
    private static final char SEPARATOR = '·';

    /** Property-name suffix marking a release-policy declaration. */
    private static final String POLICY_SUFFIX = SEPARATOR + "policy";

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
     * Injects alias indirections at file-model stage. See class
     * Javadoc for the rules.
     *
     * @param model the file-stage parsed Maven model
     * @return the (possibly augmented) model — same instance when
     *         no aliases needed injection
     * @throws ModelTransformerException never thrown at this stage;
     *                                    declared for SPI conformance
     */
    @Override
    public Model transformFileModel(Model model) throws ModelTransformerException {
        Path projectDir = projectDirOf(model);
        if (!isInOurWorkspace(projectDir)) {
            return model;
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
     * unresolved {@code ${...}} version references, unrecognized
     * release-policy values, and missing {@code <scm>} — and fails
     * the build with an actionable message if any are found.
     *
     * @param model the effective Maven model (post-inheritance,
     *              post-interpolation)
     * @return the model unchanged
     * @throws ModelTransformerException if an unresolved
     *                                    {@code ${G·A}}, a
     *                                    {@code ${G.A}} typo, an
     *                                    invalid release policy, or
     *                                    a missing {@code <scm>} is
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
        scanScm(model, violations);
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
            if (name.indexOf(SEPARATOR) >= 0) {
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
     * Looks for a {@code ${G·A}} property that would resolve if a
     * single {@code .} in {@code name} were instead {@code ·}.
     * Tries every dot position (right-to-left, since the most
     * common typo is the last dot before the artifactId). Returns
     * the first declared candidate, or {@code null} if no
     * single-dot substitution resolves.
     */
    private static String findDotTypoSuggestion(String name, Map<String, String> props) {
        char[] chars = name.toCharArray();
        for (int i = chars.length - 1; i >= 0; i--) {
            if (chars[i] != '.') {
                continue;
            }
            chars[i] = SEPARATOR;
            String candidate = new String(chars);
            if (props.containsKey(candidate)) {
                return candidate;
            }
            chars[i] = '.';
        }
        return null;
    }

    /**
     * Scans effective-model properties for release-policy
     * declarations — names ending in {@code ·policy} — and records a
     * violation for any whose value is not a {@link ReleasePolicy}
     * rung.
     */
    private static void scanPolicyProperties(Map<String, String> props, List<Violation> out) {
        Map<String, ReleasePolicy> policies = ConstantBackedEnum.index(ReleasePolicy.class);
        for (Map.Entry<String, String> entry : props.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.endsWith(POLICY_SUFFIX)) {
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
     * Records a violation if the effective model has no {@code <scm>}
     * with at least a {@code <url>} or {@code <connection>} —
     * declared locally or inherited. The cascade's coordinate-to-
     * repository join key depends on every IKE POM resolving a valid
     * {@code <scm>}; see IKE-Network/ike-issues#496.
     */
    private static void scanScm(Model model, List<Violation> out) {
        Scm scm = model.getScm();
        boolean hasIdentity = scm != null
                && ((scm.getUrl() != null && !scm.getUrl().isBlank())
                    || (scm.getConnection() != null && !scm.getConnection().isBlank()));
        if (!hasIdentity) {
            out.add(new Violation(ViolationKind.MISSING_SCM, null, "project",
                    describe(model), null));
        }
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
            sb.append("\n  [").append(switch (v.kind) {
                case UNRESOLVED_CANONICAL -> "UNRESOLVED";
                case DOT_TYPO -> "TYPO";
                case INVALID_POLICY -> "INVALID-POLICY";
                case MISSING_SCM -> "MISSING-SCM";
            }).append("] ")
              .append(v.section).append(" ").append(v.coords).append("\n");
            switch (v.kind) {
                case UNRESOLVED_CANONICAL -> {
                    sb.append("    Property ${").append(v.name).append("} is not declared.\n")
                      .append("    The IKE convention requires every ${groupId·artifactId} (U+00B7)\n")
                      .append("    property to be defined either locally, in an inherited parent\n")
                      .append("    POM (e.g., ike-base-parent), or in an alias manifest.\n");
                }
                case DOT_TYPO -> {
                    sb.append("    Property ${").append(v.name).append("} is not declared.\n")
                      .append("    Did you mean ${").append(v.suggestion).append("}? (middle dot, U+00B7)\n")
                      .append("    Typed dots ARE valid in property names; only · signals the\n")
                      .append("    IKE GA convention. See IKE-VERSIONS.md.\n");
                }
                case INVALID_POLICY -> {
                    sb.append("    Value \"").append(v.name).append("\" is not a release policy.\n");
                    if (v.suggestion != null) {
                        sb.append("    Did you mean \"").append(v.suggestion).append("\"?\n");
                    }
                    sb.append("    Valid release policies: ").append(validPolicyList()).append(".\n")
                      .append("    A ${groupId·artifactId·policy} property declares how this\n")
                      .append("    project responds when that upstream is released.\n");
                }
                case MISSING_SCM -> {
                    sb.append("    Project has no <scm> with a <url> or <connection>.\n")
                      .append("    The release cascade keys repositories by <scm> to map\n")
                      .append("    consumed coordinates back to the producing repository:\n")
                      .append("    every coordinate a reactor produces inherits one <scm>,\n")
                      .append("    so the cascade reads it to identify the repo to release.\n")
                      .append("    Declare or inherit an <scm> block with at least a\n")
                      .append("    <url> (web URL) or <connection> (scm:git:... URL) set.\n")
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

    private static final class Violation {
        final ViolationKind kind;
        final String name;
        final String section;
        final String coords;
        final String suggestion;

        Violation(ViolationKind kind, String name, String section, String coords, String suggestion) {
            this.kind = kind;
            this.name = name;
            this.section = section;
            this.coords = coords;
            this.suggestion = suggestion;
        }
    }
}
