package com.codesage.webhook;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codesage.config.AppProperties;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/webhooks")
@Tag(name = "Webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final int MAX_PAYLOAD = 5 * 1024 * 1024;

    private final AppProperties props;
    private final WebhookService service;

    public WebhookController(AppProperties props, WebhookService service) {
        this.props = props;
        this.service = service;
    }

    @Operation(summary = "GitHub webhook receiver (pull_request opened/synchronize/reopened). Requires a valid X-Hub-Signature-256.")
    @PostMapping("/github")
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestBody(required = false) byte[] payload) {
        if (!props.github().webhookConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "error", "message", "Webhook secret is not configured on the server"));
        }
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Missing or oversized payload"));
        }
        if (!WebhookSignatureVerifier.isValid(props.github().webhookSecret(), payload, signature)) {
            log.warn("Rejected webhook delivery with invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Invalid signature"));
        }
        if (event == null || deliveryId == null || !deliveryId.matches("^[A-Za-z0-9-]{1,100}$")) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Missing GitHub delivery headers"));
        }
        if ("ping".equals(event)) {
            return ResponseEntity.ok(Map.of("status", "pong"));
        }
        WebhookService.Result result = service.handle(deliveryId, event, payload);
        return ResponseEntity.status(result == WebhookService.Result.PROCESSED ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(Map.of("status", result.name().toLowerCase()));
    }
}
