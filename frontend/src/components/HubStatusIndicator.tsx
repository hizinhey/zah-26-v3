import type { ReactElement } from "react";
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "../api/client";
import type { HubPlatformStatus, HubSummary } from "../api/generated";
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

function PlatformRow({ status }: { status: HubPlatformStatus }): ReactElement {
  return (
    <div className={styles.platformBlock}>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Platform</span>
        <span className={styles.rowValue}>{status.platform}</span>
      </div>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Status</span>
        <span className={styles.rowValue}>{status.connectionStatus}</span>
      </div>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Session</span>
        <span className={styles.rowValue}>{status.transport}</span>
      </div>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Device</span>
        <span className={styles.rowValue}>{status.deviceReady ? "Ready" : "Not ready"}</span>
      </div>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Runner</span>
        <span className={styles.rowValue}>{status.runnerReady ? "Ready" : "Not ready"}</span>
      </div>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Last heartbeat</span>
        <span className={styles.rowValue}>{formatHeartbeat(status.lastHeartbeatAt)}</span>
      </div>
    </div>
  );
}

export function HubStatusIndicator(): ReactElement {
  const { data: hubs } = useQuery({
    queryKey: ["hubs"],
    queryFn: () => apiClient.listHubs(),
    refetchInterval: POLL_INTERVAL_MS,
  });

  const hub: HubSummary | undefined = hubs?.[0];
  const isOnline = hub?.platforms.some((platform) => platform.connectionStatus === "ONLINE") ?? false;
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
            {hub.platforms.map((status) => (
              <PlatformRow key={status.platform} status={status} />
            ))}
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
