package local.memory;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
final class MemoryController {
    private final MemoryService service;

    MemoryController(MemoryService service) {
        this.service = service;
    }

    @GetMapping("/health")
    JsonNode health() {
        return service.health();
    }

    @PutMapping(value = "/api/v1/memories/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    JsonNode record(@PathVariable String id, @RequestBody JsonNode input) {
        return service.record(id, input);
    }

    @PostMapping(value = "/api/v1/memories/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    JsonNode search(@RequestBody JsonNode input) {
        return service.search(input);
    }

    @GetMapping("/api/v1/memories")
    JsonNode list(@RequestParam Map<String, String> filters) {
        return service.list(filters);
    }

    @GetMapping("/api/v1/memories/{id}")
    ResponseEntity<JsonNode> get(@PathVariable String id) {
        JsonNode item = service.get(id);
        return ResponseEntity.ok().eTag(MemoryService.etag(item)).body(item);
    }

    @PatchMapping(value = "/api/v1/memories/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> patch(@PathVariable String id, @RequestHeader(value = "If-Match", required = false) String etag,
                                   @RequestBody JsonNode input, Principal principal) {
        JsonNode item = service.patch(id, etag, input, principal.getName());
        return ResponseEntity.ok().eTag(MemoryService.etag(item)).body(item);
    }

    @ExceptionHandler(MemoryError.class)
    ResponseEntity<MemoryError.Body> memoryError(MemoryError error) {
        return ResponseEntity.status(error.code.status).body(error.body());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<MemoryError.Body> unreadable() {
        return memoryError(new MemoryError(ErrorCode.INVALID_INPUT));
    }
}
