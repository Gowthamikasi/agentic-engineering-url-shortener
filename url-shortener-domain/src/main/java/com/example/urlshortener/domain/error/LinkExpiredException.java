package com.example.urlshortener.domain.error;

import com.example.urlshortener.domain.ErrorCodes;

/** The link existed but has lapsed; maps to 410 Gone (ASM-004). */
public class LinkExpiredException extends DomainException {

    public LinkExpiredException(String code) {
        super(ErrorCodes.LINK_EXPIRED, "Link '" + code + "' has expired.");
    }
}
