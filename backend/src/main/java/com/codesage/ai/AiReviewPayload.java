package com.codesage.ai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The strict schema the model must satisfy. Validated with Bean Validation before any data is used. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiReviewPayload(
        @NotNull @Size(max = 4000) String summary,
        @Min(0) @Max(100) Integer overallScore,
        @NotNull @Size(max = 60) List<@Valid @NotNull Finding> findings) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Finding(
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Pattern(regexp = "BUG|SECURITY|PERFORMANCE|RELIABILITY|MAINTAINABILITY|CODE_QUALITY|TESTING|BEST_PRACTICE") String category,
            @NotBlank @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW|INFO") String severity,
            @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
            @NotBlank @Size(max = 1000) String filePath,
            @Min(1) Integer startLine,
            @Min(1) Integer endLine,
            @NotBlank @Size(max = 6000) String description,
            @Size(max = 3000) String impact,
            @Size(max = 3000) String recommendation,
            @Size(max = 6000) String suggestedCode,
            @Size(max = 100) String ruleId) {
    }
}
