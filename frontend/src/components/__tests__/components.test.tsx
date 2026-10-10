import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { ApiError } from "../../api/client";
import { ScoreDial, SeverityBadge, SeverityBar, SourceBadge } from "../badges";
import { SafeMarkdown } from "../SafeMarkdown";
import { ErrorState, LoadingBlock } from "../ui";

describe("badges", () => {
  it("renders severity labels in sentence case", () => {
    render(<SeverityBadge severity="CRITICAL" />);
    expect(screen.getByText("Critical")).toBeInTheDocument();
  });

  it("labels AI findings as unverified and static ones by analyzer", () => {
    render(
      <>
        <SourceBadge source="AI" analyzer="llm" verification="UNVERIFIED" />
        <SourceBadge source="STATIC_ANALYSIS" analyzer="pmd" verification="STATIC_ANALYZER_REPORTED" />
      </>,
    );
    expect(screen.getByText(/AI · unverified/)).toBeInTheDocument();
    expect(screen.getByText("PMD")).toBeInTheDocument();
  });

  it("exposes the score to assistive technology", () => {
    render(<ScoreDial score={74} />);
    expect(screen.getByRole("img", { name: "Score 74 of 100" })).toBeInTheDocument();
    render(<ScoreDial score={null} />);
    expect(screen.getByRole("img", { name: "No score" })).toBeInTheDocument();
  });

  it("summarises severity counts", () => {
    render(<SeverityBar counts={{ critical: 1, high: 2, medium: 0, low: 0, info: 0 }} />);
    expect(screen.getByLabelText(/1 critical, 2 high/)).toBeInTheDocument();
    render(<SeverityBar counts={{ critical: 0, high: 0, medium: 0, low: 0, info: 0 }} />);
    expect(screen.getByText("No findings")).toBeInTheDocument();
  });
});

describe("SafeMarkdown", () => {
  it("never renders raw HTML or script from AI or repository text", () => {
    const { container } = render(
      <SafeMarkdown>{'Hello <script>alert(1)</script><img src=x onerror="alert(2)"> **bold** [x](javascript:alert(3))'}</SafeMarkdown>,
    );
    expect(container.querySelector("script")).toBeNull();
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("strong")?.textContent).toBe("bold");
    const link = container.querySelector("a");
    expect(link?.getAttribute("href") ?? "").not.toMatch(/javascript/i);
  });

  it("opens http links safely", () => {
    const { container } = render(<SafeMarkdown>{"[docs](https://example.com)"}</SafeMarkdown>);
    const link = container.querySelector("a")!;
    expect(link.getAttribute("rel")).toContain("noopener");
    expect(link.getAttribute("target")).toBe("_blank");
  });
});

describe("states", () => {
  it("shows actionable guidance for known error codes", () => {
    render(<ErrorState error={new ApiError(401, "GITHUB_REAUTH_REQUIRED", "Your GitHub authorization expired.", {}, "corr-1234")} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Your GitHub authorization expired.");
    expect(screen.getByText(/sign in with github again/i)).toBeInTheDocument();
    expect(screen.getByText("corr-1234")).toBeInTheDocument();
  });

  it("renders an accessible loading placeholder", () => {
    render(<LoadingBlock label="Loading reviews" />);
    expect(screen.getByRole("status", { name: "Loading reviews" })).toBeInTheDocument();
  });
});
