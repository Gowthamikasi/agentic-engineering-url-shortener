package com.example.urlshortener.domain.port;

import com.example.urlshortener.domain.model.ClickEvent;

/**
 * Outbound port used by the redirect path to offer a click for recording.
 *
 * <p>ASM-005: the redirect must never block on analytics, so implementations offer to a bounded
 * buffer and drop (with a metric) rather than apply back-pressure.
 *
 * @return true if the event was accepted for eventual persistence
 */
public interface ClickRecorder {

    boolean record(ClickEvent event);
}
