import { useEffect, useMemo, useState, type ReactElement } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { StepProgress } from "../../components/StepProgress";
import { DisabledReason } from "../../components/DisabledReason";
import { OPERATION_STEPS } from "../../app/router";
import { isRevisionConflict, useOperationQuery } from "../operations/useOperation";
import { useApprovePlanMutation, useGeneratePlanMutation, usePlanQuery } from "./usePlan";
import { TestCaseRow } from "./TestCaseRow";
import type { GeneratedTestCase } from "../../api/generated";
import styles from "./GenerateScreen.module.css";

interface OaGroup {
  oaOrder: number;
  cases: GeneratedTestCase[];
}

function groupByOa(cases: GeneratedTestCase[]): OaGroup[] {
  const byOa = new Map<number, GeneratedTestCase[]>();
  for (const testCase of cases) {
    const existing = byOa.get(testCase.oaOrder) ?? [];
    existing.push(testCase);
    byOa.set(testCase.oaOrder, existing);
  }
  return Array.from(byOa.entries())
    .sort(([a], [b]) => a - b)
    .map(([oaOrder, oaCases]) => ({
      oaOrder,
      cases: [...oaCases].sort((a, b) => a.order - b.order),
    }));
}

export function GenerateScreen(): ReactElement {
  const params = useParams<{ operationId: string }>();
  const operationId = params.operationId ?? "";
  const navigate = useNavigate();

  const operationQuery = useOperationQuery(operationId);
  const planQuery = usePlanQuery(operationId);
  const generatePlan = useGeneratePlanMutation(operationId);
  const approvePlan = useApprovePlanMutation(operationId);

  const [conflictMessage, setConflictMessage] = useState<string | null>(null);
  const [expandedOas, setExpandedOas] = useState<Set<number>>(new Set([1]));

  const plan = planQuery.data;
  const operationRevision = operationQuery.data?.revision;

  // Generation happens automatically on arrival at this step: the plan is
  // wholly server-derived (fixed five cases per OA, in fixed order), there is
  // nothing for the user to configure before generating it.
  useEffect(() => {
    if (!plan && operationRevision !== undefined && !generatePlan.isPending && !generatePlan.isError) {
      generatePlan.mutate(operationRevision);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [plan, operationRevision]);

  const groups = useMemo(() => (plan ? groupByOa(plan.testCases) : []), [plan]);

  function toggleOa(oaOrder: number): void {
    setExpandedOas((current) => {
      const next = new Set(current);
      if (next.has(oaOrder)) {
        next.delete(oaOrder);
      } else {
        next.add(oaOrder);
      }
      return next;
    });
  }

  const allCasesReady = plan ? plan.testCases.every((testCase) => testCase.status === "READY") : false;
  const confirmDisabledReason = !plan
    ? "Generate test cases before confirming."
    : plan.approvalStatus === "APPROVED"
      ? null
      : !allCasesReady
        ? "Every test case must be Ready before confirming."
        : null;

  async function handleConfirm(): Promise<void> {
    if (!plan || !allCasesReady) {
      return;
    }
    if (plan.approvalStatus === "APPROVED") {
      navigate(`/operations/${operationId}/execute`);
      return;
    }
    setConflictMessage(null);
    try {
      await approvePlan.mutateAsync({ planId: plan.planId, expectedRevision: plan.sourceRevision });
      navigate(`/operations/${operationId}/execute`);
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

  return (
    <div>
      <h1 className="ops-page-title">Test Operations</h1>
      <StepProgress steps={OPERATION_STEPS} currentStepId="generate" />

      {conflictMessage ? (
        <p className={styles.conflict} role="alert">
          {conflictMessage}
        </p>
      ) : null}

      {generatePlan.isPending ? <p className={styles.empty}>Generating test cases…</p> : null}
      {generatePlan.isError ? (
        <p className={styles.conflict} role="alert">
          Failed to generate test cases. {(generatePlan.error as Error).message}
        </p>
      ) : null}

      <div className={styles.groups}>
        {groups.map((group) => {
          const expanded = expandedOas.has(group.oaOrder);
          return (
            <section key={group.oaOrder} className={styles.card}>
              <header className={styles.cardHeader}>
                <div className={styles.cardHeaderLeft}>
                  <h2 className={styles.oaTitle}>OA #{group.oaOrder}</h2>
                  <span className={styles.platformChip}>Android</span>
                  <span className={styles.countChip}>{group.cases.length} test cases</span>
                </div>
                <button
                  type="button"
                  className={styles.expandToggle}
                  onClick={() => toggleOa(group.oaOrder)}
                  aria-expanded={expanded}
                  aria-label={`Toggle OA #${group.oaOrder} test cases`}
                >
                  {expanded ? "▲" : "▼"}
                </button>
              </header>
              {expanded ? (
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th>Test Case ID</th>
                      <th>Test Case Descriptions</th>
                      <th>Group</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {group.cases.map((testCase) => (
                      <TestCaseRow key={testCase.testCaseId} testCase={testCase} />
                    ))}
                  </tbody>
                </table>
              ) : null}
            </section>
          );
        })}
      </div>

      <div className={styles.actions}>
        <DisabledReason reason={confirmDisabledReason}>
          <button type="button" onClick={handleConfirm} disabled={approvePlan.isPending}>
            Confirm
          </button>
        </DisabledReason>
      </div>
    </div>
  );
}
