package com.example.urlshortener.domain.spi;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Default resolver backed by the platform resolver. */
public final class InetAddressHostResolver implements HostResolver {

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
