/**
 * Types hand-derived from contracts/openapi/opshub-v1.yaml and
 * contracts/schemas/hub-envelope-v1.json. This file stands in for a generated
 * client; if a codegen step is introduced later it should replace this file
 * without changing the shapes consumed by src/api/client.ts.
 */

export type Uuid = string;
export type Revision = number;
export type Timestamp = string;

export type FieldStatus = "PASSED" | "WARNING" | "FAILED" | "UNABLE_TO_CHECK" | "INVALID";

export type OperationStatus =
  | "DRAFT"
  | "VALIDATING"
  | "VALIDATION_FAILED"
  | "VALIDATED"
  | "GENERATING"
  | "GENERATION_FAILED"
  | "READY_FOR_APPROVAL"
  | "APPROVED"
  | "QUEUED"
  | "RUNNING"
  | "PASSED"
  | "FAILED"
  | "ERROR";

export type TestCaseStatus = "PENDING" | "READY" | "RUNNING" | "PASSED" | "FAILED" | "ERROR";

export type TestResultStatus = "PASSED" | "FAILED" | "ERROR";

export type ErrorCategory =
  | "ASSERTION_FAILURE"
  | "INFRASTRUCTURE"
  | "TIMEOUT"
  | "CONFIGURATION"
  | "UNKNOWN";

export interface CreateOperationRequest {
  jiraId: string;
}

export interface OfficialAccountInput {
  platform: "ANDROID";
  oaName: string;
  thumbnailUrl: string;
  content: string;
  buttonText: string;
  redirectUrl: string;
}

export interface OfficialAccount extends OfficialAccountInput {
  id: Uuid;
  oaOrder: number;
}

export interface ReplaceOasRequest {
  expectedRevision: Revision;
  oas: OfficialAccountInput[];
}

export interface ValidateOperationRequest {
  expectedRevision: Revision;
}

export interface Operation {
  id: Uuid;
  jiraId: string;
  revision: Revision;
  status: OperationStatus;
  createdAt: Timestamp;
  updatedAt: Timestamp;
  oas: OfficialAccount[];
}

export interface FieldFinding {
  fieldName: string;
  validatorType: string;
  status: FieldStatus;
  issue: string | null;
  location: string | null;
  suggestion: string | null;
  severity: string | null;
  confidence: number | null;
}

export interface ValidationRun {
  id: Uuid;
  operationId: Uuid;
  sourceRevision: Revision;
  status: "VALIDATION_FAILED" | "VALIDATED";
  findings: FieldFinding[];
  canGenerate: boolean;
  generateDisabledReasons: string[];
}

export interface OperationError {
  code: "INVALID_REQUEST" | "UNSUPPORTED_PLATFORM" | "OPERATION_NOT_FOUND";
  message: string;
  currentRevision: Revision | null;
}

export interface RevisionConflictError {
  code: "REVISION_CONFLICT";
  message: string;
  currentRevision: Revision;
}

export type ApiError = OperationError | RevisionConflictError;
