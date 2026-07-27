import type { ReactElement } from "react";
import styles from "./StepProgress.module.css";

export interface Step {
  id: string;
  label: string;
  description?: string;
}

export interface StepProgressProps {
  steps: Step[];
  currentStepId: string;
}

type StepState = "completed" | "current" | "upcoming";

function getStepState(steps: Step[], currentStepId: string, index: number): StepState {
  const currentIndex = steps.findIndex((step) => step.id === currentStepId);
  if (currentIndex === -1) {
    return "upcoming";
  }
  if (index < currentIndex) {
    return "completed";
  }
  if (index === currentIndex) {
    return "current";
  }
  return "upcoming";
}

export function StepProgress({ steps, currentStepId }: StepProgressProps): ReactElement {
  return (
    <ol className={styles.list} aria-label="Operation progress">
      {steps.map((step, index) => {
        const state = getStepState(steps, currentStepId, index);
        const isLast = index === steps.length - 1;
        return (
          <li
            key={step.id}
            className={`${styles.step} ${styles[state]}`}
            aria-current={state === "current" ? "step" : "false"}
            aria-label={step.label}
          >
            <span className={styles.circle} aria-hidden="true">
              {state === "completed" ? "✓" : index + 1}
            </span>
            <span className={styles.labels}>
              <span className={styles.label}>{step.label}</span>
              {step.description ? <span>{step.description}</span> : null}
            </span>
            {!isLast ? (
              <span
                className={`${styles.connector} ${state === "completed" ? styles.completedConnector : ""}`}
                aria-hidden="true"
              />
            ) : null}
          </li>
        );
      })}
    </ol>
  );
}
