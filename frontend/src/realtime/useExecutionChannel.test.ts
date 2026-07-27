import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import { useExecutionChannel, type WebSocketLike } from "./useExecutionChannel";
import type { HubEnvelopeV1 } from "../api/generated";

class FakeSocket implements WebSocketLike {
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  close(): void {
    this.closed = true;
  }

  emitOpen(): void {
    this.onopen?.();
  }

  emitMessage(envelope: HubEnvelopeV1): void {
    this.onmessage?.({ data: JSON.stringify(envelope) });
  }

  emitClose(): void {
    this.onclose?.();
  }
}

function envelope(overrides: Partial<HubEnvelopeV1> = {}): HubEnvelopeV1 {
  return {
    messageId: "11111111-1111-1111-1111-111111111111",
    version: 1,
    type: "JOB_PROGRESS",
    timestamp: "2026-07-27T00:00:00Z",
    payload: {
      executionId: "exec-1",
      testCaseId: "tc-1",
      status: "RUNNING",
      message: "Starting",
    },
    ...overrides,
  } as HubEnvelopeV1;
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe("useExecutionChannel", () => {
  it("applies WebSocket envelopes as they arrive", async () => {
    const sockets: FakeSocket[] = [];
    const createWebSocket = vi.fn(() => {
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    });
    const onEnvelope = vi.fn();
    const queryClient = new QueryClient();

    renderHook(() =>
      useExecutionChannel({
        url: "wss://example.test/executions/exec-1",
        queryClient,
        invalidateKey: ["execution", "op-1"],
        onEnvelope,
        createWebSocket,
      }),
    );

    act(() => sockets[0].emitOpen());
    act(() => sockets[0].emitMessage(envelope()));

    expect(onEnvelope).toHaveBeenCalledTimes(1);
  });

  it("deduplicates envelopes by messageId", () => {
    const sockets: FakeSocket[] = [];
    const createWebSocket = vi.fn(() => {
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    });
    const onEnvelope = vi.fn();
    const queryClient = new QueryClient();

    renderHook(() =>
      useExecutionChannel({
        url: "wss://example.test/executions/exec-1",
        queryClient,
        invalidateKey: ["execution", "op-1"],
        onEnvelope,
        createWebSocket,
      }),
    );

    act(() => sockets[0].emitOpen());
    const message = envelope();
    act(() => sockets[0].emitMessage(message));
    act(() => sockets[0].emitMessage(message));

    expect(onEnvelope).toHaveBeenCalledTimes(1);
  });

  it("falls back to REST polling every 3 seconds after the socket closes", async () => {
    const sockets: FakeSocket[] = [];
    const createWebSocket = vi.fn(() => {
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    });
    const queryClient = new QueryClient();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() =>
      useExecutionChannel({
        url: "wss://example.test/executions/exec-1",
        queryClient,
        invalidateKey: ["execution", "op-1"],
        createWebSocket,
      }),
    );

    act(() => sockets[0].emitOpen());
    expect(result.current.connectionState).toBe("open");

    act(() => sockets[0].emitClose());
    expect(result.current.isPolling).toBe(true);

    act(() => vi.advanceTimersByTime(3000));
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["execution", "op-1"] });
  });

  it("stops polling once the WebSocket reconnects", async () => {
    const sockets: FakeSocket[] = [];
    const createWebSocket = vi.fn(() => {
      const socket = new FakeSocket();
      sockets.push(socket);
      return socket;
    });
    const queryClient = new QueryClient();

    const { result } = renderHook(() =>
      useExecutionChannel({
        url: "wss://example.test/executions/exec-1",
        queryClient,
        invalidateKey: ["execution", "op-1"],
        createWebSocket,
      }),
    );

    act(() => sockets[0].emitOpen());
    act(() => sockets[0].emitClose());
    expect(result.current.isPolling).toBe(true);

    // The poll interval's reconnect attempt creates a new socket; opening it
    // should stop the polling fallback.
    act(() => vi.advanceTimersByTime(3000));
    expect(sockets.length).toBeGreaterThan(1);
    act(() => sockets[sockets.length - 1].emitOpen());

    expect(result.current.isPolling).toBe(false);
    expect(result.current.connectionState).toBe("open");
  });
});
