package com.yeremitech.mailplatform.api.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Propagates a safe, low-cardinality request ID through HTTP and structured logs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final String HEADER = "X-Request-Id";
    private static final String KEY = "requestId";
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        String supplied=request.getHeader(HEADER);
        String id=supplied!=null && supplied.matches("[A-Za-z0-9._-]{8,64}") ? supplied : UUID.randomUUID().toString();
        response.setHeader(HEADER,id);
        MDC.put(KEY,id);
        try { chain.doFilter(request,response); }
        finally { MDC.remove(KEY); }
    }
}
