import type { ReactElement } from "react";
import { Link } from "react-router-dom";
import type { FieldFinding } from "../../api/generated";
import styles from "./ValidationFinding.module.css";

export interface OaFieldIssue {
  oaOrder: number;
  findings: FieldFinding[];
}

export interface ValidationFindingProps {
  index: number;
  issue: OaFieldIssue;
  operationId: string;
}

/** The oa[N].field prefix used by backend/.../ValidationService.java's fieldPrefix. */
const OA_FIELD_PATTERN = /^oa\[(\d+)\]\.(.+)$/;

export function parseOaFieldName(fieldName: string): { oaOrder: number; field: string } | null {
  const match = OA_FIELD_PATTERN.exec(fieldName);
  if (!match) {
    return null;
  }
  return { oaOrder: Number.parseInt(match[1], 10), field: match[2] };
}

export function ValidationFinding({ index, issue, operationId }: ValidationFindingProps): ReactElement {
  const summaries = issue.findings
    .filter((finding) => finding.status !== "PASSED")
    .map((finding) => finding.issue ?? finding.fieldName)
    .filter((text, position, all) => all.indexOf(text) === position);

  return (
    <li className={styles.issue}>
      <span className={styles.number} aria-hidden="true">
        {index + 1}
      </span>
      <div className={styles.body}>
        <p className={styles.title}>OA #{issue.oaOrder} — Input Issue</p>
        {summaries.length > 0 ? <p className={styles.detail}>{summaries.join("; ")}</p> : null}
        <Link className={styles.link} to={`/operations/${operationId}/input?oa=${issue.oaOrder}`}>
          Go to OA #{issue.oaOrder}
        </Link>
      </div>
    </li>
  );
}
