package sh.vork.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds a plain HTTP connector on {@code vork.ssl.http-port} (default 8080) that
 * redirects all traffic to HTTPS except ACME HTTP-01 challenge paths.
 *
 * This bean is only active when {@code server.ssl.certificate} is configured,
 * i.e., when the {@link sh.vork.ssl.SslStartupInitializer} successfully set up the
 * SSL certificate.
 */
@Configuration
@ConditionalOnProperty(name = "server.ssl.certificate")
public class SslServerConfig {

    private static final Logger log = LoggerFactory.getLogger(SslServerConfig.class);

    @Value("${vork.ssl.http-port:8080}")
    private int httpPort;

    @Value("${server.port:8443}")
    private int httpsPort;

    /**
     * Adds a second, plain-HTTP Tomcat connector on {@code vork.ssl.http-port}.
     * Its sole purpose is to accept HTTP connections and redirect them to HTTPS.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpConnectorCustomizer() {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setScheme("http");
            connector.setPort(httpPort);
            connector.setSecure(false);
            connector.setRedirectPort(httpsPort);
            factory.addAdditionalTomcatConnectors(connector);
            log.info("SSL: HTTP redirect connector listening on port {} → HTTPS {}", httpPort, httpsPort);
        };
    }

    /**
     * A filter that sends a 301 redirect from HTTP to HTTPS for all requests
     * except ACME HTTP-01 challenge paths ({@code /.well-known/acme-challenge/**}).
     */
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<HttpsRedirectFilter>
    httpsRedirectFilter() {
        HttpsRedirectFilter filter = new HttpsRedirectFilter(httpPort, httpsPort);
        var bean = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.addUrlPatterns("/*");
        return bean;
    }

    // ── Inner filter ──────────────────────────────────────────────────────────

    public static class HttpsRedirectFilter extends OncePerRequestFilter {

        private static final Logger flog = LoggerFactory.getLogger(HttpsRedirectFilter.class);

        private final int httpPort;
        private final int httpsPort;

        public HttpsRedirectFilter(int httpPort, int httpsPort) {
            this.httpPort  = httpPort;
            this.httpsPort = httpsPort;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            if (request.getServerPort() == httpPort && !request.isSecure()) {
                String path = request.getRequestURI();

                // Let ACME HTTP-01 challenges pass through on plain HTTP
                if (path.startsWith("/.well-known/acme-challenge/")) {
                    flog.debug("ACME challenge request allowed on HTTP: {}", path);
                    chain.doFilter(request, response);
                    return;
                }

                String target = buildHttpsUrl(request);
                flog.debug("Redirecting HTTP→HTTPS: {} → {}", path, target);
                response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
                response.setHeader("Location", target);
                return;
            }
            chain.doFilter(request, response);
        }

        private String buildHttpsUrl(HttpServletRequest req) {
            StringBuilder url = new StringBuilder("https://").append(req.getServerName());
            if (httpsPort != 443) {
                url.append(':').append(httpsPort);
            }
            url.append(req.getRequestURI());
            String qs = req.getQueryString();
            if (qs != null) url.append('?').append(qs);
            return url.toString();
        }
    }
}
