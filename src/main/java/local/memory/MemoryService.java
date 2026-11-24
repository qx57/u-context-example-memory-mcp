package local.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static local.memory.InputPolicy.require;

@Service
final class MemoryService {
    private static final Set<String> LIST_FIELDS = Set.of(
            "serviceId", "environment", "runId", "outcome", "status", "limit", "cursor");
    private final ObjectMapper mapper;
    private final PythonBridge bridge;

    MemoryService(ObjectMapper mapper, PythonBridge bridge) {
        this.mapper = mapper;
        this.bridge = bridge;
    }

    JsonNode record(String id, JsonNode arguments) {
        String observationId = uuid(id);
        Observation input = decode(arguments, Observation.class);
        require(input.method != null && input.evidence != null);
        Evidence evidence = input.evidence;
        require(evidence.executionStatus != null);
        boolean completed = evidence.executionStatus == Execution.COMPLETED;
        if (completed) {
            require(validCount(evidence.passedAssertions) && validCount(evidence.failedAssertions));
        } else {
            JsonNode evidenceObject = arguments.path("evidence");
            require(!evidenceObject.has("passedAssertions") && !evidenceObject.has("failedAssertions"));
        }
        Outcome outcome = Outcome.INCONCLUSIVE;
        if (completed && evidence.failedAssertions > 0) {
            outcome = Outcome.FAILED;
        } else if (completed && evidence.passedAssertions > 0) {
            outcome = Outcome.SUCCEEDED;
        }
        Evidence cleaned = new Evidence(evidence.executionStatus, evidence.passedAssertions, evidence.failedAssertions,
                InputPolicy.text(evidence.expected, true, 500), InputPolicy.text(evidence.observed, completed, 500),
                InputPolicy.report(evidence.reportRef));
        Observation observation = new Observation(
                InputPolicy.id(input.serviceId, true), InputPolicy.id(input.environment, true),
                InputPolicy.id(input.runId, true), InputPolicy.id(input.testCaseId, true),
                input.method, InputPolicy.route(input.route, true), timestamp(input.observedAt),
                InputPolicy.text(input.task, true, 1000), InputPolicy.text(input.approach, true, 1000), cleaned);
        ObjectNode payload = mapper.valueToTree(observation);
        payload.put("observationId", observationId);
        payload.put("autoOutcome", outcome.name());
        return bridge.call("record", payload);
    }

    JsonNode search(JsonNode arguments) {
        Search input = decode(arguments, Search.class);
        List<Outcome> outcomes = input.outcomes == null ? List.of(Outcome.SUCCEEDED, Outcome.FAILED) : input.outcomes;
        require(!outcomes.isEmpty() && outcomes.size() <= 3 && !outcomes.stream().anyMatch(o -> o == null)
                && new HashSet<>(outcomes).size() == outcomes.size());
        int limit = input.limit == null ? 5 : input.limit;
        require(limit >= 1 && limit <= 20);
        Search cleaned = new Search(InputPolicy.id(input.serviceId, true), InputPolicy.id(input.environment, true),
                InputPolicy.text(input.query, true, 1000), input.method, InputPolicy.route(input.route, false),
                outcomes, limit);
        return bridge.call("search", mapper.valueToTree(cleaned));
    }

    JsonNode list(Map<String, String> parameters) {
        require(LIST_FIELDS.containsAll(parameters.keySet()));
        int limit;
        try {
            limit = Integer.parseInt(parameters.getOrDefault("limit", "25"));
        } catch (NumberFormatException ex) {
            throw new MemoryError(ErrorCode.INVALID_INPUT);
        }
        require(limit >= 1 && limit <= 100);
        String cursor = parameters.get("cursor");
        require(cursor == null || cursor.matches("[A-Za-z0-9_-]{1,256}"));
        ObjectNode payload = mapper.createObjectNode()
                .put("serviceId", InputPolicy.id(parameters.get("serviceId"), false))
                .put("environment", InputPolicy.id(parameters.get("environment"), false))
                .put("runId", InputPolicy.id(parameters.get("runId"), false))
                .put("outcome", enumName(Outcome.class, parameters.get("outcome")))
                .put("status", enumName(Status.class, parameters.getOrDefault("status", "ACTIVE")))
                .put("limit", limit)
                .put("cursor", cursor);
        return bridge.call("list", payload);
    }

    JsonNode get(String id) {
        return bridge.call("get", mapper.createObjectNode().put("observationId", uuid(id)));
    }

    JsonNode patch(String id, String etag, JsonNode arguments, String reviewer) {
        String observationId = uuid(id);
        if (etag == null) {
            throw new MemoryError(ErrorCode.REVISION_REQUIRED);
        }
        require(etag.matches("\"[1-9][0-9]{0,17}\""));
        require(arguments != null && arguments.isObject() && !arguments.isEmpty());
        boolean hasReview = arguments.has("manualReview");
        boolean hasStatus = arguments.has("status");
        require(arguments.size() == (hasReview ? 1 : 0) + (hasStatus ? 1 : 0));
        ObjectNode payload = mapper.createObjectNode().put("observationId", observationId)
                .put("revision", Long.parseLong(etag.substring(1, etag.length() - 1)));
        if (hasReview && arguments.get("manualReview").isNull()) {
            payload.putNull("manualReview");
        } else if (hasReview) {
            Review review = decode(arguments.get("manualReview"), Review.class);
            require(review.verdict != null);
            payload.putObject("manualReview").put("verdict", review.verdict.name())
                    .put("reason", InputPolicy.text(review.reason, true, 500)).put("reviewerId", reviewer);
        }
        if (hasStatus) {
            JsonNode status = arguments.get("status");
            require(status.isTextual());
            payload.put("status", enumName(Status.class, status.textValue()));
        }
        return bridge.call("patch", payload);
    }

    JsonNode health() {
        return bridge.call("health", mapper.createObjectNode());
    }

    static String etag(JsonNode card) {
        return "\"" + card.path("revision").asLong() + "\"";
    }

    private static String uuid(String value) {
        require(value != null && value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"));
        return UUID.fromString(value).toString();
    }

    private static String timestamp(String value) {
        require(value != null && value.matches(
                "[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?([Zz]|[+-][0-9]{2}:[0-9]{2})"));
        try {
            return OffsetDateTime.parse(value).toInstant().toString();
        } catch (DateTimeParseException ex) {
            throw new MemoryError(ErrorCode.INVALID_INPUT);
        }
    }

    private static boolean validCount(Integer value) {
        return value != null && value >= 0 && value <= 10_000;
    }

    private <T> T decode(JsonNode object, Class<T> type) {
        require(object != null && object.isObject());
        try {
            return mapper.treeToValue(object, type);
        } catch (Exception ex) {
            throw new MemoryError(ErrorCode.INVALID_INPUT);
        }
    }

    private static <E extends Enum<E>> String enumName(Class<E> type, String name) {
        if (name == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, name).name();
        } catch (IllegalArgumentException ex) {
            throw new MemoryError(ErrorCode.INVALID_INPUT);
        }
    }

    enum Method { GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS }
    enum Execution { COMPLETED, NOT_RUN, INFRA_ERROR, TIMEOUT }
    enum Outcome { SUCCEEDED, FAILED, INCONCLUSIVE }
    enum Status { ACTIVE, ARCHIVED }

    record Evidence(Execution executionStatus, Integer passedAssertions, Integer failedAssertions,
                    String expected, String observed, String reportRef) {}

    record Observation(String serviceId, String environment, String runId, String testCaseId,
                       Method method, String route, String observedAt, String task, String approach,
                       Evidence evidence) {}

    record Search(String serviceId, String environment, String query, Method method, String route,
                  List<Outcome> outcomes, Integer limit) {}

    record Review(Outcome verdict, String reason) {}
}
