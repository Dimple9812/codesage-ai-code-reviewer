import Editor, { loader, type OnMount } from "@monaco-editor/react";
import * as monaco from "monaco-editor";
import editorWorker from "monaco-editor/esm/vs/editor/editor.worker?worker";
import { useEffect, useRef } from "react";

import type { Finding } from "../api/types";
import { SEV_HEX } from "./badges";

// Load Monaco from the local bundle (no CDN), with a single generic worker: the viewer is read-only.
self.MonacoEnvironment = { getWorker: () => new editorWorker() };
loader.config({ monaco });

monaco.editor.defineTheme("codesage", {
  base: "vs-dark",
  inherit: true,
  rules: [
    { token: "comment", foreground: "66788c", fontStyle: "italic" },
    { token: "keyword", foreground: "8cc7a8" },
    { token: "string", foreground: "e7cf62" },
    { token: "number", foreground: "f0a35e" },
  ],
  colors: {
    "editor.background": "#0e1621",
    "editor.lineHighlightBackground": "#1b2836",
    "editorLineNumber.foreground": "#4d5f73",
    "editorLineNumber.activeForeground": "#e4ebf3",
    "editorGutter.background": "#0e1621",
    "editor.selectionBackground": "#2e5543",
  },
});

export interface CodeViewerProps {
  path: string;
  language: string;
  content: string;
  findings: Finding[];
  focusLine: number | null;
  changedLines?: Set<number>;
  onSelectLine?: (line: number) => void;
}

export default function CodeViewer({ path, language, content, findings, focusLine, changedLines, onSelectLine }: CodeViewerProps) {
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const decorations = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);

  const onMount: OnMount = (editor) => {
    editorRef.current = editor as unknown as monaco.editor.IStandaloneCodeEditor;
    decorations.current = editorRef.current.createDecorationsCollection([]);
    editor.onMouseDown((e) => {
      const line = e.target.position?.lineNumber;
      if (line && onSelectLine) onSelectLine(line);
    });
    applyDecorations();
    reveal();
  };

  function applyDecorations() {
    if (!decorations.current) return;
    const items: monaco.editor.IModelDeltaDecoration[] = [];
    changedLines?.forEach((line) =>
      items.push({ range: new monaco.Range(line, 1, line, 1), options: { isWholeLine: true, linesDecorationsClassName: "cs-changed-line" } }),
    );
    for (const f of findings) {
      if (!f.startLine) continue;
      items.push({
        range: new monaco.Range(f.startLine, 1, f.endLine ?? f.startLine, 1),
        options: {
          isWholeLine: true,
          className: `cs-finding-${f.severity.toLowerCase()}`,
          glyphMarginClassName: `cs-glyph-${f.severity.toLowerCase()}`,
          hoverMessage: { value: `**${f.severity}** ${f.title}` },
          overviewRuler: { color: SEV_HEX[f.severity], position: monaco.editor.OverviewRulerLane.Right },
        },
      });
    }
    decorations.current.set(items);
  }

  function reveal() {
    const editor = editorRef.current;
    if (!editor || !focusLine) return;
    editor.revealLineInCenter(focusLine);
    editor.setSelection(new monaco.Range(focusLine, 1, focusLine, 1));
  }

  useEffect(applyDecorations, [findings, changedLines]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(reveal, [focusLine, path]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <Editor
      path={path}
      language={language}
      value={content}
      theme="codesage"
      onMount={onMount}
      options={{
        readOnly: true,
        domReadOnly: true,
        minimap: { enabled: false },
        glyphMargin: true,
        fontFamily: "JetBrains Mono, ui-monospace, monospace",
        fontSize: 13,
        lineHeight: 20,
        scrollBeyondLastLine: false,
        renderLineHighlight: "line",
        automaticLayout: true,
        wordWrap: "off",
      }}
      loading={<div className="p-4 text-sm text-muted">Loading editor…</div>}
    />
  );
}
