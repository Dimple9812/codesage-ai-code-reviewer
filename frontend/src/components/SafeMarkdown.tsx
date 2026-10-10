import Markdown from "react-markdown";

/**
 * Renders AI/analyzer text as Markdown. react-markdown never renders raw HTML (no rehype-raw),
 * and links are restricted to http(s), so repository-controlled text cannot inject script.
 */
export function SafeMarkdown({ children }: { children: string | null | undefined }) {
  if (!children) return null;
  return (
    <div className="prose-finding text-sm leading-relaxed text-ink/90">
      <Markdown
        skipHtml
        urlTransform={(url) => (/^https?:\/\//i.test(url) ? url : "")}
        components={{
          a: ({ href, children: c }) => (
            <a href={href} target="_blank" rel="noopener noreferrer nofollow">
              {c}
            </a>
          ),
          img: () => null,
        }}
      >
        {children}
      </Markdown>
    </div>
  );
}
