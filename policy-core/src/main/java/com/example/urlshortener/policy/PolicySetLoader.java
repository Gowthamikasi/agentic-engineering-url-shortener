package com.example.urlshortener.policy;

import com.example.urlshortener.contracts.ApiContract;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads a versioned policy set from the classpath or an external file.
 *
 * <p>The file name carries the version and the loader refuses a set whose {@code version} field
 * disagrees with its own declaration, so a policy set cannot be edited in place and still claim
 * to be the version a past run was evaluated against.
 */
public final class PolicySetLoader {

    private static final String CLASSPATH_TEMPLATE = ApiContract.POLICY_SET_TEMPLATE;

    private final ObjectMapper mapper;

    public PolicySetLoader() {
        this(new ObjectMapper());
    }

    public PolicySetLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public PolicySet loadVersion(String version) {
        String resource = CLASSPATH_TEMPLATE.formatted(version);
        try (InputStream in = PolicySetLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No policy set on the classpath for version " + version);
            }
            return verify(mapper.readValue(in, PolicySet.class), version);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read policy set " + resource, e);
        }
    }

    public PolicySet loadFile(Path path) {
        try {
            PolicySet set = mapper.readValue(Files.readAllBytes(path), PolicySet.class);
            return verify(set, set.version());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read policy set " + path, e);
        }
    }

    private static PolicySet verify(PolicySet set, String expectedVersion) {
        if (!expectedVersion.equals(set.version())) {
            throw new IllegalStateException(
                    "Policy set declares version " + set.version() + " but was loaded as " + expectedVersion);
        }
        if (set.rules() == null || set.rules().isEmpty()) {
            throw new IllegalStateException("Policy set " + set.version() + " contains no rules");
        }
        return set;
    }
}
