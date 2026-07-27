import { useMutation, useQuery, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { apiClient, ApiClientError } from "../../api/client";
import type {
  Operation,
  OfficialAccountInput,
  RevisionConflictError,
  ValidationRun,
} from "../../api/generated";

export const NEW_OPERATION_ID = "new";

export function operationQueryKey(operationId: string): readonly ["operation", string] {
  return ["operation", operationId] as const;
}

export function validationQueryKey(operationId: string): readonly ["validation", string] {
  return ["validation", operationId] as const;
}

/** Fetches the operation from the server; the server's revision is the source of truth. */
export function useOperationQuery(operationId: string): UseQueryResult<Operation> {
  return useQuery({
    queryKey: operationQueryKey(operationId),
    queryFn: () => apiClient.getOperation(operationId),
    enabled: operationId !== NEW_OPERATION_ID,
  });
}

/**
 * There is no GET endpoint for a validation run; the latest run is whatever
 * the validate mutation last wrote into the cache for this operation. This
 * hook just exposes that cached value with query-shaped ergonomics.
 */
export function useValidationQuery(operationId: string): UseQueryResult<ValidationRun> {
  return useQuery({
    queryKey: validationQueryKey(operationId),
    queryFn: () => Promise.reject(new Error("no validation run has been fetched yet")),
    enabled: false,
    retry: false,
  });
}

export function useCreateOperationMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (jiraId: string) => apiClient.createOperation({ jiraId }),
    onSuccess: (operation) => {
      queryClient.setQueryData(operationQueryKey(operation.id), operation);
    },
  });
}

export function useReplaceOasMutation(operationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: { expectedRevision: number; oas: OfficialAccountInput[] }) =>
      apiClient.replaceOperationOas(operationId, variables),
    onSuccess: (operation) => {
      queryClient.setQueryData(operationQueryKey(operationId), operation);
      // The operation revision moved: any previously fetched validation run
      // for the prior revision is stale and must not be trusted for gating.
      queryClient.removeQueries({ queryKey: validationQueryKey(operationId) });
    },
  });
}

export function useValidateOperationMutation(operationId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (expectedRevision: number) =>
      apiClient.validateOperation(operationId, { expectedRevision }),
    onSuccess: (run) => {
      queryClient.setQueryData(validationQueryKey(operationId), run);
    },
  });
}

export function isRevisionConflict(
  error: unknown,
): error is ApiClientError & { body: RevisionConflictError } {
  return (
    error instanceof ApiClientError &&
    error.status === 409 &&
    error.body?.code === "REVISION_CONFLICT"
  );
}
