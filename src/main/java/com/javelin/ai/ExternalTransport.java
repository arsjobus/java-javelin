package com.javelin.ai;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * The one thing the external provider needs from the network: send a request, get the status and
 * the body. A seam so the provider's logic (what is sent, what is refused, what a reply means) is
 * tested without a network; {@link JdkExternalTransport} is the only implementation that touches
 * one.
 */
interface ExternalTransport {
  record Response(int status, String body) {}

  Response get(URI uri, Map<String, String> headers, Duration timeout, AiCancel cancel)
      throws AiException;

  Response post(
      URI uri, Map<String, String> headers, String body, Duration timeout, AiCancel cancel)
      throws AiException;
}
