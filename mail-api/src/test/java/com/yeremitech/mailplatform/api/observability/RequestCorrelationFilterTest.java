package com.yeremitech.mailplatform.api.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class RequestCorrelationFilterTest {
    @Test void preservesValidIdAndCleansMdc() throws Exception {
        var request=new MockHttpServletRequest();
        request.addHeader("X-Request-Id","trace_abcdef1234");
        var response=new MockHttpServletResponse();
        new RequestCorrelationFilter().doFilter(request,response,new MockFilterChain());
        assertEquals("trace_abcdef1234",response.getHeader("X-Request-Id"));
        assertNull(MDC.get("requestId"));
    }
    @Test void replacesUntrustedRequestId() throws Exception {
        var request=new MockHttpServletRequest();
        request.addHeader("X-Request-Id","bad\\nInjected:1");
        var response=new MockHttpServletResponse();
        new RequestCorrelationFilter().doFilter(request,response,new MockFilterChain());
        assertTrue(response.getHeader("X-Request-Id").matches("[a-f0-9-]{36}"));
    }
}
