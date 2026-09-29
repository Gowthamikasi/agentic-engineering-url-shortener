package com.example.urlshortener.domain.port;

import com.example.urlshortener.domain.model.ClickEvent;

/**
 * Offers a click for recording from the redirect path.
 *
 * <p>The redirect must not wait on analytics, so implementations buffer and drop rather than
 * apply back-pressure.
 *
 * @return true if the event was accepted for eventual persistence
 */
public interface ClickRecorder {

    boolean record(ClickEvent event);
}
