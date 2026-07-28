import type { ReactElement } from "react";
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "../api/client";
import type { HubSummary } from "../api/generated";
import styles from "./HubStatusIndicator.module.css";

const POLL_INTERVAL_MS = 10_000;

function formatHeartbeat(lastHeartbeatAt: string | null): string {
  if (!lastHeartbeatAt) {
    return "Never";
  }
  const seconds = Math.max(0, Math.round((Date.now() - new Date(lastHeartbeatAt).getTime()) / 1000));
  if (seconds < 60) {
    return `${seconds}s ago`;
  }
  const minutes = Math.round(seconds / 60);
  return `${minutes}m ago`;
}

export function HubStatusIndicator(): ReactElement {
  const { data: hubs } = useQuery({
    queryKey: ["hubs"],
    queryFn: () => apiClient.listHubs(),
    refetchInterval: POLL_INTERVAL_MS,
  });

  const hub: HubSummary | undefined = hubs?.[0];
  const isOnline = hub?.connectionStatus === "ONLINE";
  const dotClass = !hubs ? styles.unknown : hub && isOnline ? styles.online : styles.offline;
  const accessibleName = !hubs ? "Hub status unknown" : hub ? (isOnline ? "Hub online" : "Hub offline") : "No hub has ever connected";

  return (
    <div className={styles.wrapper} tabIndex={0}>
      <span className={`${styles.dot} ${dotClass}`} role="status" aria-label={accessibleName} />
      <div className={styles.panel}>
        <p className={styles.title}>Local Hub</p>
        {hub ? (
          <>
            <div className={styles.row}>
              <span className={styles.rowLabel}>Hub ID</span>
              <span className={styles.rowValue}>{hub.id}</span>
            </div>
            <div className={styles.row}>
              <span className={styles.rowLabel}>Status</span>
              <span className={styles.rowValue}>{hub.connectionStatus}</span>
            </div>
            <div className={styles.row}>
              <span className={styles.rowLabel}>Session</span>
              <span className={styles.rowValue}>{hub.transport}</span>
            </div>
            <div className={styles.row}>
              <span className={styles.rowLabel}>Platform</span>
              <span className={styles.rowValue}>{hub.platform}</span>
            </div>
            <div className={styles.row}>
              <span className={styles.rowLabel}>Device</span>
              <span className={styles.rowValue}>{hub.deviceReady ? "Ready" : "Not ready"}</span>
            </div>
            <div className={styles.row}>
              <span className={styles.rowLabel}>Runner</span>
              <span className={styles.rowValue}>{hub.runnerReady ? "Ready" : "Not ready"}</span>
            </div>
            <div className={styles.row}>
              <span className={styles.rowLabel}>Last heartbeat</span>
              <span className={styles.rowValue}>{formatHeartbeat(hub.lastHeartbeatAt)}</span>
            </div>
          </>
        ) : (
          <div className={styles.row}>
            <span className={styles.rowLabel}>No hub has ever connected</span>
          </div>
        )}
      </div>
    </div>
  );
}
