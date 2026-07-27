import type {
  ApiError,
  ApprovePlanRequest,
  CreateOperationRequest,
  ExecutionResponse,
  GeneratePlanRequest,
  Operation,
  ReplaceOasRequest,
  StartExecutionRequest,
  TestPlan,
  ValidateOperationRequest,
  ValidationRun,
} from "./generated";

const API_BASE = "/api/v1";

export class ApiClientError extends Error {
  readonly status: number;
  readonly body: ApiError | undefined;

  constructor(status: number, body: ApiError | undefined, message: string) {
    super(message);
    this.name = "ApiClientError";
    this.status = status;
    this.body = body;
  }
}

async function request<TResponse>(path: string, init?: RequestInit): Promise<TResponse> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
    },
  });

  if (!response.ok) {
    let body: ApiError | undefined;
    try {
      body = (await response.json()) as ApiError;
    } catch {
      body = undefined;
    }
    throw new ApiClientError(
      response.status,
      body,
      body?.message ?? `Request to ${path} failed with status ${response.status}`,
    );
  }

  if (response.status === 204) {
    return undefined as TResponse;
  }

  return (await response.json()) as TResponse;
}

export const apiClient = {
  createOperation(payload: CreateOperationRequest): Promise<Operation> {
    return request<Operation>("/operations", {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  getOperation(operationId: string): Promise<Operation> {
    return request<Operation>(`/operations/${operationId}`);
  },

  replaceOperationOas(operationId: string, payload: ReplaceOasRequest): Promise<Operation> {
    return request<Operation>(`/operations/${operationId}/oas`, {
      method: "PUT",
      body: JSON.stringify(payload),
    });
  },

  validateOperation(
    operationId: string,
    payload: ValidateOperationRequest,
  ): Promise<ValidationRun> {
    return request<ValidationRun>(`/operations/${operationId}/validate`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  generatePlan(operationId: string, payload: GeneratePlanRequest): Promise<TestPlan> {
    return request<TestPlan>(`/operations/${operationId}/plans`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  approvePlan(planId: string, payload: ApprovePlanRequest): Promise<void> {
    return request<void>(`/plans/${planId}/approve`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },

  startExecution(operationId: string, payload: StartExecutionRequest): Promise<ExecutionResponse> {
    return request<ExecutionResponse>(`/operations/${operationId}/executions`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
  },
};
