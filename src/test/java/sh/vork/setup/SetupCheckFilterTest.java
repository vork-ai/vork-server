package sh.vork.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

class SetupCheckFilterTest {

    @Test
    void redirectsToSetup_whenSetupRequired_andRequestIsNotBypassed() throws Exception {
        SetupService setupService = mock(SetupService.class);
        when(setupService.isSetupRequired()).thenReturn(true);

        SetupCheckFilter filter = new SetupCheckFilter(setupService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setServletPath("/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertEquals("/setup", response.getRedirectedUrl());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void allowsSetupApi_whenSetupRequired() throws Exception {
        SetupService setupService = mock(SetupService.class);
        when(setupService.isSetupRequired()).thenReturn(true);

        SetupCheckFilter filter = new SetupCheckFilter(setupService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/setup/status");
        request.setServletPath("/api/setup/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
