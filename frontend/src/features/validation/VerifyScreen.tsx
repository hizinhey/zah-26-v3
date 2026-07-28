import { useMemo, useState, type ReactElement } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { StepProgress } from "../../components/StepProgress";
import { OperationBreadcrumb } from "../../components/OperationBreadcrumb";
import { Card } from "../../components/Card";
import { StatusBadge, type Status } from "../../components/StatusBadge";
import { DisabledReason } from "../../components/DisabledReason";
import { AiIcon } from "../../components/icons";
import { OPERATION_STEPS } from "../../app/router";
import type { FieldFinding } from "../../api/generated";
import {
  isRevisionConflict,
  useOperationQuery,
  useValidateOperationMutation,
  useValidationQuery,
} from "../operations/useOperation";
import { ValidationFinding, parseOaFieldName, type OaFieldIssue } from "./ValidationFinding";
import styles from "./VerifyScreen.module.css";

// Display-only aggregation: this ranking is a client-side convenience for
// summarizing findings per OA in the table below. It does not gate
// navigation — the actual Generate gate always reads the API's
// `canGenerate`/`generateDisabledReasons` directly and never recomputes it.
const STATUS_SEVERITY: Record<string, number> = {
  FAILED: 4,
  INVALID: 4,
  WARNING: 3,
  UNABLE_TO_CHECK: 2,
  PASSED: 0,
};

interface OaRowSummary {
  oaOrder: number;
  inputComplete: boolean;
  aiValidationStatus: Status | "MISSING_INPUT";
  overallStatus: Status;
  findings: FieldFinding[];
}

function summarizeByOa(findings: FieldFinding[]): OaRowSummary[] {
  const byOa = new Map<number, FieldFinding[]>();
  for (const finding of findings) {
    const parsed = parseOaFieldName(finding.fieldName);
    if (!parsed) {
      continue;
    }
    const existing = byOa.get(parsed.oaOrder) ?? [];
    existing.push(finding);
    byOa.set(parsed.oaOrder, existing);
  }

  return Array.from(byOa.entries())
    .sort(([a], [b]) => a - b)
    .map(([oaOrder, oaFindings]) => {
      const requiredFindings = oaFindings.filter((finding) => finding.validatorType === "required");
      const otherFindings = oaFindings.filter((finding) => finding.validatorType !== "required");
      const inputComplete = requiredFindings.every((finding) => finding.status === "PASSED");

      if (!inputComplete) {
        return {
          oaOrder,
          inputComplete: false,
          aiValidationStatus: "MISSING_INPUT" as const,
          overallStatus: "FAILED" as Status,
          findings: oaFindings,
        };
      }

      const worst = otherFindings.reduce<FieldFinding | null>((current, finding) => {
        const currentSeverity = current ? (STATUS_SEVERITY[current.status] ?? 0) : -1;
        const findingSeverity = STATUS_SEVERITY[finding.status] ?? 0;
        return findingSeverity > currentSeverity ? finding : current;
      }, null);

      const aiValidationStatus: Status = worst ? (worst.status as Status) : "PASSED";
      const overallStatus: Status = aiValidationStatus === "PASSED" ? "PASSED" : "FAILED";

      return { oaOrder, inputComplete, aiValidationStatus, overallStatus, findings: oaFindings };
    });
}

export function VerifyScreen(): ReactElement {
  const params = useParams<{ operationId: string }>();
  const operationId = params.operationId ?? "";
  const navigate = useNavigate();

  const operationQuery = useOperationQuery(operationId);
  const validationQuery = useValidationQuery(operationId);
  const revalidate = useValidateOperationMutation(operationId);
  const [conflictMessage, setConflictMessage] = useState<string | null>(null);

  const validationRun = validationQuery.data;
  const rows = useMemo(
    () => (validationRun ? summarizeByOa(validationRun.findings) : []),
    [validationRun],
  );

  const issues: OaFieldIssue[] = useMemo(() => {
    if (!validationRun) {
      return [];
    }
    return rows
      .filter((row) => row.overallStatus !== "PASSED")
      .map((row) => ({ oaOrder: row.oaOrder, findings: row.findings }));
  }, [rows, validationRun]);

  const canGenerate = validationRun?.canGenerate ?? false;
  const generateDisabledReason =
    !validationRun
      ? "Run AI Validation to enable Generate."
      : canGenerate
        ? null
        : (validationRun.generateDisabledReasons[0] ??
          "Generate is only enabled once every OA has passed AI validation.");

  async function handleRecheck(): Promise<void> {
    setConflictMessage(null);
    const revision = operationQuery.data?.revision;
    if (revision === undefined) {
      return;
    }
    try {
      await revalidate.mutateAsync(revision);
    } catch (error) {
      if (isRevisionConflict(error)) {
        setConflictMessage(
          `This operation changed elsewhere (current revision ${error.body.currentRevision}). Refresh and try again.`,
        );
        await operationQuery.refetch();
        return;
      }
      throw error;
    }
  }

  function handleGenerate(): void {
    if (canGenerate) {
      navigate(`/operations/${operationId}/generate`);
    }
  }

  return (
    <div>
      <OperationBreadcrumb operationId={operationId} currentStepId="verify" />
      <h1 className="ops-page-title">Test Operations</h1>
      <StepProgress steps={OPERATION_STEPS} currentStepId="verify" />

      {conflictMessage ? (
        <p className={styles.conflict} role="alert">
          {conflictMessage}
        </p>
      ) : null}

      {validationRun && validationRun.status === "VALIDATION_FAILED" ? (
        <div className={styles.issueBanner} role="alert">
          <div>
            <p className={styles.issueBannerTitle}>Input Issues Found</p>
            <p>Một số OA chưa đầy đủ hoặc có lỗi. Vui lòng chỉnh sửa để tiếp tục.</p>
          </div>
          <button
            type="button"
            className={styles.recheckButton}
            onClick={handleRecheck}
            disabled={revalidate.isPending}
          >
            {revalidate.isPending ? "Re-checking…" : "Re-check"}
          </button>
        </div>
      ) : null}

      <div className={styles.layout}>
        <Card title="OA Summary">
          {rows.length === 0 ? (
            <p className={styles.empty}>
              No validation results yet.{" "}
              <button type="button" className={styles.recheckButton} onClick={handleRecheck}>
                Re-check
              </button>
            </p>
          ) : (
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>OA</th>
                  <th>Platform</th>
                  <th>Input Complete</th>
                  <th>AI Validation</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.oaOrder}>
                    <td>OA #{row.oaOrder}</td>
                    <td>Android</td>
                    <td>
                      {row.inputComplete ? (
                        <StatusBadge status="PASSED" label="Complete" />
                      ) : (
                        <StatusBadge status="WARNING" label="Missing Input" />
                      )}
                    </td>
                    <td>
                      {row.aiValidationStatus === "MISSING_INPUT" ? (
                        <StatusBadge status="WARNING" label="Missing Input" />
                      ) : (
                        <StatusBadge status={row.aiValidationStatus} />
                      )}
                    </td>
                    <td>
                      <StatusBadge status={row.overallStatus} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        <Card title={`Issues Found (${issues.length})`}>
          {issues.length === 0 ? (
            <p className={styles.empty}>No issues found.</p>
          ) : (
            <ul className={styles.issueList}>
              {issues.map((issue, index) => (
                <ValidationFinding
                  key={issue.oaOrder}
                  index={index}
                  issue={issue}
                  operationId={operationId}
                />
              ))}
            </ul>
          )}
        </Card>
      </div>

      <p className={styles.hint}>Sửa đổi hoặc bổ sung thông tin tại các OA bị lỗi và kiểm tra lại.</p>

      <div className={styles.actions}>
        <DisabledReason reason={generateDisabledReason}>
          <button type="button" className="ops-primary-button" onClick={handleGenerate}>
            <AiIcon />
            AI Generate
          </button>
        </DisabledReason>
        {!canGenerate ? (
          <p className={styles.generateHint}>
            Generate chỉ được bật khi tất cả OA đã Pass AI Validation.
          </p>
        ) : null}
      </div>
    </div>
  );
}
