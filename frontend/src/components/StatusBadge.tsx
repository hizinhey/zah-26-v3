import type { ReactElement } from "react";
import styles from "./StatusBadge.module.css";

/**
 * Union of every status enum from contracts/schemas/hub-envelope-v1.json
 * (FieldStatus, OperationStatus, TestCaseStatus, TestResultStatus) plus ErrorCategory,
 * so a single badge component can represent any status the API returns.
 */
export type Status =
  // FieldStatus
  | "PASSED"
  | "WARNING"
  | "FAILED"
  | "UNABLE_TO_CHECK"
  | "INVALID"
  // OperationStatus (additional values not already listed above)
  | "DRAFT"
  | "VALIDATING"
  | "VALIDATION_FAILED"
  | "VALIDATED"
  | "GENERATING"
  | "GENERATION_FAILED"
  | "READY_FOR_APPROVAL"
  | "APPROVED"
  | "QUEUED"
  | "RUNNING"
  | "ERROR"
  // TestCaseStatus (additional values not already listed above)
  | "PENDING"
  | "READY"
  // ErrorCategory
  | "ASSERTION_FAILURE"
  | "INFRASTRUCTURE"
  | "TIMEOUT"
  | "CONFIGURATION"
  | "UNKNOWN";

type Tone = "success" | "warning" | "danger" | "info" | "neutral";

const STATUS_TONE: Record<Status, Tone> = {
  PASSED: "success",
  VALIDATED: "success",
  APPROVED: "success",
  READY: "success",

  WARNING: "warning",

  FAILED: "danger",
  VALIDATION_FAILED: "danger",
  GENERATION_FAILED: "danger",
  ERROR: "danger",
  INVALID: "danger",
  ASSERTION_FAILURE: "danger",
  INFRASTRUCTURE: "danger",
  TIMEOUT: "danger",
  CONFIGURATION: "danger",

  VALIDATING: "info",
  GENERATING: "info",
  QUEUED: "info",
  RUNNING: "info",
  READY_FOR_APPROVAL: "info",

  DRAFT: "neutral",
  PENDING: "neutral",
  UNABLE_TO_CHECK: "neutral",
  UNKNOWN: "neutral",
};

function humanize(status: string): string {
  return status
    .toLowerCase()
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

export interface StatusBadgeProps {
  status: Status;
  label?: string;
}

export function StatusBadge({ status, label }: StatusBadgeProps): ReactElement {
  const tone = STATUS_TONE[status] ?? "neutral";
  return (
    <span className={`${styles.badge} ${styles[tone]}`} role="status">
      {label ?? humanize(status)}
    </span>
  );
}
