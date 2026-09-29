package com.example.urlshortener.contracts;

/**
 * Published contract versions, and where the contract files live.
 *
 * <p>Here rather than in either plane, because both reference them and neither may depend on
 * the other. Keeping the version in one place also means bumping it is a visible edit.
 *
 * <p>Versioning: an additive optional field is a minor bump; a removal, rename or type change
 * is a major bump behind a new path prefix.
 */
public final class ApiContract {

    /** Sent as the API-Version header on application-plane responses. */
    public static final String VERSION = "1.1.0";

    public static final String HEADER = "API-Version";

    /** Classpath location of the workflow the engine runs. */
    public static final String WORKFLOW_DEFINITION_RESOURCE = "/workflows/sdlc.v1.json";

    /** Classpath template for a versioned policy set. */
    public static final String POLICY_SET_TEMPLATE = "/policies/policy-set.v%s.json";

    /** Used when the caller does not name one. */
    public static final String DEFAULT_POLICY_VERSION = "1.0.0";

    private ApiContract() {
    }
}
