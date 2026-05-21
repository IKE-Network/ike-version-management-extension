package network.ike.extension.version;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads and merges {@link AliasManifest} instances from the
 * extension's bundled default and any per-project override.
 *
 * <p>Two locations are consulted, in order of decreasing
 * precedence:
 *
 * <ol>
 *   <li><b>{@code .mvn/version-aliases.yaml}</b> at the workspace
 *       root (a sibling of the workspace's {@code .mvn/extensions.xml}).
 *       Project-local overrides take precedence over the bundled
 *       defaults.</li>
 *   <li><b>{@code META-INF/ike/version-aliases.yaml}</b> on the
 *       extension's classpath. Shipped inside the
 *       {@code ike-version-management-extension} jar; provides the
 *       IKE-curated default canonical pins and legacy aliases.</li>
 * </ol>
 *
 * <p>"Merge" means: a canonical pin or alias declared in the
 * project-local file replaces any same-name entry from the bundled
 * defaults. Other entries are unioned.
 *
 * <p>The set of paths consulted is also surfaced via
 * {@link #consultedPaths(Path)} for inclusion in error messages
 * — when the extension hard-fails on an unresolved {@code ${G·A}},
 * we tell the user every place we looked.
 */
final class ManifestLoader {

    /** Classpath resource path for the bundled default manifest. */
    private static final String BUNDLED_RESOURCE = "META-INF/ike/version-aliases.yaml";

    /** Project-local override path, relative to the workspace root. */
    private static final String PROJECT_LOCAL_RELATIVE = ".mvn/version-aliases.yaml";

    private ManifestLoader() {}

    /**
     * Loads and merges the bundled default manifest with any
     * project-local override.
     *
     * @param projectDir the directory containing the POM under
     *                   transformation, used as the search anchor
     *                   for the project-local override; may be
     *                   {@code null} if the POM has no file (e.g.,
     *                   synthetic models), in which case only the
     *                   bundled defaults are returned
     * @return a manifest representing the merged view; never
     *         {@code null}
     */
    static AliasManifest load(Path projectDir) {
        AliasManifest bundled = loadBundled();
        AliasManifest local = projectDir == null ? AliasManifest.empty() : loadProjectLocal(projectDir);
        return merge(bundled, local);
    }

    /**
     * Returns the human-readable list of locations consulted by
     * {@link #load(Path)}, for inclusion in diagnostic messages.
     * Project-local path is included whether or not the file
     * existed — the user benefits from knowing where to add an
     * override.
     *
     * @param projectDir the directory containing the POM under
     *                   transformation; may be {@code null}
     * @return a human-readable, newline-separated description of
     *         the manifest sources consulted
     */
    static String consultedPaths(Path projectDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("classpath:").append(BUNDLED_RESOURCE);
        if (projectDir != null) {
            Path local = findProjectLocal(projectDir);
            sb.append("\n        ");
            if (local != null) {
                sb.append(local).append(" (read)");
            } else {
                sb.append(projectDir.resolve(PROJECT_LOCAL_RELATIVE)).append(" (not present)");
            }
        }
        return sb.toString();
    }

    private static AliasManifest loadBundled() {
        ClassLoader cl = ManifestLoader.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                System.err.println("[ike-version-management-extension] WARN: bundled manifest "
                        + BUNDLED_RESOURCE + " not found on classpath");
                return AliasManifest.empty();
            }
            return AliasManifest.parse(in);
        } catch (IOException e) {
            System.err.println("[ike-version-management-extension] WARN: failed to read bundled "
                    + "manifest " + BUNDLED_RESOURCE + ": " + e.getMessage());
            return AliasManifest.empty();
        }
    }

    private static AliasManifest loadProjectLocal(Path projectDir) {
        Path local = findProjectLocal(projectDir);
        if (local == null) {
            return AliasManifest.empty();
        }
        try (InputStream in = Files.newInputStream(local)) {
            return AliasManifest.parse(in);
        } catch (IOException e) {
            System.err.println("[ike-version-management-extension] WARN: failed to read project-local "
                    + "manifest " + local + ": " + e.getMessage());
            return AliasManifest.empty();
        }
    }

    /**
     * Searches for {@code .mvn/version-aliases.yaml} starting at
     * {@code projectDir} and walking up to the filesystem root.
     * Maven workspaces typically have {@code .mvn/} at the
     * reactor root, so a leaf POM's {@code projectDir} won't
     * contain the file directly.
     */
    private static Path findProjectLocal(Path projectDir) {
        Path cursor = projectDir;
        while (cursor != null) {
            Path candidate = cursor.resolve(PROJECT_LOCAL_RELATIVE);
            if (Files.exists(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private static AliasManifest merge(AliasManifest base, AliasManifest overlay) {
        if (overlay.canonical().isEmpty() && overlay.aliases().isEmpty()) {
            return base;
        }
        Map<String, String> canonical = new LinkedHashMap<>(base.canonical());
        canonical.putAll(overlay.canonical());
        Map<String, String> aliases = new LinkedHashMap<>(base.aliases());
        aliases.putAll(overlay.aliases());
        return AliasManifest.of(canonical, aliases);
    }
}
