import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { HubStatusIndicator } from "./HubStatusIndicator";
import type { HubSummary } from "../api/generated";

const mocks = vi.hoisted(() => ({
  listHubs: vi.fn(),
}));

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof import("../api/client")>("../api/client");
  return {
    ...actual,
    apiClient: mocks,
  };
});

function hub(overrides: Partial<HubSummary> = {}): HubSummary {
  return {
    id: "3c75ce1d-e42d-4f16-b20f-b358df58a175",
    name: "3c75ce1d-e42d-4f16-b20f-b358df58a175",
    connectionStatus: "ONLINE",
    transport: "WEBSOCKET",
    platform: "ANDROID",
    deviceReady: true,
    runnerReady: true,
    lastHeartbeatAt: "2026-07-28T13:47:10Z",
    createdAt: "2026-07-27T15:21:09Z",
    ...overrides,
  };
}

function renderIndicator() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <HubStatusIndicator />
    </QueryClientProvider>,
  );
}

describe("HubStatusIndicator", () => {
  it("shows an online indicator and the hub's details once loaded", async () => {
    mocks.listHubs.mockResolvedValue([hub()]);
    renderIndicator();

    await waitFor(() => expect(screen.getByRole("status")).toHaveAccessibleName(/online/i));
    expect(screen.getByText("3c75ce1d-e42d-4f16-b20f-b358df58a175")).toBeInTheDocument();
    expect(screen.getByText("WEBSOCKET")).toBeInTheDocument();
    expect(screen.getByText("ANDROID")).toBeInTheDocument();
  });

  it("shows an offline indicator for a disconnected hub", async () => {
    mocks.listHubs.mockResolvedValue([hub({ connectionStatus: "OFFLINE" })]);
    renderIndicator();

    await waitFor(() => expect(screen.getByRole("status")).toHaveAccessibleName(/offline/i));
  });

  it("shows a 'no hub' state when none has ever connected", async () => {
    mocks.listHubs.mockResolvedValue([]);
    renderIndicator();

    await waitFor(() => expect(screen.getByRole("status")).toHaveAccessibleName(/no hub/i));
  });
});
