package ru.agimate.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.agimate.common.util.UUIDUtils;

import java.io.IOException;



@Slf4j
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        var requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null) {
            requestId = UUIDUtils.generateUUIDv8().toString();
        }
        response.addHeader(REQUEST_ID_HEADER, requestId);

        try {
            setRequestId(requestId);
            filterChain.doFilter(request, response);
        } finally {
            removeRequestId();
        }
    }

    public static void setRequestId(String requestId) {
        MDC.put(REQUEST_ID_HEADER, requestId);
    }

    public static String getRequestId() {
        return MDC.get(REQUEST_ID_HEADER);
    }

    public static void removeRequestId() {
        MDC.remove(REQUEST_ID_HEADER);
    }


}