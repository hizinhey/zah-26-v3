import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { InputScreen } from "./InputScreen";
import { ApiClientError } from "../../api/client";
import type { Operation } from "../../api/generated";

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

function renderScreen(initialPath: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/operations/:operationId/input" element={<InputScreen />} />
          <Route path="/operations/:operationId/verify" element={<div>Verify Screen Stub</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function existingOperation(overrides: Partial<Operation> = {}): Operation {
  return {
    id: "op-1",
    jiraId: "ZVAS-1",
    revision: 1,
    status: "DRAFT",
    createdAt: "2026-07-27T00:00:00Z",
    updatedAt: "2026-07-27T00:00:00Z",
    oas: [],
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("InputScreen", () => {
  it("starts with a single OA tab and can add another", async () => {
    const user = userEvent.setup();
    renderScreen("/operations/new/input");

    expect(screen.getByRole("tab", { name: "OA #1" })).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "OA #2" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "+ Add OA" }));

    expect(screen.getByRole("tab", { name: "OA #2" })).toBeInTheDocument();
  });

  it("removes an OA but never removes the last remaining one", async () => {
    const user = userEvent.setup();
    renderScreen("/operations/new/input");

    await user.click(screen.getByRole("button", { name: "+ Add OA" }));
    expect(screen.getByRole("tab", { name: "OA #2" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Remove OA #2" }));
    expect(screen.queryByRole("tab", { name: "OA #2" })).not.toBeInTheDocument();

    expect(screen.getByRole("button", { name: "Remove OA #1" })).toBeDisabled();
  });

  it("reorders OAs, moving field values along with the tab", async () => {
    const user = userEvent.setup();
    renderScreen("/operations/new/input");

    await user.type(screen.getByLabelText(/Button Text/), "First");
    await user.click(screen.getByRole("button", { name: "+ Add OA" }));
    await user.type(screen.getByLabelText(/Button Text/), "Second");

    // Active tab is now OA #2 ("Second"); move it earlier so it becomes OA #1.
    await user.click(screen.getByRole("button", { name: "Move OA #2 earlier" }));

    expect(screen.getByRole("tab", { name: "OA #1", selected: true })).toBeInTheDocument();
    expect(screen.getByLabelText(/Button Text/)).toHaveValue("Second");

    await user.click(screen.getByRole("tab", { name: "OA #2" }));
    expect(screen.getByLabelText(/Button Text/)).toHaveValue("First");
  });

  it("only ever displays Android as the platform, matching the backend's ANDROID-only contract", () => {
    renderScreen("/operations/new/input");

    expect(screen.getByTestId("oa-platform")).toHaveTextContent("Android");
    expect(screen.queryByText("iOS")).not.toBeInTheDocument();
    expect(screen.queryByText("Web")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "iOS" })).not.toBeInTheDocument();
  });

  it("disables AI Validation until Jira ID and every required OA field is filled, and explains why", async () => {
    const user = userEvent.setup();
    renderScreen("/operations/new/input");

    const submit = screen.getByRole("button", { name: /AI Validation/ });
    expect(submit).toBeDisabled();
    expect(screen.getByRole("note")).toHaveTextContent(/nhập đầy đủ thông tin/);

    await user.type(screen.getByLabelText(/Jira ID/), "ZVAS-1424");
    await user.type(screen.getByLabelText(/Thumb URL/), "https://example.com/thumb.png");
    await user.type(screen.getByLabelText(/Content/), "Header line\nBody line");
    await user.type(screen.getByLabelText(/Button Text/), "Try now");
    await user.type(screen.getByLabelText(/URL Direction/), "https://example.com/redirect");

    expect(submit).toBeEnabled();
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  it("previews the content field's header and body without trimming or collapsing whitespace", async () => {
    const user = userEvent.setup();
    renderScreen("/operations/new/input");

    await user.type(
      screen.getByLabelText(/Content/),
      "Không giới hạn 60.250.000{enter}  Để trả lẻ bạn nói mình.{enter}Second body line.",
    );

    expect(screen.getByTestId("content-header-preview")).toHaveTextContent(
      "Không giới hạn 60.250.000",
    );
    const bodyPreview = screen.getByTestId("content-body-preview");
    expect(bodyPreview.textContent).toBe("  Để trả lẻ bạn nói mình.\nSecond body line.");
  });

  it("surfaces a conflict message and refetches instead of navigating on a stale revision", async () => {
    const user = userEvent.setup();
    mocks.getOperation.mockResolvedValue(existingOperation({ revision: 3 }));
    mocks.replaceOperationOas.mockRejectedValueOnce(
      new ApiClientError(
        409,
        { code: "REVISION_CONFLICT", message: "stale", currentRevision: 5 },
        "stale",
      ),
    );

    renderScreen("/operations/op-1/input");

    await waitFor(() => expect(mocks.getOperation).toHaveBeenCalledTimes(1));

    await user.type(screen.getByLabelText(/Thumb URL/), "https://example.com/thumb.png");
    await user.type(screen.getByLabelText(/Content/), "Header\nBody");
    await user.type(screen.getByLabelText(/Button Text/), "Try now");
    await user.type(screen.getByLabelText(/URL Direction/), "https://example.com/redirect");

    await user.click(screen.getByRole("button", { name: /AI Validation/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/current revision 5/);
    expect(screen.queryByText("Verify Screen Stub")).not.toBeInTheDocument();
    await waitFor(() => expect(mocks.getOperation).toHaveBeenCalledTimes(2));
  });

  it("creates the operation, replaces OAs, validates, and navigates to verify for a brand-new operation", async () => {
    const user = userEvent.setup();
    mocks.createOperation.mockResolvedValue(existingOperation({ id: "op-new", revision: 1 }));
    mocks.replaceOperationOas.mockResolvedValue(
      existingOperation({ id: "op-new", revision: 2 }),
    );
    mocks.validateOperation.mockResolvedValue({
      id: "run-1",
      operationId: "op-new",
      sourceRevision: 2,
      status: "VALIDATED",
      findings: [],
      canGenerate: true,
      generateDisabledReasons: [],
    });

    renderScreen("/operations/new/input");

    await user.type(screen.getByLabelText(/Jira ID/), "ZVAS-1424");
    await user.type(screen.getByLabelText(/Thumb URL/), "https://example.com/thumb.png");
    await user.type(screen.getByLabelText(/Content/), "Header\nBody");
    await user.type(screen.getByLabelText(/Button Text/), "Try now");
    await user.type(screen.getByLabelText(/URL Direction/), "https://example.com/redirect");

    await user.click(screen.getByRole("button", { name: /AI Validation/ }));

    await screen.findByText("Verify Screen Stub");
    expect(mocks.createOperation).toHaveBeenCalledWith({ jiraId: "ZVAS-1424" });
    expect(mocks.replaceOperationOas).toHaveBeenCalledWith(
      "op-new",
      expect.objectContaining({ expectedRevision: 1 }),
    );
    expect(mocks.validateOperation).toHaveBeenCalledWith("op-new", { expectedRevision: 2 });
  });
});
