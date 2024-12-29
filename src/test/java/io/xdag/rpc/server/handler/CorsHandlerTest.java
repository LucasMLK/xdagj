package io.xdag.rpc.server.handler;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class CorsHandlerTest {

    private EmbeddedChannel channel;
    private static final String ALLOWED_ORIGINS = "http://localhost:3000";

    @Before
    public void setUp() {
        CorsHandler handler = new CorsHandler(ALLOWED_ORIGINS);
        channel = new EmbeddedChannel(handler);
    }

    @Test
    public void testSimpleRequestWithDisallowedOrigin() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/");
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://disallowed.com")
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json");

        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();

        assertNotNull(response);
        assertEquals(HttpResponseStatus.FORBIDDEN, response.status());
        assertNull(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testPreflightRequestWithDisallowedOrigin() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.OPTIONS,
                "/");
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://disallowed.com")
                .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS, "content-type");

        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();

        assertNotNull(response);
        assertEquals(HttpResponseStatus.FORBIDDEN, response.status());
        assertNull(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    public void testPreflightRequest() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.OPTIONS,
                "/");
        request.headers()
                .set(HttpHeaderNames.ORIGIN, "http://localhost:3000")
                .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .set(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS, "content-type");

        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();

        assertNotNull(response);
        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals("http://localhost:3000", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertEquals("GET, POST, OPTIONS", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS));
        assertEquals("content-type, authorization", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
        assertEquals("Origin", response.headers().get(HttpHeaderNames.VARY));
    }
}