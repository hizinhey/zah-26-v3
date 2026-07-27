import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { apiClient } from "../../api/client";
import type { TestPlan } from "../../api/generated";
import { operationQueryKey } from "../operations/useOperation";

export function planQueryKey(operationId: string): readonly ["plan", string] {
  return ["plan", operationId] as const;
}

/**
 * There is no GET endpoint for a test plan; the latest plan is whatever the
 * generate/approve mutations last wrote into the cache for this operation
 * (mirrors useValidationQuery's cache-only pattern in ../operations/useOperation).
 */
export function usePlanQuery(operationId: string): UseQueryResult<TestPlan> {
  return useQuery({
    queryKey: planQueryKey(operationId),
    queryFn: () => Promise.reject(new Error("no test plan has been generated yet")),
    enabled: false,
    retry: false,
  });
}

export function useGeneratePlanMutation(operationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (expectedRevision: number) =>
      apiClient.generatePlan(operationId, { expectedRevision }),
    onSuccess: (plan) => {
      queryClient.setQueryData(planQueryKey(operationId), plan);
    },
  });
}

export function useApprovePlanMutation(operationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: { planId: string; expectedRevision: number }) =>
      apiClient.approvePlan(variables.planId, { expectedRevision: variables.expectedRevision }),
    onSuccess: (_void, variables) => {
      queryClient.setQueryData<TestPlan | undefined>(planQueryKey(operationId), (current) =>
        current ? { ...current, approvalStatus: "APPROVED" } : current,
      );
      // Approval moves the operation to APPROVED and bumps nothing revision-wise
      // on its own, but the operation's status did change server-side; refetch it
      // so downstream screens see the latest status rather than a stale cache.
      void queryClient.invalidateQueries({ queryKey: operationQueryKey(operationId) });
      void variables;
    },
  });
}
