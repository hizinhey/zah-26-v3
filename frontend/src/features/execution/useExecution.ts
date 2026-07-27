import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { apiClient } from "../../api/client";
import type { ExecutionResponse } from "../../api/generated";

export function executionQueryKey(operationId: string): readonly ["execution", string] {
  return ["execution", operationId] as const;
}

/**
 * Seeded by useStartExecutionMutation with the just-started execution's id/status.
 * Once seeded, this refetches GET /api/v1/executions/{executionId} - the real REST
 * status endpoint - so both the WebSocket close handler's invalidateQueries call and
 * the 3s poll fallback in useExecutionChannel genuinely pull fresh state, instead of
 * the previous always-rejecting/disabled queryFn that made the fallback a no-op.
 */
export function useExecutionQuery(operationId: string): UseQueryResult<ExecutionResponse> {
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: executionQueryKey(operationId),
    queryFn: async () => {
      const seeded = queryClient.getQueryData<ExecutionResponse>(executionQueryKey(operationId));
      if (!seeded) {
        throw new Error("no execution has been started yet");
      }
      const status = await apiClient.getExecution(seeded.id);
      return {
        id: status.id,
        operationId: status.operationId,
        planId: status.planId,
        sourceRevision: status.sourceRevision,
        status: status.status,
      } satisfies ExecutionResponse;
    },
    // enabled (not the previous permanently-disabled query): a query with enabled:false is
    // skipped by invalidateQueries too, which is exactly what made the 3s REST-fallback poll
    // in useExecutionChannel a no-op before this fix. Before an execution is seeded, queryFn
    // above rejects immediately and harmlessly (nothing renders off .error here).
    enabled: true,
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
