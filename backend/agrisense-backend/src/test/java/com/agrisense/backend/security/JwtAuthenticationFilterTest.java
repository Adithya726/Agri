package com.agrisense.backend.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";

    private final JwtService jwtService = new JwtService(SECRET, 60);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);

    private MockFilterChain run(MockHttpServletRequest request,
                                MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain;
    }

    @Test
    void tokenRoundTripKeepsFarmerAndRole() {
        String token = jwtService.generateToken(7L, "a@b.com", "FARMER");
        var claims = jwtService.parse(token);
        assertNotNull(claims);
        assertEquals("7", claims.getSubject());
        assertEquals("FARMER", claims.get(JwtService.ROLE_CLAIM, String.class));
    }

    @Test
    void tokenSignedWithOtherSecretIsRejected() {
        String foreign = new JwtService("another-secret-that-is-at-least-32-bytes", 60)
                .generateToken(1L, "x@y.com", "FARMER");
        assertNull(jwtService.parse(foreign));
        assertNull(jwtService.parse("not.a.jwt"));
    }

    @Test
    void protectedEndpointWithoutTokenGives401() throws Exception {
        var response = new MockHttpServletResponse();
        var chain = run(new MockHttpServletRequest("GET", "/farmapi/farmer/1"), response);
        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void protectedEndpointWithBadTokenGives401() throws Exception {
        var request = new MockHttpServletRequest("GET", "/predictionapi/context/1");
        request.addHeader("Authorization", "Bearer garbage");
        var response = new MockHttpServletResponse();
        run(request, response);
        assertEquals(401, response.getStatus());
    }

    @Test
    void protectedEndpointWithValidTokenPasses() throws Exception {
        var request = new MockHttpServletRequest("GET", "/actionplanapi/farm/1");
        request.addHeader("Authorization",
                "Bearer " + jwtService.generateToken(1L, "a@b.com", "FARMER"));
        var response = new MockHttpServletResponse();
        var chain = run(request, response);
        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
        assertEquals("FARMER", request.getAttribute(JwtAuthenticationFilter.FARMER_ROLE_ATTR));
        assertEquals("1", request.getAttribute(JwtAuthenticationFilter.FARMER_ID_ATTR));
    }

    @Test
    void loginRegisterAndPreflightArePublic() throws Exception {
        for (var request : new MockHttpServletRequest[] {
                new MockHttpServletRequest("POST", "/farmerapi/login"),
                new MockHttpServletRequest("POST", "/farmerapi/register"),
                new MockHttpServletRequest("OPTIONS", "/farmapi/farmer/1") }) {
            var response = new MockHttpServletResponse();
            var chain = run(request, response);
            assertEquals(200, response.getStatus());
            assertNotNull(chain.getRequest());
        }
    }
}
