import type {
  ErrorCategory,
  ExecutionTestResult,
  GeneratedTestCase,
  HubEnvelopeV1,
  TestCaseStatus,
} from "../../api/generated";

export interface LiveTestCaseState {
  status: TestCaseStatus;
  message: string | null;
  attempt: number | null;
  startedAt: string | null;
  durationMs: number | null;
  errorCategory: ErrorCategory | null;
  errorMessage: string | null;
}

export interface LogEntry {
  key: string;
  time: string;
  message: string;
}

export interface ExecutionState {
  cases: Record<string, LiveTestCaseState>;
  logs: LogEntry[];
}

export const INITIAL_EXECUTION_STATE: ExecutionState = { cases: {}, logs: [] };

function initialCaseState(): LiveTestCaseState {
  return {
    status: "PENDING",
    message: null,
    attempt: null,
    startedAt: null,
    durationMs: null,
    errorCategory: null,
    errorMessage: null,
  };
}

/**
 * Seeds every test case as PENDING so the table always shows the fixed five
 * rows per OA before any progress events arrive.
 */
export function seedExecutionState(testCases: GeneratedTestCase[]): ExecutionState {
  const cases: Record<string, LiveTestCaseState> = {};
  for (const testCase of testCases) {
    cases[testCase.testCaseId] = initialCaseState();
  }
  return { cases, logs: [] };
}

/**
 * Applies one deduplicated Hub envelope. Execution continues through every
 * test case even after an assertion failure - this reducer only records
 * whatever the server reported; it never stops rendering progress or treats
 * a failure as ending the run. Retries are reflected as reported (assertion
 * failures do not retry; infrastructure errors retry once) - the attempt
 * number is exactly whatever the server sent, no client-side retry logic.
 */
export function applyEnvelope(state: ExecutionState, envelope: HubEnvelopeV1): ExecutionState {
  const time = new Date(envelope.timestamp).toLocaleTimeString();

  if (envelope.type === "JOB_PROGRESS") {
    const { testCaseId, status, message } = envelope.payload;
    const previous = state.cases[testCaseId] ?? initialCaseState();
    return {
      cases: {
        ...state.cases,
        [testCaseId]: {
          ...previous,
          status,
          message,
          startedAt: previous.startedAt ?? (status === "RUNNING" ? envelope.timestamp : previous.startedAt),
        },
      },
      logs: [...state.logs, { key: envelope.messageId, time, message }],
    };
  }

  if (envelope.type === "TEST_RESULT") {
    const { testCaseId, status, attempt, durationMs, errorCategory, errorMessage } = envelope.payload;
    const previous = state.cases[testCaseId] ?? initialCaseState();
    return {
      cases: {
        ...state.cases,
        [testCaseId]: {
          ...previous,
          status,
          attempt,
          durationMs,
          errorCategory,
          errorMessage,
        },
      },
      logs: [
        ...state.logs,
        { key: envelope.messageId, time, message: `Test case result: ${status} (attempt ${attempt})` },
      ],
    };
  }

  return state;
}

/**
 * Merges REST-fetched `results` (GET /api/v1/executions/{id}) into the reducer's case map,
 * the same shape `applyEnvelope`'s TEST_RESULT branch produces. This is what makes the 3s
 * REST-poll fallback (useExecutionChannel) actually update the table when the browser
 * WebSocket can't connect (C2 fix) - without this, `results` was fetched but never applied
 * to any state the table reads from, so every row stayed PENDING forever.
 *
 * A result is only applied if it represents progress at least as advanced as what's already
 * known for that test case (higher attempt, or same attempt with a terminal status), so an
 * out-of-order REST fetch can never regress a case that a later envelope already advanced.
 */
export function hydrateFromResults(state: ExecutionState, results: ExecutionTestResult[]): ExecutionState {
  let cases = state.cases;
  let changed = false;
  for (const result of results) {
    const previous = cases[result.testCaseId] ?? initialCaseState();
    if (previous.attempt != null && previous.attempt > result.attempt) {
      continue;
    }
    if (
      previous.attempt === result.attempt &&
      previous.status === result.status &&
      previous.durationMs === result.durationMs &&
      previous.errorCategory === result.errorCategory &&
      previous.errorMessage === result.errorMessage
    ) {
      continue;
    }
    if (!changed) {
      cases = { ...cases };
      changed = true;
    }
    cases[result.testCaseId] = {
      ...previous,
      status: result.status,
      attempt: result.attempt,
      durationMs: result.durationMs,
      errorCategory: result.errorCategory,
      errorMessage: result.errorMessage,
    };
  }
  return changed ? { ...state, cases } : state;
}

function isTerminalStatus(status: TestCaseStatus): boolean {
  return status === "PASSED" || status === "FAILED" || status === "ERROR";
}

/**
 * Marks the REST poll's `runningTestCaseId` (GET /api/v1/executions/{id}) as RUNNING in the
 * reducer's case map - the only way a PENDING row ever becomes RUNNING when the browser
 * WebSocket can't connect and JOB_PROGRESS envelopes never reach the tab directly. Never
 * regresses a case that's already reached a terminal outcome (an out-of-order/stale poll
 * response must not un-finish a case that a later envelope or poll already completed), and is a
 * no-op once `runningTestCaseId` is null (nothing currently in flight).
 */
export function hydrateRunningTestCase(state: ExecutionState, runningTestCaseId: string | null): ExecutionState {
  if (!runningTestCaseId) {
    return state;
  }
  const previous = state.cases[runningTestCaseId] ?? initialCaseState();
  if (isTerminalStatus(previous.status) || previous.status === "RUNNING") {
    return state;
  }
  return {
    ...state,
    cases: {
      ...state.cases,
      [runningTestCaseId]: { ...previous, status: "RUNNING" },
    },
  };
}
