import type { ReactElement } from "react";
import styles from "./ContentPreview.module.css";

export interface ParsedContent {
  header: string;
  body: string;
}

/**
 * Mirrors backend/.../validation/application/ContentParser.java exactly:
 * the header is the first non-blank line, the body is everything after that
 * line's line separator, preserved verbatim (no trim/collapse). Kept in sync
 * deliberately rather than trimming client-side, per the "preserve multiline
 * content exactly" constraint.
 */
export function parseContent(content: string): ParsedContent {
  if (!content) {
    return { header: "", body: "" };
  }

  let index = 0;
  while (index < content.length) {
    const lineStart = index;
    while (index < content.length && content[index] !== "\n" && content[index] !== "\r") {
      index++;
    }
    const lineEnd = index;
    const line = content.slice(lineStart, lineEnd);
    if (line.trim().length > 0) {
      let bodyStart = index;
      if (content[bodyStart] === "\r") {
        bodyStart++;
      }
      if (content[bodyStart] === "\n") {
        bodyStart++;
      }
      return { header: line, body: content.slice(bodyStart) };
    }
    if (content[index] === "\r") {
      index++;
    }
    if (content[index] === "\n") {
      index++;
    }
  }
  return { header: "", body: "" };
}

export interface ContentPreviewProps {
  content: string;
}

export function ContentPreview({ content }: ContentPreviewProps): ReactElement {
  const { header, body } = parseContent(content);
  return (
    <div className={styles.preview}>
      <div className={styles.row}>
        <span className={styles.label}>Header preview</span>
        <span className={`${styles.value} ${styles.headerValue}`} data-testid="content-header-preview">
          {header}
        </span>
      </div>
      <div className={styles.row}>
        <span className={styles.label}>Body preview</span>
        <span className={styles.value} data-testid="content-body-preview">
          {body}
        </span>
      </div>
    </div>
  );
}
