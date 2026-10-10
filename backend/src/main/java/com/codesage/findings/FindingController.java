package com.codesage.findings;

import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codesage.exception.ApiException;
import com.codesage.review.ReviewDtos.UpdateFindingRequest;
import com.codesage.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/findings")
@Tag(name = "Findings")
public class FindingController {

    private final ReviewFindingRepository findings;
    private final CurrentUser currentUser;

    public FindingController(ReviewFindingRepository findings, CurrentUser currentUser) {
        this.findings = findings;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Mark a finding as OPEN, RESOLVED or DISMISSED")
    @PatchMapping("/{findingId}")
    @Transactional
    public FindingDto update(@PathVariable UUID findingId, @Valid @RequestBody UpdateFindingRequest request) {
        ReviewFinding finding = findings.findOwned(findingId, currentUser.requireId())
                .orElseThrow(() -> ApiException.notFound("Finding"));
        finding.setStatus(request.status());
        return FindingDto.from(findings.save(finding));
    }
}
