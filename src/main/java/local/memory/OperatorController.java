package local.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

@Controller
final class OperatorController {
    private final MemoryService service;
    private final ObjectMapper mapper;

    OperatorController(MemoryService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/login")
    String login() {
        return "login";
    }

    @GetMapping("/")
    String home() {
        return "redirect:/ui/memories";
    }

    @GetMapping("/ui/memories")
    String list(@RequestParam Map<String, String> parameters, Model model) {
        Map<String, String> filters = new LinkedHashMap<>();
        parameters.forEach((name, value) -> {
            if (!value.isBlank()) {
                filters.put(name, value);
            }
        });
        JsonNode page = service.list(filters);
        model.addAttribute("filters", filters);
        model.addAttribute("items", modelValue(page).get("items"));
        if (page.hasNonNull("nextCursor")) {
            var next = UriComponentsBuilder.fromPath("/ui/memories");
            filters.forEach((key, value) -> {
                if (!key.equals("cursor")) {
                    next.queryParam(key, value);
                }
            });
            model.addAttribute("nextUrl", next.queryParam("cursor", page.path("nextCursor").asText())
                    .build().encode().toUriString());
        }
        return "memories";
    }

    @PostMapping("/ui/memories/search")
    String search(@RequestParam String serviceId, @RequestParam String environment, @RequestParam String query,
                  Model model) {
        JsonNode hits = service.search(mapper.createObjectNode().put("serviceId", serviceId)
                .put("environment", environment).put("query", query));
        model.addAttribute("items", modelValue(hits).get("items"));
        return "search";
    }

    @GetMapping("/ui/memories/{id}")
    String card(@PathVariable String id, Model model) {
        model.addAttribute("item", modelValue(service.get(id)));
        return "memory";
    }

    @PostMapping("/ui/memories/{id}")
    String change(@PathVariable String id, @RequestParam String revision, @RequestParam String action,
                  @RequestParam(required = false) String verdict, @RequestParam(required = false) String reason,
                  Principal principal) {
        ObjectNode patch = mapper.createObjectNode();
        switch (action) {
            case "review" -> patch.putObject("manualReview").put("verdict", verdict).put("reason", reason);
            case "clearReview" -> patch.putNull("manualReview");
            case "archive" -> patch.put("status", "ARCHIVED");
            case "restore" -> patch.put("status", "ACTIVE");
            default -> throw new MemoryError(ErrorCode.INVALID_INPUT);
        }
        JsonNode updated = service.patch(id, "\"" + revision + "\"", patch, principal.getName());
        return "redirect:/ui/memories/" + updated.path("observationId").asText();
    }

    @ExceptionHandler(MemoryError.class)
    ModelAndView error(MemoryError error) {
        return new ModelAndView("failure", Map.of("message", error.getMessage(), "requestId", error.requestId),
                HttpStatusCode.valueOf(error.code.status));
    }

    private Map<String, Object> modelValue(JsonNode result) {
        return mapper.convertValue(result, new TypeReference<>() {});
    }
}
