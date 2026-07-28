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

export type Platform = "ANDROID" | "IOS" | "PC" | "WEB";

export interface OfficialAccountInput {
  platform: Platform;
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

/** Errors specific to ExecutionErrorHandler (com.opshub.execution.api.ExecutionController). */
export interface ExecutionError {
  code: "HUB_NOT_ONLINE" | "OPERATION_NOT_APPROVED";
  message: string;
  currentRevision: Revision | null;
}

export type ApiError = OperationError | RevisionConflictError | ExecutionError;

// ---------------------------------------------------------------------------
// Generation (test plans) and execution. These endpoints exist on the backend
// (com.opshub.generation.api.TestPlanController, com.opshub.execution.api.
// ExecutionController) but are not yet listed in contracts/openapi/opshub-v1.yaml.
// Shapes below are hand-derived from those controllers/services and from
// contracts/schemas/hub-envelope-v1.json for the envelope/test-case shapes.
// ---------------------------------------------------------------------------

export type TestCaseStatusValue = "READY" | "NOT_READY";

export interface TemplateParametersV1 {
  oaName: string;
  thumbnailUrl: string;
  expectedHeader: string;
  expectedBody: string;
  expectedButtonText: string;
  expectedRedirectUrl: string;
  expectedRedirectDomain: string;
}

/** One generated test case row, as returned by TestPlanService.TestCaseDto. */
export interface GeneratedTestCase {
  testCaseId: Uuid;
  planId: Uuid;
  oaOrder: number;
  order: number;
  templateId: string;
  templateVersion: number;
  templateSha256: string;
  parameters: TemplateParametersV1;
  status: TestCaseStatusValue;
  reason: string | null;
}

export interface TestPlan {
  planId: Uuid;
  operationId: Uuid;
  sourceRevision: Revision;
  templateCatalogVersion: string;
  status: "READY" | "GENERATION_FAILED";
  approvalStatus: "PENDING" | "APPROVED";
  testCases: GeneratedTestCase[];
}

export interface GeneratePlanRequest {
  expectedRevision: Revision;
}

export interface ApprovePlanRequest {
  expectedRevision: Revision;
}

export interface StartExecutionRequest {
  expectedRevision: Revision;
  idempotencyKey: string;
}

export interface ExecutionResponse {
  id: Uuid;
  operationId: Uuid;
  planId: Uuid;
  sourceRevision: Revision;
  status: string;
}

/** One persisted test result row, as returned by GET /executions/{executionId}. */
export interface ExecutionTestResult {
  id: Uuid;
  testCaseId: Uuid;
  attempt: number;
  status: TestResultStatus;
  durationMs: number | null;
  errorCategory: ErrorCategory | null;
  errorMessage: string | null;
}

/** One evidence file's metadata, as returned by GET /test-results/{testResultId}/evidence. */
export interface EvidenceItem {
  id: Uuid;
  evidenceType: string;
  sizeBytes: number;
  checksum: string;
  createdAt: string;
}

/** GET /executions/{executionId} response: current status plus every recorded test result. */
export interface ExecutionStatus {
  id: Uuid;
  operationId: Uuid;
  planId: Uuid;
  sourceRevision: Revision;
  status: string;
  results: ExecutionTestResult[];
}

/** One platform's connectivity/readiness state within a Hub. */
export interface HubPlatformStatus {
  platform: Platform;
  connectionStatus: "ONLINE" | "OFFLINE";
  transport: "WEBSOCKET" | "HTTPS_POLLING";
  deviceReady: boolean;
  runnerReady: boolean;
  lastHeartbeatAt: string | null;
}

/** One row from GET /api/v1/hubs, ordered most-recently-created first. */
export interface HubSummary {
  id: Uuid;
  name: string;
  createdAt: string;
  platforms: HubPlatformStatus[];
}

// --- Hub envelope (browser-facing realtime feed; see useExecutionChannel) ---

export interface JobOfferedPayload {
  executionId: Uuid;
  idempotencyKey: string;
  revision: Revision;
  platform: "ANDROID";
  testCases: GeneratedTestCase[];
}

export interface JobProgressPayload {
  executionId: Uuid;
  testCaseId: Uuid;
  status: TestCaseStatus;
  message: string;
}

export interface TestResultPayload {
  executionId: Uuid;
  testCaseId: Uuid;
  attempt: number;
  status: TestResultStatus;
  durationMs: number;
  errorCategory: ErrorCategory | null;
  errorMessage: string | null;
}

export interface HeartbeatPayload {
  deviceReady: boolean;
  runnerReady: boolean;
}

export type HubEnvelopeV1 =
  | { messageId: Uuid; version: 1; type: "JOB_OFFERED"; timestamp: Timestamp; payload: JobOfferedPayload }
  | { messageId: Uuid; version: 1; type: "JOB_PROGRESS"; timestamp: Timestamp; payload: JobProgressPayload }
  | { messageId: Uuid; version: 1; type: "TEST_RESULT"; timestamp: Timestamp; payload: TestResultPayload }
  | { messageId: Uuid; version: 1; type: "HEARTBEAT"; timestamp: Timestamp; payload: HeartbeatPayload };
