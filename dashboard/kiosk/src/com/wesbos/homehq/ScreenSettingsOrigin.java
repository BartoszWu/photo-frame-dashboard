package com.wesbos.homehq;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Origin rules are independent of the legacy exported activity's changeable URL extra. */
final class ScreenSettingsOrigin {
  static String origin(String url) {
    try {
      URI u = new URI(url);
      String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.US);
      String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.US);
      int port = u.getPort();
      if (!("http".equals(scheme) || "https".equals(scheme)) || host.isEmpty()
          || u.getRawUserInfo() != null || port == 0 || port > 65535 || port < -1) {
        throw new IllegalArgumentException("Invalid dashboard origin");
      }
      if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
      return scheme + "://" + host + (port == -1 ? "" : ":" + port);
    } catch (URISyntaxException | NullPointerException e) {
      throw new IllegalArgumentException("Invalid dashboard origin");
    }
  }
  static String pairable(String value) {
    String result = origin(value);
    URI u = URI.create(value);
    if (u.getRawQuery() != null || u.getRawFragment() != null
        || !(u.getPath().isEmpty() || "/".equals(u.getPath()))) {
      throw new IllegalArgumentException("Pair an origin, not a page URL");
    }
    String host = u.getHost().toLowerCase(Locale.US);
    boolean local = "localhost".equals(host) || host.endsWith(".local");
    if (host.matches("[0-9]+(?:\\.[0-9]+){3}")) {
      String[] parts = host.split("\\.");
      int[] n = new int[4];
      for (int i = 0; i < 4; i++) {
        if (parts[i].length() > 3) throw new IllegalArgumentException("Invalid local address");
        n[i] = Integer.parseInt(parts[i]);
        if (n[i] > 255) throw new IllegalArgumentException("Invalid local address");
      }
      local = n[0] == 127 || n[0] == 10 || (n[0] == 192 && n[1] == 168)
          || (n[0] == 172 && n[1] >= 16 && n[1] <= 31);
    }
    if (!local) throw new IllegalArgumentException("Pair only the local dashboard");
    return result;
  }
  static boolean matches(String pinned, String page) {
    try { return pinned != null && pinned.equals(origin(page)); }
    catch (IllegalArgumentException e) { return false; }
  }
}
