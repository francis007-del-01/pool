package com.pool.variable;

/**
 * Source of variable values.
 */
public enum VariableSource {
    /**
     * Request variables from request payload ($req.*)
     */
    REQUEST("$req"),

    /**
     * System variables auto-populated by Pool ($sys.*)
     */
    SYSTEM("$sys"),

    /**
     * Context variables from request context/headers ($ctx.*)
     */
    CONTEXT("$ctx");

    private final String prefix;

    VariableSource(String prefix) {
        this.prefix = prefix;
    }

    public String getPrefix() {
        return prefix;
    }

    /**
     * Determine the source from a variable reference.
     *
     * @param reference Variable reference (e.g., "$req.amount")
     * @return The variable source
     * @throws IllegalArgumentException if reference is invalid
     */
    public static VariableSource fromReference(String reference) {
        if (reference == null || reference.isEmpty()) {
            throw new IllegalArgumentException("Variable reference cannot be null or empty");
        }
        if (reference.startsWith(REQUEST.prefix + ".")) {
            return REQUEST;
        }
        if (reference.startsWith(SYSTEM.prefix + ".")) {
            return SYSTEM;
        }
        if (reference.startsWith(CONTEXT.prefix + ".")) {
            return CONTEXT;
        }
        throw new IllegalArgumentException("Invalid variable reference: " + reference +
                ". Must start with $req., $sys., or $ctx.");
    }

    /**
     * Extract the variable name from a reference.
     *
     * @param reference Variable reference (e.g., "$req.amount")
     * @return The variable name (e.g., "amount")
     */
    public static String extractName(String reference) {
        VariableSource source = fromReference(reference);
        return reference.substring(source.prefix.length() + 1);
    }
}
