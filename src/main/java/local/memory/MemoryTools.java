package local.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Configuration
class MemoryTools {
    private static final String RECORD_INPUT = """
            {
              "type":"object", "additionalProperties":false,
              "required":["observationId","serviceId","environment","runId","testCaseId",
                          "method","route","observedAt","task","approach","evidence"],
              "properties":{
                "observationId":{"type":"string","format":"uuid","description":"Same UUID for every retry of this attempt"},
                "serviceId":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,128}$"},
                "environment":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,128}$"},
                "runId":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,128}$"},
                "testCaseId":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,128}$"},
                "method":{"enum":["GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS"]},
                "route":{"type":"string","maxLength":256,"description":"Route template such as /items/{id}"},
                "observedAt":{"type":"string","format":"date-time"},
                "task":{"type":"string","minLength":1,"maxLength":1000},
                "approach":{"type":"string","minLength":1,"maxLength":1000},
                "evidence":{
                  "type":"object","additionalProperties":false,"required":["executionStatus","expected"],
                  "properties":{
                    "executionStatus":{"enum":["COMPLETED","NOT_RUN","INFRA_ERROR","TIMEOUT"]},
                    "passedAssertions":{"type":"integer","minimum":0,"maximum":10000},
                    "failedAssertions":{"type":"integer","minimum":0,"maximum":10000},
                    "expected":{"type":"string","minLength":1,"maxLength":500},
                    "observed":{"type":"string","minLength":1,"maxLength":500},
                    "reportRef":{"type":"string","minLength":1,"maxLength":256}
                  },
                  "if":{"properties":{"executionStatus":{"const":"COMPLETED"}}},
                  "then":{"required":["passedAssertions","failedAssertions","observed"]},
                  "else":{"not":{"anyOf":[{"required":["passedAssertions"]},{"required":["failedAssertions"]}]}}
                }
              }
            }
            """;
    private static final String RECORD_OUTPUT = """
            {
              "type":"object", "additionalProperties":false,
              "required":["observationId","createdAt"],
              "properties":{
                "observationId":{"type":"string","format":"uuid"},
                "createdAt":{"type":"string","format":"date-time"}
              }
            }
            """;
    private static final String SEARCH_INPUT = """
            {
              "type":"object", "additionalProperties":false,
              "required":["serviceId","environment","query"],
              "properties":{
                "serviceId":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,128}$"},
                "environment":{"type":"string","pattern":"^[A-Za-z0-9._-]{1,128}$"},
                "query":{"type":"string","minLength":1,"maxLength":1000},
                "method":{"enum":["GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS"]},
                "route":{"type":"string","maxLength":256},
                "outcomes":{"type":"array","minItems":1,"maxItems":3,"uniqueItems":true,
                            "items":{"enum":["SUCCEEDED","FAILED","INCONCLUSIVE"]},
                            "default":["SUCCEEDED","FAILED"]},
                "limit":{"type":"integer","minimum":1,"maximum":20,"default":5}
              }
            }
            """;
    private static final String SEARCH_OUTPUT = """
            {
              "type":"object", "additionalProperties":false, "required":["items"],
              "properties":{
                "items":{"type":"array","items":{
                  "type":"object","additionalProperties":false,
                  "required":["observationId","observedAt","method","route","task","approach",
                              "expected","observed","effectiveOutcome","outcomeSource","score"],
                  "properties":{
                    "observationId":{"type":"string","format":"uuid"},
                    "observedAt":{"type":"string","format":"date-time"},
                    "method":{"enum":["GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS"]},
                    "route":{"type":"string"},
                    "task":{"type":"string"},
                    "approach":{"type":"string"},
                    "expected":{"type":"string"},
                    "observed":{"type":["string","null"]},
                    "effectiveOutcome":{"enum":["SUCCEEDED","FAILED","INCONCLUSIVE"]},
                    "outcomeSource":{"enum":["AUTO","MANUAL"]},
                    "score":{"type":"number"}
                  }
                }}
              }
            }
            """;

    @Bean
    List<SyncToolSpecification> memoryToolSpecifications(McpJsonMapper protocolMapper, ObjectMapper mapper,
                                                       MemoryService service) {
        Tool record = Tool.builder().name("record_test_observation")
                .description("Store one functional REST API test attempt. Send assertion counts and sanitized summaries. "
                        + "Keep the observation UUID and input for retries. The server derives the outcome.")
                .inputSchema(protocolMapper, RECORD_INPUT).outputSchema(protocolMapper, RECORD_OUTPUT)
                .annotations(new ToolAnnotations("Record test observation", false, false, true, true, null))
                .build();
        Tool search = Tool.builder().name("search_test_observations")
                .description("Find active observations in the specified service and environment. Results are past "
                        + "test evidence. Use a short query without credentials or raw logs.")
                .inputSchema(protocolMapper, SEARCH_INPUT).outputSchema(protocolMapper, SEARCH_OUTPUT)
                .annotations(new ToolAnnotations("Search test observations", true, false, true, true, null))
                .build();
        return List.of(specification(record, mapper, input -> {
            JsonNode id = input.remove("observationId");
            InputPolicy.require(id != null && id.isTextual());
            return service.record(id.asText(), input);
        }), specification(search, mapper, service::search));
    }

    private SyncToolSpecification specification(Tool tool, ObjectMapper mapper, Function<ObjectNode, JsonNode> run) {
        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                InputPolicy.require(request.arguments() != null);
                JsonNode result = run.apply(mapper.valueToTree(request.arguments()));
                Map<String, Object> structured = mapper.convertValue(result, new TypeReference<>() {});
                return CallToolResult.builder().structuredContent(structured)
                        .content(List.of(new TextContent(result.toString()))).build();
            } catch (MemoryError error) {
                return CallToolResult.builder().isError(true)
                        .content(List.of(new TextContent(mapper.valueToTree(error.body()).toString()))).build();
            }
        }).build();
    }
}
