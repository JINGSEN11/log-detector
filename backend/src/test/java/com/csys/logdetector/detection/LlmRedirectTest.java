package com.csys.logdetector.detection;


import com.csys.logdetector.llm.client.OpenAiCompatLlmClient;
import com.csys.logdetector.llm.config.LlmProperties;
import com.csys.logdetector.llm.service.LlmCallRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LlmRedirectTest {
    @Test void doesNotFollowRedirectOrSendCredentials() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Location", "/other"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/other", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new OpenAiCompatLlmClient(new ObjectMapper(), mock(LlmCallRecorder.class), new LlmProperties(base, "synthetic-key", "test", 5, false));
            assertThrows(RuntimeException.class, () -> client.chat("test"));
            assertEquals(0, redirected.get());
        } finally { server.stop(0); }
    }
}
