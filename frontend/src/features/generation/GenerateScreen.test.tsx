import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { GenerateScreen } from "./GenerateScreen";
import { ApiClientError } from "../../api/client";
import type { GeneratedTestCase, Operation, TestPlan } from "../../api/generated";
import { operationQueryKey } from "../operations/useOperation";
import { planQueryKey } from "./usePlan";

const mocks = vi.hoisted(() => ({
  getOperation: vi.fn(),
  generatePlan: vi.fn(),
  approvePlan: vi.fn(),
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
    revision: 3,
    status: "READY_FOR_APPROVAL",
    createdAt: "2026-07-27T00:00:00Z",
    updatedAt: "2026-07-27T00:00:00Z",
    oas: [],
    ...overrides,
  };
}

function testCase(oaOrder: number, order: number, overrides: Partial<GeneratedTestCase> = {}): GeneratedTestCase {
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
    ...overrides,
  };
}

function fiveCasesFor(oaOrder: number, overrides: Partial<GeneratedTestCase> = {}): GeneratedTestCase[] {
  return [1, 2, 3, 4, 5].map((order) => testCase(oaOrder, order, order === 5 ? overrides : {}));
}

function plan(overrides: Partial<TestPlan> = {}): TestPlan {
  return {
    planId: "plan-1",
    operationId: "op-1",
    sourceRevision: 3,
    templateCatalogVersion: "v1",
    status: "READY",
    approvalStatus: "PENDING",
    testCases: [...fiveCasesFor(1), ...fiveCasesFor(2)],
    ...overrides,
  };
}

function renderScreen(seedPlan?: TestPlan, seedOperation: Operation = operation()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(operationQueryKey("op-1"), seedOperation);
  if (seedPlan) {
    queryClient.setQueryData(planQueryKey("op-1"), seedPlan);
  }
  mocks.getOperation.mockResolvedValue(seedOperation);

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/operations/op-1/generate"]}>
        <Routes>
          <Route path="/operations/:operationId/generate" element={<GenerateScreen />} />
          <Route path="/operations/:operationId/execute" element={<div>Execute Screen Stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("GenerateScreen", () => {
  it("renders exactly five test cases per OA in fixed order", async () => {
    renderScreen(plan());

    await userEvent.click(screen.getByRole("button", { name: /Toggle OA #2/ }));

    const oa1Rows = screen.getAllByText(/TC-0\d/);
    // 5 per OA x 2 OAs = 10 labels rendered once both are expanded.
    expect(oa1Rows).toHaveLength(10);
    expect(screen.getAllByText("Open OA form verification")).toHaveLength(2);
    expect(screen.getAllByText("Validate URL tracking")).toHaveLength(2);
  });

  it("expands a script preview without offering add/delete/rerun controls", async () => {
    renderScreen(plan());

    await userEvent.click(screen.getByRole("button", { name: "Open OA form verification" }));

    expect(screen.getByText(/Expected header/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Add Test Case/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Delete/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Rerun/i })).not.toBeInTheDocument();
  });

  it("generates a plan automatically when arriving with none cached", async () => {
    mocks.generatePlan.mockResolvedValue(plan());
    renderScreen(undefined, operation({ status: "VALIDATED" }));

    await waitFor(() => expect(mocks.generatePlan).toHaveBeenCalledWith("op-1", { expectedRevision: 3 }));
    expect(await screen.findByText("OA #1")).toBeInTheDocument();
  });

  it("disables Confirm until every test case is Ready", () => {
    renderScreen(plan({ testCases: [...fiveCasesFor(1, { status: "NOT_READY", reason: "template missing" }), ...fiveCasesFor(2)] }));

    const confirm = screen.getByRole("button", { name: "Confirm" });
    expect(confirm).toBeDisabled();
    expect(screen.getByRole("note")).toHaveTextContent(/Ready/);
  });

  it("approves the plan and navigates to the execute step on Confirm", async () => {
    mocks.approvePlan.mockResolvedValue(undefined);
    renderScreen(plan());

    await userEvent.click(screen.getByRole("button", { name: "Confirm" }));

    await waitFor(() =>
      expect(mocks.approvePlan).toHaveBeenCalledWith("plan-1", { expectedRevision: 3 }),
    );
    expect(await screen.findByText("Execute Screen Stub")).toBeInTheDocument();
  });

  it("surfaces a conflict message on a stale Confirm instead of navigating", async () => {
    mocks.approvePlan.mockRejectedValueOnce(
      new ApiClientError(409, { code: "REVISION_CONFLICT", message: "stale", currentRevision: 5 }, "stale"),
    );
    renderScreen(plan());

    await userEvent.click(screen.getByRole("button", { name: "Confirm" }));

    expect(await screen.findByText(/current revision 5/)).toBeInTheDocument();
    expect(screen.queryByText("Execute Screen Stub")).not.toBeInTheDocument();
  });
});
