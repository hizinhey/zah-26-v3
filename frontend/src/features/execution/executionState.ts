import type {
  ErrorCategory,
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
  return { status: "PENDING", message: null, attempt: null, startedAt: null, durationMs: null, errorCategory: null };
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
    const { testCaseId, status, attempt, durationMs, errorCategory } = envelope.payload;
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
