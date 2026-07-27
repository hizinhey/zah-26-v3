import type { ReactElement } from "react";
import { Navigate, Outlet, createBrowserRouter, useParams } from "react-router-dom";

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

// Placeholder screens; Tasks 10-11 replace these with real step content.
function StepPlaceholder({ label }: { label: string }): ReactElement {
  const { operationId } = useParams();
  return (
    <div>
      <h1 className="ops-page-title">{label}</h1>
      <p>Operation: {operationId}</p>
    </div>
  );
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
      { path: "input", element: <StepPlaceholder label="Input OA Details" /> },
      { path: "verify", element: <StepPlaceholder label="Verify Inputs" /> },
      { path: "generate", element: <StepPlaceholder label="Generate Test Cases" /> },
      { path: "execute", element: <StepPlaceholder label="Confirm & Start Execution" /> },
    ],
  },
]);
