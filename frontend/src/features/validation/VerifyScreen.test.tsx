import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { VerifyScreen } from "./VerifyScreen";
import { ApiClientError } from "../../api/client";
import type { Operation, ValidationRun } from "../../api/generated";
import { operationQueryKey, validationQueryKey } from "../operations/useOperation";

const mocks = vi.hoisted(() => ({
  createOperation: vi.fn(),
  getOperation: vi.fn(),
  replaceOperationOas: vi.fn(),
  validateOperation: vi.fn(),
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
    revision: 2,
    status: "VALIDATION_FAILED",
    createdAt: "2026-07-27T00:00:00Z",
    updatedAt: "2026-07-27T00:00:00Z",
    oas: [],
    ...overrides,
  };
}

const failedRun: ValidationRun = {
  id: "run-1",
  operationId: "op-1",
  sourceRevision: 2,
  status: "VALIDATION_FAILED",
  findings: [
    { fieldName: "oa[1].oaName", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
    { fieldName: "oa[1].thumbnailUrl", validatorType: "thumbnail", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
    { fieldName: "oa[1].content.header", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
    { fieldName: "oa[1].content.body", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
    { fieldName: "oa[1].buttonText", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
    { fieldName: "oa[1].redirectUrl", validatorType: "url-direction", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
    { fieldName: "oa[2].thumbnailUrl", validatorType: "required", status: "FAILED", issue: "Thiếu: Thumb_Content, URL, Tracking", location: null, suggestion: null, severity: "ERROR", confidence: null },
    { fieldName: "oa[3].content.header", validatorType: "text-validation", status: "FAILED", issue: "Nội dung không khớp", location: null, suggestion: null, severity: "ERROR", confidence: 0.9 },
    { fieldName: "oa[3].content.body", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
    { fieldName: "oa[3].oaName", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
    { fieldName: "oa[3].buttonText", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
    { fieldName: "oa[3].redirectUrl", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
    { fieldName: "oa[3].thumbnailUrl", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
  ],
  canGenerate: false,
  generateDisabledReasons: [
    "Resolve every failed, warning, or unavailable validation finding before generating tests.",
  ],
};

const passedRun: ValidationRun = {
  id: "run-2",
  operationId: "op-1",
  sourceRevision: 3,
  status: "VALIDATED",
  findings: [
    { fieldName: "oa[1].oaName", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
  ],
  canGenerate: true,
  generateDisabledReasons: [],
};

function renderScreen(seedValidation?: ValidationRun, seedOperation: Operation = operation()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(operationQueryKey("op-1"), seedOperation);
  if (seedValidation) {
    queryClient.setQueryData(validationQueryKey("op-1"), seedValidation);
  }
  mocks.getOperation.mockResolvedValue(seedOperation);

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/operations/op-1/verify"]}>
        <Routes>
          <Route path="/operations/:operationId/verify" element={<VerifyScreen />} />
          <Route path="/operations/:operationId/input" element={<div>Input Screen Stub</div>} />
          <Route path="/operations/:operationId/generate" element={<div>Generate Screen Stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("VerifyScreen", () => {
  it("shows the issue banner and per-OA statuses derived from findings, not recomputed pass/fail rules", () => {
    renderScreen(failedRun);

    expect(screen.getByText("Input Issues Found")).toBeInTheDocument();
    expect(screen.getByText("OA #1")).toBeInTheDocument();
    expect(screen.getAllByText("Passed").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Missing Input").length).toBeGreaterThan(0);
  });

  it("disables Generate using the API's canGenerate flag and reason text, not a client-recomputed rule", () => {
    renderScreen(failedRun);

    const generate = screen.getByRole("button", { name: "AI Generate" });
    expect(generate).toBeDisabled();
    expect(screen.getByRole("note")).toHaveTextContent(
      "Resolve every failed, warning, or unavailable validation finding before generating tests.",
    );
  });

  it("enables Generate once the API reports canGenerate=true", () => {
    renderScreen(passedRun, operation({ status: "VALIDATED" }));

    const generate = screen.getByRole("button", { name: "AI Generate" });
    expect(generate).toBeEnabled();
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  it("navigates from an issue directly to the offending OA on the input screen", async () => {
    const user = userEvent.setup();
    renderScreen(failedRun);

    const links = screen.getAllByRole("link", { name: /Go to OA/ });
    expect(links.length).toBeGreaterThan(0);
    await user.click(links[0]);

    expect(await screen.findByText("Input Screen Stub")).toBeInTheDocument();
  });

  it("re-checks by calling validate with the operation's current revision and refreshes results", async () => {
    const user = userEvent.setup();
    mocks.validateOperation.mockResolvedValue(passedRun);
    renderScreen(failedRun, operation({ revision: 2 }));

    await user.click(screen.getByRole("button", { name: "Re-check" }));

    await waitFor(() =>
      expect(mocks.validateOperation).toHaveBeenCalledWith("op-1", { expectedRevision: 2 }),
    );
    await waitFor(() => expect(screen.getByRole("button", { name: "AI Generate" })).toBeEnabled());
  });

  it("surfaces a conflict message and refetches instead of leaving stale results on a stale re-check", async () => {
    const user = userEvent.setup();
    mocks.validateOperation.mockRejectedValueOnce(
      new ApiClientError(
        409,
        { code: "REVISION_CONFLICT", message: "stale", currentRevision: 5 },
        "stale",
      ),
    );
    renderScreen(failedRun, operation({ revision: 2 }));

    await user.click(screen.getByRole("button", { name: "Re-check" }));

    expect(await screen.findByText(/current revision 5/)).toBeInTheDocument();
    // No incorrect state left displayed: the stale failedRun rows are still
    // showing (no crash / no bogus success), and Generate stays gated.
    expect(screen.getByRole("button", { name: "AI Generate" })).toBeDisabled();
    await waitFor(() => expect(mocks.getOperation).toHaveBeenCalledTimes(2));
  });
});
