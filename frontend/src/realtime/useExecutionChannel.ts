import { useEffect, useRef, useState } from "react";
import type { QueryClient, QueryKey } from "@tanstack/react-query";
import type { HubEnvelopeV1 } from "../api/generated";

export type ConnectionState = "connecting" | "open" | "closed";

/** The minimal surface of the WebSocket API this hook depends on, so tests can inject a fake. */
export interface WebSocketLike {
  onopen: (() => void) | null;
  onmessage: ((event: { data: string }) => void) | null;
  onclose: (() => void) | null;
  onerror: (() => void) | null;
  close(): void;
}

export interface UseExecutionChannelOptions {
  /**
   * WebSocket URL to connect to.
   *
   * - `undefined`: stay fully disconnected, no polling either (e.g. before an execution exists).
   * - `null`: skip the WebSocket entirely and go straight to REST-poll-only mode at
   *   `pollIntervalMs`. Used when no browser-facing WS endpoint exists yet (see
   *   ExecuteScreen.tsx's `browserExecutionChannelUrl`, C2) - avoids reconnect-storming against
   *   an endpoint that will never accept the connection.
   * - a string: connect over WebSocket, falling back to REST-poll on close/error as before.
   */
  url: string | null | undefined;
  queryClient: QueryClient;
  /** Query key invalidated on the REST polling fallback while the socket is closed. */
  invalidateKey: QueryKey;
  /** Called once per envelope, after message-id deduplication. */
  onEnvelope?: (envelope: HubEnvelopeV1) => void;
  /** Defaults to `window.WebSocket`; override in tests. */
  createWebSocket?: (url: string) => WebSocketLike;
  /** Defaults to 3000ms per the realtime-fallback contract. */
  pollIntervalMs?: number;
}

export interface UseExecutionChannelResult {
  connectionState: ConnectionState;
  isPolling: boolean;
}

/**
 * WebSocket is primary; on closure this falls back to invalidating
 * `invalidateKey` (triggering a REST refetch) every `pollIntervalMs` and also
 * retries the WebSocket connection on that same cadence. Polling stops the
 * moment the WebSocket reconnects. Envelopes are deduplicated by messageId so
 * a message delivered by both transports during the handoff is only applied
 * once.
 */
export function useExecutionChannel(options: UseExecutionChannelOptions): UseExecutionChannelResult {
  const {
    url,
    queryClient,
    invalidateKey,
    onEnvelope,
    createWebSocket,
    pollIntervalMs = 3000,
  } = options;

  const [connectionState, setConnectionState] = useState<ConnectionState>("connecting");
  const [isPolling, setIsPolling] = useState(false);

  const onEnvelopeRef = useRef(onEnvelope);
  onEnvelopeRef.current = onEnvelope;

  useEffect(() => {
    if (url === undefined) {
      setConnectionState("closed");
      setIsPolling(false);
      return;
    }

    const seenMessageIds = new Set<string>();
    let disposed = false;
    let socket: WebSocketLike | null = null;
    let pollTimer: ReturnType<typeof setInterval> | null = null;

    const open = createWebSocket ?? ((target: string) => new WebSocket(target) as unknown as WebSocketLike);

    function stopPolling(): void {
      if (pollTimer !== null) {
        clearInterval(pollTimer);
        pollTimer = null;
      }
      setIsPolling(false);
    }

    function startPolling(): void {
      if (pollTimer !== null) {
        return;
      }
      setIsPolling(true);
      pollTimer = setInterval(() => {
        void queryClient.invalidateQueries({ queryKey: invalidateKey });
        connect();
      }, pollIntervalMs);
    }

    function handleMessage(event: { data: string }): void {
      let envelope: HubEnvelopeV1;
      try {
        envelope = JSON.parse(event.data) as HubEnvelopeV1;
      } catch {
        return;
      }
      if (seenMessageIds.has(envelope.messageId)) {
        return;
      }
      seenMessageIds.add(envelope.messageId);
      onEnvelopeRef.current?.(envelope);
    }

    function connect(): void {
      if (disposed || !url) {
        return;
      }
      setConnectionState("connecting");
      const next = open(url);
      socket = next;
      next.onopen = () => {
        if (disposed) {
          return;
        }
        setConnectionState("open");
        stopPolling();
      };
      next.onmessage = handleMessage;
      next.onclose = () => {
        if (disposed) {
          return;
        }
        setConnectionState("closed");
        startPolling();
      };
      next.onerror = () => {
        next?.close();
      };
    }

    if (url === null) {
      // Poll-only mode: skip the WebSocket entirely and start polling immediately, rather than
      // waiting for a connect/close cycle against an endpoint that doesn't exist.
      setConnectionState("closed");
      startPolling();
    } else {
      connect();
    }

    return () => {
      disposed = true;
      stopPolling();
      socket?.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, queryClient, JSON.stringify(invalidateKey), createWebSocket, pollIntervalMs]);

  return { connectionState, isPolling };
}
