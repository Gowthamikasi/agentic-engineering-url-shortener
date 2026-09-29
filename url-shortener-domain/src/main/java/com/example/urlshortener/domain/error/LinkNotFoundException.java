package com.example.urlshortener.domain.error;

import com.example.urlshortener.domain.ErrorCodes;

/** No link exists for the requested code; maps to 404. */
public class LinkNotFoundException extends DomainException {

    public LinkNotFoundException(String code) {
        super(ErrorCodes.LINK_NOT_FOUND, "No link exists for code '" + code + "'.");
    }
}
