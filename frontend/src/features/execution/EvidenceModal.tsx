import { useEffect, type ReactElement } from "react";
import { useQuery } from "@tanstack/react-query";
import { apiClient } from "../../api/client";
import styles from "./EvidenceModal.module.css";

export interface EvidenceModalProps {
  testResultId: string;
  onClose: () => void;
}

export function EvidenceModal({ testResultId, onClose }: EvidenceModalProps): ReactElement {
  const query = useQuery({
    queryKey: ["evidence", testResultId] as const,
    queryFn: () => apiClient.listEvidence(testResultId),
  });

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent): void {
      if (event.key === "Escape") {
        onClose();
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  const items = query.data ?? [];

  return (
    <div className={styles.overlay} role="presentation" onClick={onClose}>
      <div
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-label="Evidence"
        onClick={(event) => event.stopPropagation()}
      >
        <div className={styles.header}>
          <h2 className={styles.title}>Evidence</h2>
          <button type="button" className={styles.closeButton} onClick={onClose} aria-label="Close">
            ×
          </button>
        </div>

        {query.isLoading ? <p className={styles.status}>Loading…</p> : null}
        {query.isError ? <p className={styles.status}>Could not load evidence.</p> : null}
        {query.isSuccess && items.length === 0 ? (
          <p className={styles.status}>No evidence available.</p>
        ) : null}

        <div className={styles.items}>
          {items.map((item) => (
            <EvidenceItemView key={item.id} item={item} />
          ))}
        </div>
      </div>
    </div>
  );
}

function EvidenceItemView({ item }: { item: { id: string; evidenceType: string; createdAt: string } }): ReactElement {
  const contentUrl = `/api/v1/evidence/${item.id}/content`;
  return (
    <div className={styles.item}>
      <p className={styles.itemMeta}>
        {item.evidenceType} — {new Date(item.createdAt).toLocaleString()}
      </p>
      {item.evidenceType === "LOG" ? (
        <LogContent url={contentUrl} />
      ) : (
        <img className={styles.screenshot} src={contentUrl} alt={`${item.evidenceType} evidence`} />
      )}
    </div>
  );
}

function LogContent({ url }: { url: string }): ReactElement {
  const query = useQuery({
    queryKey: ["evidence-log", url] as const,
    queryFn: async () => {
      const response = await fetch(url);
      if (!response.ok) {
        throw new Error(`Failed to load log: ${response.status}`);
      }
      return response.text();
    },
  });
  if (query.isLoading) {
    return <p className={styles.status}>Loading log…</p>;
  }
  if (query.isError) {
    return <p className={styles.status}>Could not load log.</p>;
  }
  return <pre className={styles.log}>{query.data}</pre>;
}
