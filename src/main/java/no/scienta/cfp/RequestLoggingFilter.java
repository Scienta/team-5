package no.scienta.cfp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * One log line per request: method, path and query, status, duration, the request body and, for errors, the
 * response body (both cut to {@value #MAX_BODY} characters). The harness's checks are invisible without this.
 * Successful GET /health is skipped: the deploy pipeline and the platform poll it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("http");

    private static final int MAX_BODY = 2000;

    private static final String ERROR_ATTRIBUTE = DefaultErrorAttributes.class.getName() + ".ERROR";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, MAX_BODY);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            int status = res.getStatus();
            if (!(status < 400 && "/health".equals(req.getRequestURI()))) {
                StringBuilder line = new StringBuilder()
                        .append(req.getMethod()).append(' ').append(req.getRequestURI());
                if (req.getQueryString() != null) {
                    line.append('?').append(req.getQueryString());
                }
                line.append(" -> ").append(status).append(" (").append(ms).append(" ms)");
                String in = body(req.getContentAsByteArray());
                if (!in.isEmpty()) {
                    line.append(" req=").append(in);
                }
                if (status >= 400) {
                    String out = body(res.getContentAsByteArray());
                    // Errors from sendError() (ResponseStatusException, validation) get their body later, in Spring's
                    // /error dispatch, so log the exception DefaultErrorAttributes stashed on the request instead.
                    Object ex = req.getAttribute(ERROR_ATTRIBUTE);
                    if (!out.isEmpty()) {
                        line.append(" res=").append(out);
                    } else if (ex instanceof Throwable) {
                        line.append(" err=").append(body(String.valueOf(((Throwable) ex).getMessage())
                                .getBytes(StandardCharsets.UTF_8)));
                    }
                }
                if (status >= 500) {
                    log.warn(line.toString());
                } else {
                    log.info(line.toString());
                }
            }
            res.copyBodyToResponse();
        }
    }

    private static String body(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s*\\R\\s*", " ").trim();
        return s.length() > MAX_BODY ? s.substring(0, MAX_BODY) + "…" : s;
    }
}
