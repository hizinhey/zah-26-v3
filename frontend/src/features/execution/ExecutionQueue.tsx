import type { ReactElement } from "react";
import { StatusBadge } from "../../components/StatusBadge";
import styles from "./ExecuteScreen.module.css";

export type QueueStatus = "QUEUED" | "RUNNING" | "DONE";

export interface QueueOa {
  oaOrder: number;
  aggregateStatus: QueueStatus;
}

export interface ExecutionQueueProps {
  oas: QueueOa[];
}

const STATUS_LABEL: Record<QueueStatus, string> = {
  QUEUED: "Queued",
  RUNNING: "Running",
  DONE: "Done",
};

/** Left-hand execution queue: OAs run strictly in OA-order sequence, one at a time. */
export function ExecutionQueue({ oas }: ExecutionQueueProps): ReactElement {
  return (
    <ol className={styles.queueList} aria-label="Execution Queue (by OA Sequence)">
      {oas.map((oa, index) => (
        <li
          key={oa.oaOrder}
          className={`${styles.queueItem} ${oa.aggregateStatus === "RUNNING" ? styles.queueItemActive : ""}`}
        >
          <span className={styles.queueIndex}>{index + 1}</span>
          <div className={styles.queueBody}>
            <span className={styles.queueTitle}>OA #{oa.oaOrder}</span>
            <span className={styles.queuePlatform}>Android</span>
          </div>
          <StatusBadge
            status={oa.aggregateStatus === "DONE" ? "PASSED" : oa.aggregateStatus}
            label={STATUS_LABEL[oa.aggregateStatus]}
          />
        </li>
      ))}
    </ol>
  );
}
