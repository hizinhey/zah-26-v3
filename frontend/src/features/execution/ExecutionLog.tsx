import type { ReactElement } from "react";
import type { LogEntry } from "./executionState";
import styles from "./ExecuteScreen.module.css";

export interface ExecutionLogProps {
  logs: LogEntry[];
}

/** Time/message log table fed by JOB_PROGRESS and TEST_RESULT envelopes, in arrival order. */
export function ExecutionLog({ logs }: ExecutionLogProps): ReactElement {
  if (logs.length === 0) {
    return <p className={styles.empty}>No log entries yet.</p>;
  }

  return (
    <table className={styles.table}>
      <thead>
        <tr>
          <th>Time</th>
          <th>Message</th>
        </tr>
      </thead>
      <tbody>
        {logs.map((entry) => (
          <tr key={entry.key}>
            <td>{entry.time}</td>
            <td>{entry.message}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
