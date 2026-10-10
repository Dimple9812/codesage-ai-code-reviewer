package com.codesage.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codesage.repository.RepositoryDtos.AutoReviewRequest;
import com.codesage.repository.RepositoryDtos.BranchDto;
import com.codesage.repository.RepositoryDtos.CommitDto;
import com.codesage.repository.RepositoryDtos.PullRequestDto;
import com.codesage.repository.RepositoryDtos.RepositoryDto;
import com.codesage.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/repositories")
@Tag(name = "Repositories")
public class RepositoryController {

    private final RepositoryService service;
    private final CurrentUser currentUser;

    public RepositoryController(RepositoryService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Repositories accessible to the signed-in GitHub user")
    @GetMapping
    public List<RepositoryDto> list(@RequestParam(required = false) @Size(max = 200) String q,
                                    @RequestParam(required = false) @Size(max = 100) String language,
                                    @RequestParam(defaultValue = "false") boolean refresh) {
        return service.list(currentUser.requireId(), q, language, refresh);
    }

    @GetMapping("/{repositoryId}")
    public RepositoryDto get(@PathVariable UUID repositoryId) {
        return service.get(currentUser.requireId(), repositoryId);
    }

    @GetMapping("/{repositoryId}/branches")
    public List<BranchDto> branches(@PathVariable UUID repositoryId) {
        return service.branches(currentUser.requireId(), repositoryId);
    }

    @GetMapping("/{repositoryId}/commits")
    public List<CommitDto> commits(@PathVariable UUID repositoryId,
                                   @RequestParam(required = false) @Size(max = 255) String branch) {
        return service.commits(currentUser.requireId(), repositoryId, branch);
    }

    @GetMapping("/{repositoryId}/pull-requests")
    public List<PullRequestDto> pullRequests(@PathVariable UUID repositoryId,
                                             @RequestParam(defaultValue = "open") String state) {
        return service.pullRequests(currentUser.requireId(), repositoryId, state);
    }

    @Operation(summary = "Enable/disable automatic reviews of pull requests delivered by GitHub webhooks")
    @PutMapping("/{repositoryId}/auto-review")
    public RepositoryDto autoReview(@PathVariable UUID repositoryId, @RequestBody AutoReviewRequest request) {
        return service.setAutoReview(currentUser.requireId(), repositoryId, request.enabled());
    }
}
