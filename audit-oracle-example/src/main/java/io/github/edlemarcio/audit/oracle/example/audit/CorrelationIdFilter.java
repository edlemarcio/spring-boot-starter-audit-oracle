package io.github.edlemarcio.audit.oracle.example.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".CORRELATION_ID";
    public static final String RESPONSE_HEADER = "X-Request-ID";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String suppliedId = request.getHeader(RESPONSE_HEADER);
        String correlationId = isSafe(suppliedId) ? suppliedId : UUID.randomUUID().toString();

        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(RESPONSE_HEADER, correlationId);
        filterChain.doFilter(request, response);
    }

    private boolean isSafe(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,128}");
    }
}
