package sh.vork.web;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestOriginContext {

    private static final ThreadLocal<HttpServletRequest> CURRENT_REQUEST = new ThreadLocal<>();

    private RequestOriginContext() {
    }

    public static void bind(HttpServletRequest request) {
        CURRENT_REQUEST.set(request);
    }

    public static void clear() {
        CURRENT_REQUEST.remove();
    }

    public static HttpServletRequest get() {
        return CURRENT_REQUEST.get();
    }

    public static String resolveBaseUrlFromCurrentRequest() {
        return resolveBaseUrl(get());
    }

    public static String resolveBaseUrl(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        String scheme = firstToken(request.getHeader("X-Forwarded-Proto"));
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }

        String hostHeader = firstToken(request.getHeader("X-Forwarded-Host"));
        if (hostHeader == null || hostHeader.isBlank()) {
            hostHeader = firstToken(request.getHeader("Host"));
        }

        String host = null;
        Integer hostHeaderPort = null;
        if (hostHeader != null && !hostHeader.isBlank()) {
            String normalizedHost = hostHeader.trim();
            int colonIdx = normalizedHost.lastIndexOf(':');
            if (colonIdx > 0 && colonIdx < normalizedHost.length() - 1
                    && normalizedHost.indexOf(']') < 0) {
                host = normalizedHost.substring(0, colonIdx);
                try {
                    hostHeaderPort = Integer.parseInt(normalizedHost.substring(colonIdx + 1));
                } catch (NumberFormatException ignored) {
                    hostHeaderPort = null;
                    host = normalizedHost;
                }
            } else {
                host = normalizedHost;
            }
        }

        if (host == null || host.isBlank()) {
            host = request.getServerName();
        }

        String forwardedPort = firstToken(request.getHeader("X-Forwarded-Port"));
        Integer port = null;
        if (forwardedPort != null && !forwardedPort.isBlank()) {
            try {
                port = Integer.parseInt(forwardedPort);
            } catch (NumberFormatException ignored) {
                port = null;
            }
        }
        if (port == null) {
            port = hostHeaderPort != null ? hostHeaderPort : request.getServerPort();
        }

        boolean defaultPort = ("https".equalsIgnoreCase(scheme) && port == 443)
                || ("http".equalsIgnoreCase(scheme) && port == 80);

        if (defaultPort) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }

    private static String firstToken(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String[] parts = headerValue.split(",");
        return parts.length == 0 ? null : parts[0].trim();
    }
}