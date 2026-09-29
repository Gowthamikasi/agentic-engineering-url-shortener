package com.example.urlshortener.contracts;

/** Published contract versions, and where the contract files live. */
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
