package com.codesage.webhook;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.codesage.common.Texts;
import com.codesage.github.GitHubModels;
import com.codesage.pullrequest.PullRequestRecord;
import com.codesage.repository.GitRepository;
import com.codesage.repository.GitRepositoryRepository;
import com.codesage.review.CodeReview;
import com.codesage.review.CodeReviewRepository;
import com.codesage.review.ReviewJobRepository;
import com.codesage.review.ReviewJobService;
import com.codesage.review.ReviewService;
import com.codesage.review.ReviewStatus;
import com.codesage.review.ReviewType;
import com.codesage.review.TriggerSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Processes verified GitHub webhook deliveries. Deliveries are recorded by their unique delivery id
 * (duplicate deliveries are ignored); review jobs use an idempotency key derived from repository,
 * PR number and head commit, so retried or re-sent events never queue the same review twice.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final Set<String> REVIEW_ACTIONS = Set.of("opened", "synchronize", "reopened");

    public enum Result { PROCESSED, DUPLICATE, IGNORED }

    private final WebhookEventRepository events;
    private final GitRepositoryRepository repositories;
    private final CodeReviewRepository reviews;
    private final ReviewJobRepository jobs;
    private final ReviewJobService jobService;
    private final ReviewService reviewService;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public WebhookService(WebhookEventRepository events, GitRepositoryRepository repositories,
                          CodeReviewRepository reviews, ReviewJobRepository jobs, ReviewJobService jobService,
                          ReviewService reviewService, ObjectMapper mapper, TransactionTemplate tx) {
        this.events = events;
        this.repositories = repositories;
        this.reviews = reviews;
        this.jobs = jobs;
        this.jobService = jobService;
        this.reviewService = reviewService;
        this.mapper = mapper;
        this.tx = tx;
    }

    public Result handle(String deliveryId, String eventType, byte[] payload) {
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (Exception e) {
            throw com.codesage.exception.ApiException.badRequest("Webhook payload is not valid JSON");
        }
        WebhookEvent event = record(deliveryId, eventType, root);
        if (event == null) {
            return Result.DUPLICATE;
        }
        try {
            Result result = process(event, eventType, root);
            return result;
        } catch (RuntimeException e) {
            log.error("Webhook delivery {} failed", deliveryId, e);
            tx.executeWithoutResult(s -> events.findById(event.getId()).ifPresent(ev -> {
                ev.setStatus(WebhookEvent.Status.FAILED);
                ev.setError(Texts.truncate(e.getMessage(), 1900));
                ev.setProcessedAt(Instant.now());
            }));
            throw e;
        }
    }

    /** Inserts the delivery; returns null if it was already processed (duplicate). Failed ones may be retried. */
    private WebhookEvent record(String deliveryId, String eventType, JsonNode root) {
        try {
            return tx.execute(s -> {
                var existing = events.findByDeliveryId(deliveryId);
                if (existing.isPresent()) {
                    WebhookEvent ev = existing.get();
                    if (ev.getStatus() != WebhookEvent.Status.FAILED) {
                        return null;
                    }
                    ev.setStatus(WebhookEvent.Status.RECEIVED); // redelivery of a failed event
                    ev.setError(null);
                    return ev;
                }
                WebhookEvent ev = new WebhookEvent();
                ev.setDeliveryId(deliveryId);
                ev.setEventType(Texts.truncate(eventType, 60));
                ev.setAction(Texts.truncate(root.path("action").asText(null), 60));
                JsonNode repo = root.path("repository");
                if (repo.has("id")) {
                    ev.setRepositoryGithubId(repo.path("id").asLong());
                    ev.setRepositoryFullName(Texts.truncate(repo.path("full_name").asText(null), 300));
                }
                JsonNode pr = root.path("pull_request");
                if (pr.has("number")) {
                    ev.setPrNumber(pr.path("number").asInt());
                    ev.setHeadSha(Texts.truncate(pr.path("head").path("sha").asText(null), 64));
                }
                ev.setStatus(WebhookEvent.Status.RECEIVED);
                return events.saveAndFlush(ev);
            });
        } catch (DataIntegrityViolationException e) {
            return null; // concurrent duplicate delivery lost the unique-constraint race
        }
    }

    private Result process(WebhookEvent event, String eventType, JsonNode root) {
        String action = root.path("action").asText("");
        if (!"pull_request".equals(eventType) || !REVIEW_ACTIONS.contains(action)) {
            mark(event, WebhookEvent.Status.IGNORED, 0);
            return Result.IGNORED;
        }
        GitHubModels.Pull pull;
        try {
            pull = mapper.treeToValue(root.path("pull_request"), GitHubModels.Pull.class);
        } catch (Exception e) {
            throw com.codesage.exception.ApiException.badRequest("Malformed pull_request payload");
        }
        long repoGithubId = root.path("repository").path("id").asLong();
        List<GitRepository> subscribed = repositories.findByGithubIdAndAutoReviewTrue(repoGithubId);
        int queued = 0;
        for (GitRepository repo : subscribed) {
            Boolean created = tx.execute(s -> enqueue(repo, pull));
            if (Boolean.TRUE.equals(created)) {
                queued++;
            }
        }
        mark(event, subscribed.isEmpty() ? WebhookEvent.Status.IGNORED : WebhookEvent.Status.PROCESSED, queued);
        return subscribed.isEmpty() ? Result.IGNORED : Result.PROCESSED;
    }

    private boolean enqueue(GitRepository repo, GitHubModels.Pull pull) {
        String headSha = pull.head() == null ? "unknown" : pull.head().sha();
        String key = "webhook:" + repo.getUserId() + ":" + repo.getGithubId() + ":" + pull.number() + ":" + headSha;
        if (jobs.findByIdempotencyKey(key).isPresent()) {
            return false;
        }
        PullRequestRecord pr = reviewService.upsertPullRequest(repo.getId(), pull);
        CodeReview review = new CodeReview();
        review.setUserId(repo.getUserId());
        review.setRepositoryId(repo.getId());
        review.setPullRequestId(pr.getId());
        review.setType(ReviewType.PULL_REQUEST);
        review.setBranch(pr.getHeadRef());
        review.setCommitSha(headSha);
        review.setStatus(ReviewStatus.QUEUED);
        review.setTriggerSource(TriggerSource.WEBHOOK);
        review = reviews.save(review);
        jobService.enqueue(review.getId(), key);
        return true;
    }

    private void mark(WebhookEvent event, WebhookEvent.Status status, int queued) {
        tx.executeWithoutResult(s -> events.findById(event.getId()).ifPresent(ev -> {
            ev.setStatus(status);
            ev.setReviewsQueued(queued);
            ev.setProcessedAt(Instant.now());
        }));
    }
}
