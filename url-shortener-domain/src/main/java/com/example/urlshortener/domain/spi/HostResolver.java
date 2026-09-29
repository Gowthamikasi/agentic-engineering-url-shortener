package com.example.urlshortener.domain.spi;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** DNS behind an interface, so URL validation can be tested without a network. */
public interface HostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
