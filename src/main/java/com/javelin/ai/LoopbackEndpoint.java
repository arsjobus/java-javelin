package com.javelin.ai;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The only addresses a local AI server may have: this computer. The check is on the text of the
 * address and never looks a name up, so judging an address cannot itself touch the network. A
 * remote address in {@code ai.ollama.endpoint} is refused instead of being used: source code must
 * not leave the computer because of a settings line.
 */
public final class LoopbackEndpoint {
  public static final String DEFAULT = "http://127.0.0.1:11434";

  private static final Pattern IPV4_LOOPBACK =
      Pattern.compile("127\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

  private LoopbackEndpoint() {}

  /**
   * The address as a URI without a trailing slash path, or an exception saying why it was refused.
   */
  public static URI require(String text) throws AiException {
    if (text == null || text.isBlank()) throw refused("The AI server address is empty.");
    URI uri;
    try {
      uri = new URI(text.strip());
    } catch (URISyntaxException ex) {
      throw refused("'" + text.strip() + "' is not a valid AI server address.");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https"))
      throw refused("The AI server address must start with http:// or https://: " + text.strip());
    if (uri.getRawUserInfo() != null)
      throw refused("The AI server address must not contain a user name or password.");
    if (uri.getRawQuery() != null || uri.getRawFragment() != null)
      throw refused("The AI server address must not contain a query or fragment.");
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    if (!path.isEmpty() && !path.equals("/"))
      throw refused("The AI server address must not contain a path: " + text.strip());
    String host = uri.getHost();
    if (host == null || !isLoopbackHost(host)) {
      throw refused(
          "The AI server address must be on this computer (localhost, 127.0.0.1 or [::1]), but it is '"
              + text.strip()
              + "'. Remote servers are not supported: your code never leaves this computer by accident.");
    }
    try {
      return new URI(scheme, null, host, uri.getPort(), null, null, null);
    } catch (URISyntaxException ex) {
      throw refused("'" + text.strip() + "' is not a valid AI server address.");
    }
  }

  /**
   * True for {@code localhost}, any {@code 127.x.y.z} address and the IPv6 loopback {@code [::1]}.
   */
  public static boolean isLoopbackHost(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    if (h.equals("localhost") || h.equals("[::1]") || h.equals("[0:0:0:0:0:0:0:1]")) return true;
    var m = IPV4_LOOPBACK.matcher(h);
    if (!m.matches()) return false;
    for (int i = 1; i <= 3; i++) if (Integer.parseInt(m.group(i)) > 255) return false;
    return true;
  }

  private static AiException refused(String message) {
    return new AiException(AiException.Kind.REFUSED, message);
  }
}
