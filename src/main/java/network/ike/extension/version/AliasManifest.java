package network.ike.extension.version;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable parsed view of an IKE version-aliases manifest.
 *
 * <p>The manifest is a constrained YAML-lite document with three
 * shapes:
 *
 * <pre>
 *   schema: N
 *
 *   canonical:
 *     groupId&middot;artifactId: version
 *     &hellip;
 *
 *   aliases:
 *     legacy-short-name: groupId&middot;artifactId
 *     &hellip;
 * </pre>
 *
 * <p>No flow style, no anchors, no nested maps. The {@link #parse}
 * factory accepts that subset and ignores anything outside it. The
 * parser is intentionally hand-rolled to keep the extension jar
 * free of transitive dependencies: Maven 4 build extensions load
 * into their own classloader from their declared deps, and adding
 * a YAML library would bloat the artifact for a format this
 * constrained.
 *
 * <p>Instances are immutable. The two contained maps preserve
 * declaration order (LinkedHashMap), so iteration order matches
 * the source file — useful for stable diagnostic messages.
 */
final class AliasManifest {

    /** Canonical pins: {@code groupId·artifactId} → version. */
    private final Map<String, String> canonical;

    /** Aliases: legacy short name → {@code groupId·artifactId}. */
    private final Map<String, String> aliases;

    private AliasManifest(Map<String, String> canonical, Map<String, String> aliases) {
        this.canonical = Collections.unmodifiableMap(canonical);
        this.aliases = Collections.unmodifiableMap(aliases);
    }

    /**
     * Creates an empty manifest (no canonical pins, no aliases).
     *
     * @return a manifest with empty {@link #canonical()} and
     *         {@link #aliases()} maps
     */
    static AliasManifest empty() {
        return new AliasManifest(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    /**
     * Package-private factory accepting raw maps. Callers are
     * responsible for declaration-ordering and ownership; this
     * factory copies neither map, but the returned instance
     * wraps both in unmodifiable views.
     *
     * @param canonical the canonical-pin map, keyed by
     *                  {@code groupId·artifactId}
     * @param aliases the alias map, keyed by legacy short name
     * @return a new manifest backed by the given maps
     */
    static AliasManifest of(Map<String, String> canonical, Map<String, String> aliases) {
        return new AliasManifest(canonical, aliases);
    }

    /**
     * Returns this manifest's canonical-pin map. The keys are
     * {@code groupId·artifactId} strings; the values are the
     * version literals that the extension will inject into
     * consumers' POMs when neither the canonical name nor any of
     * its aliases are already declared.
     *
     * @return an unmodifiable, declaration-ordered map of canonical
     *         pins to version literals
     */
    Map<String, String> canonical() {
        return canonical;
    }

    /**
     * Returns this manifest's alias map. The keys are legacy
     * short-name property identifiers (e.g. {@code junit-jupiter.version});
     * the values are the corresponding {@code groupId·artifactId}
     * canonical names.
     *
     * @return an unmodifiable, declaration-ordered map of legacy
     *         short names to canonical {@code groupId·artifactId}
     *         identifiers
     */
    Map<String, String> aliases() {
        return aliases;
    }

    /**
     * Parses a YAML-lite alias manifest from the given input stream.
     *
     * <p>Recognized constructs:
     * <ul>
     *   <li>{@code # …} — comment lines (ignored).</li>
     *   <li>Blank lines (ignored).</li>
     *   <li>{@code schema: N} at top level (recorded as a check
     *       but not enforced; future schema bumps will use it).</li>
     *   <li>{@code canonical:} section header at top level, followed
     *       by indented {@code key: value} entries.</li>
     *   <li>{@code aliases:} section header at top level, followed
     *       by indented {@code key: value} entries.</li>
     * </ul>
     *
     * <p>Whitespace tolerance: any indentation depth &gt; 0 is
     * treated as "inside the current section." The section ends
     * when a non-indented, non-comment, non-blank line is read.
     *
     * @param in the input stream to read; UTF-8 encoded
     * @return the parsed manifest
     * @throws IOException if the stream cannot be read
     */
    static AliasManifest parse(InputStream in) throws IOException {
        Map<String, String> canonical = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String section = null;
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int indent = leadingSpaces(line);
                if (indent == 0) {
                    // Top-level: either a section header or a scalar like `schema: 1`.
                    int colon = trimmed.indexOf(':');
                    if (colon < 0) {
                        section = null;
                        continue;
                    }
                    String key = trimmed.substring(0, colon).trim();
                    String value = trimmed.substring(colon + 1).trim();
                    if (value.isEmpty()) {
                        // Section header.
                        section = key;
                    } else {
                        // Top-level scalar (e.g. schema). Not in a section.
                        section = null;
                    }
                    continue;
                }
                // Indented entry within a section.
                int colon = trimmed.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = trimmed.substring(0, colon).trim();
                String value = stripInlineComment(trimmed.substring(colon + 1).trim());
                if (key.isEmpty() || value.isEmpty()) {
                    continue;
                }
                if ("canonical".equals(section)) {
                    canonical.put(key, value);
                } else if ("aliases".equals(section)) {
                    aliases.put(key, value);
                }
                // Other sections silently skipped — forward compat.
            }
        }
        return new AliasManifest(canonical, aliases);
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static String stripInlineComment(String value) {
        // YAML allows ` # comment` after a scalar; strip it. Be
        // conservative: only strip when ` #` appears (space-then-hash),
        // not bare `#`, since `#` is valid in some values.
        int hash = value.indexOf(" #");
        return hash < 0 ? value : value.substring(0, hash).trim();
    }
}
