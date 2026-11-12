package local.termux.agentbridge;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Per-task capability and package allowlist. Pure Java so it runs in JVM tests. */
final class CapabilitySet {
    static final String PHONE = "phone";
    static final String VISION = "vision";
    static final String FILES = "files";
    static final String SHELL = "shell";

    private static final Set<String> KNOWN = new LinkedHashSet<>(Arrays.asList(PHONE, VISION, FILES, SHELL));
    private static final Set<String> FILE_TOOLS = new LinkedHashSet<>(Arrays.asList(
            "read_file", "list_files", "write_file", "edit_file", "grep_files", "glob_files", "undo"));

    private final boolean all;
    private final Set<String> capabilities;
    private final boolean allPackages;
    private final Set<String> packages;

    private CapabilitySet(boolean all, Set<String> capabilities, boolean allPackages, Set<String> packages) {
        this.all = all;
        this.capabilities = capabilities;
        this.allPackages = allPackages;
        this.packages = packages;
    }

    static CapabilitySet all() {
        return new CapabilitySet(true, Collections.emptySet(), true, Collections.emptySet());
    }

    /** Empty means all; unknown names are rejected instead of silently narrowing or widening. */
    static CapabilitySet parse(String csv) {
        if (csv == null || csv.trim().isEmpty()) return all();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String token : csv.trim().split("[,\\s]+")) {
            if (token.isEmpty()) continue;
            String value = token.toLowerCase(Locale.ROOT);
            if (value.equals("*")) return all();
            if (!KNOWN.contains(value)) throw new IllegalArgumentException("Unknown capability: " + token);
            out.add(value);
        }
        return out.isEmpty() ? all() : new CapabilitySet(false, Collections.unmodifiableSet(out), true, Collections.emptySet());
    }

    /** Empty or '*' means every package is allowed. */
    static CapabilitySet packages(String csv) {
        if (csv == null || csv.trim().isEmpty()) return all();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String token : csv.trim().split("[,\\s]+")) {
            if (token.isEmpty()) continue;
            if (token.equals("*")) return all();
            out.add(token);
        }
        return out.isEmpty() ? all() : new CapabilitySet(true, Collections.emptySet(), false, Collections.unmodifiableSet(out));
    }

    boolean allows(String capability) {
        return all || (capability != null && capabilities.contains(capability));
    }

    static String capabilityOf(String toolName) {
        if (toolName == null || toolName.isEmpty() || toolName.equals("confirm_action")) return "";
        if (FILE_TOOLS.contains(toolName)) return FILES;
        if (toolName.equals("shell") || toolName.equals("job")) return SHELL;
        if (toolName.equals("phone_vision")) return VISION;
        if (toolName.startsWith("phone_")) return PHONE;
        return "";
    }

    boolean toolAllowed(String toolName) {
        if (toolName == null) return false;
        if (toolName.equals("confirm_action")) return true;
        if (toolName.equals("phone_vision")) return allows(PHONE) && allows(VISION);
        String capability = capabilityOf(toolName);
        return !capability.isEmpty() && allows(capability);
    }

    boolean packageAllowed(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return true;
        return allPackages || packages.contains(pkg.trim());
    }

    String capabilitiesCsv() {
        return all ? "*" : join(capabilities);
    }

    String packagesCsv() {
        return allPackages ? "*" : join(packages);
    }

    private static String join(Set<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }
}
