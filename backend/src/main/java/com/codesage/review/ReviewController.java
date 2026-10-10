package com.codesage.review;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codesage.common.PageResponse;
import com.codesage.findings.FindingDto;
import com.codesage.findings.Severity;
import com.codesage.pullrequest.PublishCommentsService;
import com.codesage.review.ReviewDtos.ComparisonDto;
import com.codesage.review.ReviewDtos.CreateReviewRequest;
import com.codesage.review.ReviewDtos.FileContentDto;
import com.codesage.review.ReviewDtos.PublishCommentsRequest;
import com.codesage.review.ReviewDtos.PublishCommentsResponse;
import com.codesage.review.ReviewDtos.ReviewDetailDto;
import com.codesage.review.ReviewDtos.ReviewFileDto;
import com.codesage.review.ReviewDtos.ReviewStatusDto;
import com.codesage.review.ReviewDtos.ReviewSummaryDto;
import com.codesage.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/reviews")
@Tag(name = "Reviews")
public class ReviewController {

    private final ReviewService service;
    private final PublishCommentsService publisher;
    private final CurrentUser currentUser;

    public ReviewController(ReviewService service, PublishCommentsService publisher, CurrentUser currentUser) {
        this.service = service;
        this.publisher = publisher;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Queue a repository or pull request review (asynchronous). Supports an Idempotency-Key header.")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReviewSummaryDto create(@Valid @RequestBody CreateReviewRequest request,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.create(currentUser.requireId(), request, idempotencyKey);
    }

    @GetMapping
    public PageResponse<ReviewSummaryDto> list(@RequestParam(required = false) UUID repositoryId,
                                               @RequestParam(required = false) ReviewStatus status,
                                               @RequestParam(required = false) String branch,
                                               @RequestParam(required = false) Severity severity,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return service.list(currentUser.requireId(), repositoryId, status, branch, severity, from, to, page, size);
    }

    @GetMapping("/{reviewId}")
    public ReviewDetailDto get(@PathVariable UUID reviewId) {
        return service.detail(currentUser.requireId(), reviewId);
    }

    @GetMapping("/{reviewId}/status")
    public ReviewStatusDto status(@PathVariable UUID reviewId) {
        return service.status(currentUser.requireId(), reviewId);
    }

    @PostMapping("/{reviewId}/cancel")
    public ReviewStatusDto cancel(@PathVariable UUID reviewId) {
        return service.cancel(currentUser.requireId(), reviewId);
    }

    @GetMapping("/{reviewId}/findings")
    public List<FindingDto> findings(@PathVariable UUID reviewId,
                                     @RequestParam(required = false) Severity severity,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) String filePath,
                                     @RequestParam(required = false) String source,
                                     @RequestParam(required = false) String status) {
        return service.findings(currentUser.requireId(), reviewId, severity, category, filePath, source, status);
    }

    @GetMapping("/{reviewId}/files")
    public List<ReviewFileDto> files(@PathVariable UUID reviewId) {
        return service.files(currentUser.requireId(), reviewId);
    }

    @Operation(summary = "File content at the reviewed commit, fetched live from GitHub")
    @GetMapping("/{reviewId}/files/content")
    public FileContentDto fileContent(@PathVariable UUID reviewId, @RequestParam String path) {
        return service.fileContent(currentUser.requireId(), reviewId, path);
    }

    @Operation(summary = "Compare with an earlier review: new, fixed and recurring findings")
    @GetMapping("/{reviewId}/compare")
    public ComparisonDto compare(@PathVariable UUID reviewId, @RequestParam(required = false) UUID baseReviewId) {
        return service.compare(currentUser.requireId(), reviewId, baseReviewId);
    }

    @Operation(summary = "Publish selected findings as PR review comments (requires confirm=true; COMMENT event only)")
    @PostMapping("/{reviewId}/publish-comments")
    public PublishCommentsResponse publish(@PathVariable UUID reviewId, @Valid @RequestBody PublishCommentsRequest request) {
        return publisher.publish(currentUser.requireId(), reviewId, request.findingIds());
    }
}
