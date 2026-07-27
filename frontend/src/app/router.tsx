import type { ReactElement } from "react";
import { Navigate, Outlet, createBrowserRouter } from "react-router-dom";
import { InputScreen } from "../features/operations/InputScreen";
import { VerifyScreen } from "../features/validation/VerifyScreen";
import { GenerateScreen } from "../features/generation/GenerateScreen";
import { ExecuteScreen } from "../features/execution/ExecuteScreen";

export type OperationStepId = "input" | "verify" | "generate" | "execute";

export const OPERATION_STEPS: { id: OperationStepId; path: string; label: string }[] = [
  { id: "input", path: "input", label: "Input OA Details" },
  { id: "verify", path: "verify", label: "Verify Inputs" },
  { id: "generate", path: "generate", label: "Generate Test Cases" },
  { id: "execute", path: "execute", label: "Confirm & Start Execution" },
];

/**
 * Gating hook placeholder. Tasks 10-11 will implement the real rule (e.g. a
 * step is reachable only once the previous step's server-derived status
 * allows it, per ValidationRun.canGenerate and friends). For now every step
 * is reachable so routing/navigation can be exercised end-to-end; screens
 * are expected to call this before rendering step content or navigating.
 */
export function useStepGate(_stepId: OperationStepId): { allowed: boolean; reason: string | null } {
  return { allowed: true, reason: null };
}

function OperationLayout(): ReactElement {
  return <Outlet />;
}

export const router = createBrowserRouter([
  {
    path: "/",
    element: <Navigate to="/operations/new/input" replace />,
  },
  {
    path: "/operations/:operationId",
    element: <OperationLayout />,
    children: [
      { index: true, element: <Navigate to="input" replace /> },
      { path: "input", element: <InputScreen /> },
      { path: "verify", element: <VerifyScreen /> },
      { path: "generate", element: <GenerateScreen /> },
      { path: "execute", element: <ExecuteScreen /> },
    ],
  },
]);
