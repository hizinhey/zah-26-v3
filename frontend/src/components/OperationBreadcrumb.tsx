import type { ReactElement } from "react";
import { Link, useNavigate } from "react-router-dom";
import { OPERATION_STEPS, type OperationStepId } from "../app/router";
import styles from "./OperationBreadcrumb.module.css";

export interface OperationBreadcrumbProps {
  operationId: string;
  currentStepId: OperationStepId;
}

export function OperationBreadcrumb({ operationId, currentStepId }: OperationBreadcrumbProps): ReactElement {
  const navigate = useNavigate();
  const currentIndex = OPERATION_STEPS.findIndex((step) => step.id === currentStepId);
  const currentStep = OPERATION_STEPS[currentIndex];
  const previousStep = currentIndex > 0 ? OPERATION_STEPS[currentIndex - 1] : null;

  return (
    <div className={styles.bar}>
      <button
        type="button"
        className={styles.backButton}
        onClick={() => navigate(`/operations/${operationId}/${previousStep?.path}`)}
        disabled={!previousStep}
      >
        ← Back
      </button>
      <nav aria-label="Breadcrumb">
        <ol className={styles.crumbs}>
          <li className={styles.crumb}>Operations</li>
          <li className={styles.separator} aria-hidden="true">
            /
          </li>
          <li className={styles.crumb}>
            <Link className={styles.crumbLink} to={`/operations/${operationId}/input`}>
              {operationId}
            </Link>
          </li>
          <li className={styles.separator} aria-hidden="true">
            /
          </li>
          <li className={styles.crumb} aria-current="page">
            {currentStep?.label}
          </li>
        </ol>
      </nav>
    </div>
  );
}
