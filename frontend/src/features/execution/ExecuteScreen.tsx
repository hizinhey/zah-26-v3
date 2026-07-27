import { useMemo, useReducer, useState, type ReactElement } from "react";
import { useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { StepProgress } from "../../components/StepProgress";
import { DisabledReason } from "../../components/DisabledReason";
import { StatusBadge } from "../../components/StatusBadge";
import { OPERATION_STEPS } from "../../app/router";
import { ApiClientError } from "../../api/client";
import { useOperationQuery } from "../operations/useOperation";
import { usePlanQuery } from "../generation/usePlan";
import { executionQueryKey, useExecutionQuery, useStartExecutionMutation } from "./useExecution";
import { useExecutionChannel } from "../../realtime/useExecutionChannel";
import { applyEnvelope, seedExecutionState, type ExecutionState } from "./executionState";
import { ExecutionQueue, type QueueOa, type QueueStatus } from "./ExecutionQueue";
import { ExecutionLog } from "./ExecutionLog";
import { TEST_CASE_CATALOG, testCaseLabel } from "../generation/testCaseCatalog";
import type { GeneratedTestCase, HubEnvelopeV1 } from "../../api/generated";
import styles from "./ExecuteScreen.module.css";

type Tab = "current" | "script" | "logs";

function browserExecutionChannelUrl(executionId: string): string {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  // No browser-facing WebSocket path is defined in contracts/openapi/opshub-v1.yaml yet
  // (only the Hub-facing /ws/v1/hubs/{hubId} exists); this follows the same
  // HubEnvelopeV1 envelope pattern over a plausible browser-scoped path, per
  // task guidance to build against the envelope contract and note the gap.
  return `${protocol}//${window.location.host}/ws/v1/executions/${executionId}`;
}

function groupByOa(cases: GeneratedTestCase[]): Map<number, GeneratedTestCase[]> {
  const byOa = new Map<number, GeneratedTestCase[]>();
  for (const testCase of cases) {
    const existing = byOa.get(testCase.oaOrder) ?? [];
    existing.push(testCase);
    byOa.set(testCase.oaOrder, existing);
  }
  for (const [, oaCases] of byOa) {
    oaCases.sort((a, b) => a.order - b.order);
  }
  return byOa;
}

function isTerminal(status: string): boolean {
  return status === "PASSED" || status === "FAILED" || status === "ERROR";
}

function reducer(state: ExecutionState, action: { type: "seed"; testCases: GeneratedTestCase[] } | { type: "envelope"; envelope: HubEnvelopeV1 }): ExecutionState {
  if (action.type === "seed") {
    return seedExecutionState(action.testCases);
  }
  return applyEnvelope(state, action.envelope);
}

export function ExecuteScreen(): ReactElement {
  const params = useParams<{ operationId: string }>();
  const operationId = params.operationId ?? "";
  const queryClient = useQueryClient();

  const operationQuery = useOperationQuery(operationId);
  const planQuery = usePlanQuery(operationId);
  const executionQuery = useExecutionQuery(operationId);
  const startExecution = useStartExecutionMutation(operationId);

  const [state, dispatch] = useReducer(reducer, INITIAL_STATE_FROM(planQuery.data?.testCases ?? []));
  const [tab, setTab] = useState<Tab>("current");
  const [startError, setStartError] = useState<string | null>(null);

  const plan = planQuery.data;
  const execution = executionQuery.data;

  const channelUrl = execution ? browserExecutionChannelUrl(execution.id) : null;
  const { connectionState, isPolling } = useExecutionChannel({
    url: channelUrl,
    queryClient,
    invalidateKey: executionQueryKey(operationId),
    onEnvelope: (envelope) => dispatch({ type: "envelope", envelope }),
  });

  const byOa = useMemo(() => groupByOa(plan?.testCases ?? []), [plan]);
  const oaOrders = useMemo(() => Array.from(byOa.keys()).sort((a, b) => a - b), [byOa]);

  const queueOas: QueueOa[] = useMemo(
    () =>
      oaOrders.map((oaOrder) => {
        const cases = byOa.get(oaOrder) ?? [];
        const statuses = cases.map((testCase) => state.cases[testCase.testCaseId]?.status ?? "PENDING");
        let aggregateStatus: QueueStatus = "QUEUED";
        if (statuses.some((status) => status === "RUNNING")) {
          aggregateStatus = "RUNNING";
        } else if (statuses.length > 0 && statuses.every((status) => isTerminal(status))) {
          aggregateStatus = "DONE";
        }
        return { oaOrder, aggregateStatus };
      }),
    [oaOrders, byOa, state.cases],
  );

  const currentOaOrder =
    queueOas.find((oa) => oa.aggregateStatus === "RUNNING")?.oaOrder ??
    queueOas.find((oa) => oa.aggregateStatus === "QUEUED")?.oaOrder ??
    oaOrders[oaOrders.length - 1] ??
    null;

  const currentOaCases = currentOaOrder !== null ? (byOa.get(currentOaOrder) ?? []) : [];

  const allCases = plan?.testCases ?? [];
  const completedCount = allCases.filter((testCase) => isTerminal(state.cases[testCase.testCaseId]?.status ?? "PENDING")).length;
  const totalCount = allCases.length;
  const progressPercent = totalCount === 0 ? 0 : Math.round((completedCount / totalCount) * 100);

  const isRunning = queueOas.some((oa) => oa.aggregateStatus === "RUNNING");
  const isComplete = totalCount > 0 && completedCount === totalCount;

  const passedCount = allCases.filter((testCase) => state.cases[testCase.testCaseId]?.status === "PASSED").length;
  const failedCount = allCases.filter((testCase) => {
    const status = state.cases[testCase.testCaseId]?.status;
    return status === "FAILED" || status === "ERROR";
  }).length;

  const startDisabledReason = !plan
    ? "Generate and confirm test cases before starting execution."
    : plan.approvalStatus !== "APPROVED"
      ? "Confirm the test plan before starting execution."
      : execution
        ? "Execution already started."
        : null;

  async function handleStart(): Promise<void> {
    if (!plan || !operationQuery.data) {
      return;
    }
    setStartError(null);
    dispatch({ type: "seed", testCases: plan.testCases });
    try {
      await startExecution.mutateAsync({
        expectedRevision: operationQuery.data.revision,
        idempotencyKey: `${plan.planId}:${operationQuery.data.revision}`,
      });
    } catch (error) {
      if (error instanceof ApiClientError && error.body && "code" in error.body) {
        if (error.body.code === "HUB_NOT_ONLINE") {
          setStartError("Hub is not online yet. Wait for the Hub and device to come online, then try again.");
          return;
        }
        if (error.body.code === "OPERATION_NOT_APPROVED") {
          setStartError("The approved plan changed. Refresh and confirm again before starting.");
          return;
        }
        if (error.body.code === "REVISION_CONFLICT") {
          setStartError(`This operation changed elsewhere (current revision ${error.body.currentRevision}). Refresh and try again.`);
          await operationQuery.refetch();
          return;
        }
      }
      throw error;
    }
  }

  return (
    <div>
      <h1 className="ops-page-title">Test Operations</h1>
      <StepProgress steps={OPERATION_STEPS} currentStepId="execute" />

      {startError ? (
        <p className={styles.conflict} role="alert">
          {startError}
        </p>
      ) : null}

      <div className={styles.layout}>
        <section className={styles.queuePanel}>
          <h2 className={styles.panelTitle}>Execution Queue (by OA Sequence)</h2>
          <ExecutionQueue oas={queueOas} />
        </section>

        <section className={styles.runPanel}>
          <div className={styles.runHeader}>
            <h2 className={styles.panelTitle}>
              {isComplete ? "Execution Summary" : `Current Run: OA #${currentOaOrder ?? "-"} — Android`}
            </h2>
            <DisabledReason reason={startDisabledReason}>
              <button type="button" onClick={handleStart} disabled={startExecution.isPending}>
                {startExecution.isPending ? "Starting…" : "Start Execution"}
              </button>
            </DisabledReason>
          </div>

          {execution ? (
            <>
              <div className={styles.progressRow}>
                <span>
                  Progress: {completedCount} / {totalCount} test cases
                </span>
                <div className={styles.progressBar} role="progressbar" aria-valuenow={progressPercent} aria-valuemin={0} aria-valuemax={100}>
                  <div className={styles.progressFill} style={{ width: `${progressPercent}%` }} />
                </div>
                <span>{progressPercent}%</span>
              </div>

              {isComplete ? (
                <p className={styles.summary} role="status">
                  Execution complete: {passedCount} passed, {failedCount} failed out of {totalCount}.
                </p>
              ) : null}

              {connectionState === "closed" ? (
                <p className={styles.pollingNotice} role="status">
                  {isPolling ? "Live updates disconnected — refreshing periodically." : "Connecting to live updates…"}
                </p>
              ) : null}

              <div className={styles.tabs} role="tablist">
                {(["current", "script", "logs"] as Tab[]).map((tabId) => (
                  <button
                    key={tabId}
                    type="button"
                    role="tab"
                    aria-selected={tab === tabId}
                    className={`${styles.tab} ${tab === tabId ? styles.tabActive : ""}`}
                    onClick={() => setTab(tabId)}
                  >
                    {tabId === "current" ? "Current Run" : tabId === "script" ? "Generated Script" : "Logs"}
                  </button>
                ))}
              </div>

              {tab === "current" ? (
                <table className={styles.table}>
                  <thead>
                    <tr>
                      <th>Order</th>
                      <th>Test Case ID</th>
                      <th>Test Case Description</th>
                      <th>Status</th>
                      <th>Started At</th>
                      <th>Duration</th>
                      <th>Evidence</th>
                    </tr>
                  </thead>
                  <tbody>
                    {currentOaCases.map((testCase) => {
                      const live = state.cases[testCase.testCaseId];
                      const catalogEntry = TEST_CASE_CATALOG[testCase.templateId];
                      const label = catalogEntry?.label ?? testCaseLabel(testCase.order);
                      const status = live?.status ?? "PENDING";
                      const isFailure = status === "FAILED" || status === "ERROR";
                      return (
                        <tr key={testCase.testCaseId}>
                          <td>{testCase.order}</td>
                          <td>{label}</td>
                          <td>{catalogEntry?.description ?? testCase.templateId}</td>
                          <td>
                            <StatusBadge status={status} />
                            {live?.attempt && live.attempt > 1 ? (
                              <span className={styles.attempt}> (attempt {live.attempt})</span>
                            ) : null}
                          </td>
                          <td>{live?.startedAt ? new Date(live.startedAt).toLocaleTimeString() : "—"}</td>
                          <td>{live?.durationMs != null ? `${(live.durationMs / 1000).toFixed(2)}s` : "—"}</td>
                          <td>
                            {isFailure ? (
                              <a href={`#test-result-${testCase.testCaseId}`}>View Evidence</a>
                            ) : (
                              "—"
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              ) : null}

              {tab === "script" ? (
                <dl className={styles.scriptList}>
                  {currentOaCases.map((testCase) => (
                    <div key={testCase.testCaseId}>
                      <dt>
                        {TEST_CASE_CATALOG[testCase.templateId]?.label ?? testCaseLabel(testCase.order)} —{" "}
                        {testCase.templateId} (v{testCase.templateVersion})
                      </dt>
                      <dd>Expected header: {testCase.parameters.expectedHeader}</dd>
                      <dd>Expected body: {testCase.parameters.expectedBody}</dd>
                      <dd>Expected button text: {testCase.parameters.expectedButtonText}</dd>
                      <dd>Expected redirect: {testCase.parameters.expectedRedirectUrl}</dd>
                    </div>
                  ))}
                </dl>
              ) : null}

              {tab === "logs" ? <ExecutionLog logs={state.logs} /> : null}
            </>
          ) : (
            <div className={styles.notice}>
              <p>
                Test cases of the current OA run sequentially, top to bottom. Once an OA completes, the system
                automatically moves on to the next OA.
              </p>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

function INITIAL_STATE_FROM(testCases: GeneratedTestCase[]): ExecutionState {
  return seedExecutionState(testCases);
}
