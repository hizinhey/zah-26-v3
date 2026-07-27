import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ExecuteScreen } from "./ExecuteScreen";
import { ApiClientError } from "../../api/client";
import type { ExecutionResponse, GeneratedTestCase, Operation, TestPlan } from "../../api/generated";
import { operationQueryKey } from "../operations/useOperation";
import { planQueryKey } from "../generation/usePlan";
import { executionQueryKey } from "./useExecution";

const channelSpy = vi.hoisted(() => vi.fn());
vi.mock("../../realtime/useExecutionChannel", () => ({
  useExecutionChannel: (options: unknown) => {
    channelSpy(options);
    return { connectionState: "open", isPolling: false };
  },
}));

const mocks = vi.hoisted(() => ({
  getOperation: vi.fn(),
  startExecution: vi.fn(),
  getPlan: vi.fn(),
  getExecution: vi.fn(),
}));

vi.mock("../../api/client", async () => {
  const actual = await vi.importActual<typeof import("../../api/client")>("../../api/client");
  return {
    ...actual,
    apiClient: mocks,
  };
});

function operation(overrides: Partial<Operation> = {}): Operation {
  return {
    id: "op-1",
    jiraId: "ZVAS-1424",
    revision: 4,
    status: "APPROVED",
    createdAt: "2026-07-27T00:00:00Z",
    updatedAt: "2026-07-27T00:00:00Z",
    oas: [],
    ...overrides,
  };
}

function testCase(oaOrder: number, order: number): GeneratedTestCase {
  const templates = [
    "android-oa-delivery-v1",
    "android-thumbnail-v1",
    "android-content-v1",
    "android-button-text-v1",
    "android-redirect-v1",
  ];
  return {
    testCaseId: `tc-${oaOrder}-${order}`,
    planId: "plan-1",
    oaOrder,
    order,
    templateId: templates[order - 1],
    templateVersion: 1,
    templateSha256: "sha",
    parameters: {
      oaName: `OA ${oaOrder}`,
      thumbnailUrl: "https://example.com/thumb.png",
      expectedHeader: "Header",
      expectedBody: "Body",
      expectedButtonText: "Try now",
      expectedRedirectUrl: "https://example.com/redirect",
      expectedRedirectDomain: "example.com",
    },
    status: "READY",
    reason: null,
  };
}

function fiveCasesFor(oaOrder: number): GeneratedTestCase[] {
  return [1, 2, 3, 4, 5].map((order) => testCase(oaOrder, order));
}

function plan(overrides: Partial<TestPlan> = {}): TestPlan {
  return {
    planId: "plan-1",
    operationId: "op-1",
    sourceRevision: 4,
    templateCatalogVersion: "v1",
    status: "READY",
    approvalStatus: "APPROVED",
    testCases: [...fiveCasesFor(1), ...fiveCasesFor(2), ...fiveCasesFor(3)],
    ...overrides,
  };
}

function execution(overrides: Partial<ExecutionResponse> = {}): ExecutionResponse {
  return {
    id: "exec-1",
    operationId: "op-1",
    planId: "plan-1",
    sourceRevision: 4,
    status: "QUEUED",
    ...overrides,
  };
}

function renderScreen(options: {
  seedPlan?: TestPlan;
  seedExecution?: ExecutionResponse;
  seedOperation?: Operation;
} = {}) {
  const seedOperation = options.seedOperation ?? operation();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(operationQueryKey("op-1"), seedOperation);
  if (options.seedPlan) {
    queryClient.setQueryData(planQueryKey("op-1"), options.seedPlan);
  }
  if (options.seedExecution) {
    queryClient.setQueryData(executionQueryKey("op-1"), options.seedExecution);
    mocks.getExecution.mockResolvedValue({ ...options.seedExecution, results: [] });
  }
  mocks.getOperation.mockResolvedValue(seedOperation);
  if (options.seedPlan) {
    mocks.getPlan.mockResolvedValue(options.seedPlan);
  }

  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={["/operations/op-1/execute"]}>
          <Routes>
            <Route path="/operations/:operationId/execute" element={<ExecuteScreen />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("ExecuteScreen", () => {
  it("renders the execution queue in OA order", () => {
    renderScreen({ seedPlan: plan() });

    const queue = screen.getByRole("list", { name: "Execution Queue (by OA Sequence)" });
    const items = queue.querySelectorAll("li");
    expect(items).toHaveLength(3);
    expect(items[0]).toHaveTextContent("OA #1");
    expect(items[1]).toHaveTextContent("OA #2");
    expect(items[2]).toHaveTextContent("OA #3");
  });

  it("disables Start Execution until the plan is confirmed/approved", () => {
    renderScreen({ seedPlan: plan({ approvalStatus: "PENDING" }) });

    const start = screen.getByRole("button", { name: /Start Execution/ });
    expect(start).toBeDisabled();
    expect(screen.getByRole("note")).toHaveTextContent(/Confirm the test plan/);
  });

  it("starts execution and connects the realtime channel", async () => {
    mocks.startExecution.mockResolvedValue(execution());
    renderScreen({ seedPlan: plan() });

    await userEvent.click(screen.getByRole("button", { name: "Start Execution" }));

    await waitFor(() =>
      expect(mocks.startExecution).toHaveBeenCalledWith("op-1", {
        expectedRevision: 4,
        idempotencyKey: "plan-1:4",
      }),
    );
    await waitFor(() => expect(channelSpy).toHaveBeenCalled());
    const lastCall = channelSpy.mock.calls[channelSpy.mock.calls.length - 1][0];
    expect(lastCall.url).toContain("exec-1");
  });

  it("shows a Hub-not-online message and keeps Start enabled to retry, without navigating away", async () => {
    mocks.startExecution.mockRejectedValueOnce(
      new ApiClientError(409, { code: "HUB_NOT_ONLINE", message: "hub offline", currentRevision: null }, "hub offline"),
    );
    renderScreen({ seedPlan: plan() });

    await userEvent.click(screen.getByRole("button", { name: "Start Execution" }));

    expect(await screen.findByText(/Hub is not online/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start Execution" })).toBeEnabled();
  });

  it("shows exactly five fixed test cases for the current OA, seeded as pending before any events", () => {
    renderScreen({ seedPlan: plan(), seedExecution: execution() });

    const rows = screen.getAllByText(/TC-0\d/);
    expect(rows).toHaveLength(5);
    expect(screen.getAllByText("Pending").length).toBeGreaterThanOrEqual(5);
  });

  it("keeps rendering all five test cases after an assertion failure, without stopping the run", async () => {
    const { queryClient } = renderScreen({ seedPlan: plan(), seedExecution: execution() });
    const dispatchEnvelope = channelSpy.mock.calls[0][0].onEnvelope as (envelope: unknown) => void;

    dispatchEnvelope({
      messageId: "m1",
      version: 1,
      type: "TEST_RESULT",
      timestamp: "2026-07-27T00:00:01Z",
      payload: { executionId: "exec-1", testCaseId: "tc-1-1", attempt: 1, status: "FAILED", durationMs: 500, errorCategory: "ASSERTION_FAILURE" },
    });

    await waitFor(() => expect(screen.getAllByText(/TC-0\d/)).toHaveLength(5));
    expect(screen.getByText("Failed")).toBeInTheDocument();
    void queryClient;
  });

  it("shows a final aggregate summary once every test case reaches a terminal state", async () => {
    renderScreen({ seedPlan: plan({ testCases: fiveCasesFor(1) }), seedExecution: execution() });
    const dispatchEnvelope = channelSpy.mock.calls[0][0].onEnvelope as (envelope: unknown) => void;

    for (let order = 1; order <= 5; order += 1) {
      dispatchEnvelope({
        messageId: `m${order}`,
        version: 1,
        type: "TEST_RESULT",
        timestamp: "2026-07-27T00:00:01Z",
        payload: {
          executionId: "exec-1",
          testCaseId: `tc-1-${order}`,
          attempt: 1,
          status: order === 3 ? "FAILED" : "PASSED",
          durationMs: 500,
          errorCategory: order === 3 ? "ASSERTION_FAILURE" : null,
        },
      });
    }

    expect(await screen.findByText(/Execution complete: 4 passed, 1 failed out of 5\./)).toBeInTheDocument();
  });

  it("shows a disabled evidence control with an explanatory tooltip for a failed test case (no browser-facing evidence viewer exists yet)", async () => {
    renderScreen({ seedPlan: plan({ testCases: fiveCasesFor(1) }), seedExecution: execution() });
    const dispatchEnvelope = channelSpy.mock.calls[0][0].onEnvelope as (envelope: unknown) => void;

    dispatchEnvelope({
      messageId: "m1",
      version: 1,
      type: "TEST_RESULT",
      timestamp: "2026-07-27T00:00:01Z",
      payload: { executionId: "exec-1", testCaseId: "tc-1-1", attempt: 1, status: "FAILED", durationMs: 500, errorCategory: "ASSERTION_FAILURE" },
    });

    const evidenceButton = await screen.findByRole("button", { name: "View Evidence" });
    expect(evidenceButton).toBeDisabled();
    expect(evidenceButton).toHaveAttribute("title", expect.stringContaining("isn't available yet"));
  });

  it("switches to the Logs tab and shows accumulated log entries", async () => {
    renderScreen({ seedPlan: plan({ testCases: fiveCasesFor(1) }), seedExecution: execution() });
    const dispatchEnvelope = channelSpy.mock.calls[0][0].onEnvelope as (envelope: unknown) => void;

    dispatchEnvelope({
      messageId: "m1",
      version: 1,
      type: "JOB_PROGRESS",
      timestamp: "2026-07-27T00:00:01Z",
      payload: { executionId: "exec-1", testCaseId: "tc-1-1", status: "RUNNING", message: "Starting test case TC-01" },
    });

    await userEvent.click(screen.getByRole("tab", { name: "Logs" }));

    expect(await screen.findByText("Starting test case TC-01")).toBeInTheDocument();
  });
});
