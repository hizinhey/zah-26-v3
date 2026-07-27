import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { apiClient } from "../../api/client";
import type { ExecutionResponse, ExecutionStatus } from "../../api/generated";

export function executionQueryKey(operationId: string): readonly ["execution", string] {
  return ["execution", operationId] as const;
}

/**
 * Seeded by useStartExecutionMutation with the just-started execution's id/status.
 * Once seeded, this refetches GET /api/v1/executions/{executionId} - the real REST
 * status endpoint - so both the WebSocket close handler's invalidateQueries call and
 * the 3s poll fallback in useExecutionChannel genuinely pull fresh state, instead of
 * the previous always-rejecting/disabled queryFn that made the fallback a no-op.
 *
 * Returns the *full* ExecutionStatus, including `results` (C2 fix): the previous
 * version discarded `results` when mapping into the narrower ExecutionResponse shape,
 * which meant the REST-poll fallback path never had any data to hydrate the table
 * with - every test case stayed PENDING forever whenever the (nonexistent) browser
 * WebSocket endpoint failed to connect. ExecuteScreen now dispatches a "hydrate"
 * action off this query's `results` on every successful fetch.
 */
export function useExecutionQuery(operationId: string): UseQueryResult<ExecutionStatus> {
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: executionQueryKey(operationId),
    queryFn: async () => {
      const seeded = queryClient.getQueryData<ExecutionResponse | ExecutionStatus>(executionQueryKey(operationId));
      if (!seeded) {
        throw new Error("no execution has been started yet");
      }
      return apiClient.getExecution(seeded.id);
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
