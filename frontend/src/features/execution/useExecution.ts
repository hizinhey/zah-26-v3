import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { apiClient } from "../../api/client";
import type { ExecutionResponse } from "../../api/generated";

export function executionQueryKey(operationId: string): readonly ["execution", string] {
  return ["execution", operationId] as const;
}

/**
 * There is no GET endpoint for execution status; the cached value here is
 * seeded by useStartExecutionMutation and kept current by useExecutionChannel
 * (WebSocket primary, REST-refresh-on-disconnect fallback per the brief).
 */
export function useExecutionQuery(operationId: string): UseQueryResult<ExecutionResponse> {
  return useQuery({
    queryKey: executionQueryKey(operationId),
    queryFn: () => Promise.reject(new Error("no execution has been started yet")),
    enabled: false,
    retry: false,
  });
}

export function useStartExecutionMutation(operationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: { expectedRevision: number; idempotencyKey: string }) =>
      apiClient.startExecution(operationId, variables),
    onSuccess: (execution) => {
      queryClient.setQueryData(executionQueryKey(operationId), execution);
    },
  });
}
