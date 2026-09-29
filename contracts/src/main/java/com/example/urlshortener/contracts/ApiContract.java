package com.example.urlshortener.contracts;

/**
 * The published contract versions and where their authoritative definitions live.
 *
 * <p>These sit in their own module because both planes reference them and neither may depend on the
 * other. Keeping the version in one place also keeps the rule below honest: a contract change has
 * to edit this constant, so bumping the version is a visible act rather than something that can be
 * forgotten when a field is added.
 *
 * <p>Versioning rule: an additive optional field is a minor bump and needs no consumer action; a
 * removal, rename or type change is a major bump behind a new path prefix, with the previous
 * version kept serving.
 */
public final class ApiContract {

    /** Emitted as the {@code API-Version} response header on every application-plane response. */
    public static final String VERSION = "1.1.0";

    public static final String HEADER = "API-Version";

    /** Classpath location of the workflow definition the engine executes. */
    public static final String WORKFLOW_DEFINITION_RESOURCE = "/workflows/sdlc.v1.json";

    /** Classpath template for a versioned policy set, e.g. {@code /policies/policy-set.v1.0.0.json}. */
    public static final String POLICY_SET_TEMPLATE = "/policies/policy-set.v%s.json";

    /** The policy version a run is stamped with unless the caller names another. */
    public static final String DEFAULT_POLICY_VERSION = "1.0.0";

    private ApiContract() {
    }
}
