package local.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FilterInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
class StdioConfiguration {
    @Bean
    McpJsonMapper protocolMapper(@Qualifier("mcpServerObjectMapper") ObjectMapper mapper) {
        ObjectMapper protocol = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        protocol.setDefaultPropertyInclusion(JsonInclude.Value.construct(
                JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS));
        return new JacksonMcpJsonMapper(protocol);
    }

    @Bean
    McpServerTransportProviderBase memoryStdio(McpJsonMapper mapper, ConfigurableApplicationContext application) {
        var lifetime = new FilterInputStream(System.in) {
            final AtomicBoolean ending = new AtomicBoolean();

            @Override
            public int read() throws IOException {
                return checkEnd(super.read());
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                return checkEnd(super.read(bytes, offset, length));
            }

            private int checkEnd(int count) {
                if (count == -1 && ending.compareAndSet(false, true)) {
                    Thread.ofPlatform().name("memory-shutdown").start(application::close);
                }
                return count;
            }
        };
        return new StdioServerTransportProvider(mapper, lifetime, System.out) {
            // The SDK's stdio transport defaults to 2024. These revisions share the tool framing.
            @Override
            public List<String> protocolVersions() {
                return List.of(ProtocolVersions.MCP_2024_11_05, ProtocolVersions.MCP_2025_03_26,
                        ProtocolVersions.MCP_2025_06_18, ProtocolVersions.MCP_2025_11_25);
            }
        };
    }
}
