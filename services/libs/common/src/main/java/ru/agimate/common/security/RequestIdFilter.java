package ru.agimate.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.agimate.common.util.UUIDUtils;

import java.io.IOException;

/**
 * Tags every log line of a request with one id, echoed back in the response header so a client can
 * quote it. Ahead of the security chain on purpose: a rejected request is exactly the one someone
 * comes asking about, and it must carry an id too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    /** Not the header name: MDC keys become field names in the JSON logs, next to `run` and `jobKey`. */
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // An inbound id is kept as given — it is what ties our logs to the caller's, and rewriting it
        // would break the one join it exists for.
        var requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUIDUtils.generateUUIDv8().toString();
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try (MDC.MDCCloseable __ = MDC.putCloseable(MDC_KEY, requestId)) {
            filterChain.doFilter(request, response);
        }
    }
}
