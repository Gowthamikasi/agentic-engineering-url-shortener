package com.example.urlshortener.domain.spi;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Seam over DNS so URL validation is testable without network access.
 * Implemented in the domain by {@link InetAddressHostResolver}; tests substitute a stub.
 */
public interface HostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
