import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { StepProgress, type Step } from "./StepProgress";

const steps: Step[] = [
  { id: "input", label: "Input OA Details" },
  { id: "verify", label: "Verify Inputs" },
  { id: "generate", label: "Generate Test Cases" },
  { id: "execute", label: "Confirm & Start Execution" },
];

describe("StepProgress", () => {
  it("marks steps before the current step as completed", () => {
    render(<StepProgress steps={steps} currentStepId="generate" />);

    const input = screen.getByRole("listitem", { name: /input oa details/i });
    const verify = screen.getByRole("listitem", { name: /verify inputs/i });

    expect(input).toHaveAttribute("aria-current", "false");
    expect(input.className).toMatch(/completed/);
    expect(verify.className).toMatch(/completed/);
  });

  it("marks the current step with aria-current=step and a distinct visual state", () => {
    render(<StepProgress steps={steps} currentStepId="generate" />);

    const current = screen.getByRole("listitem", { name: /generate test cases/i });
    expect(current).toHaveAttribute("aria-current", "step");
    expect(current.className).toMatch(/current/);
  });

  it("marks steps after the current step as upcoming, not completed", () => {
    render(<StepProgress steps={steps} currentStepId="verify" />);

    const execute = screen.getByRole("listitem", { name: /confirm & start execution/i });
    expect(execute.className).not.toMatch(/completed/);
    expect(execute.className).not.toMatch(/current/);
    expect(execute).toHaveAttribute("aria-current", "false");
  });

  it("exposes the step list with an accessible name", () => {
    render(<StepProgress steps={steps} currentStepId="input" />);
    expect(screen.getByRole("list", { name: /operation progress/i })).toBeInTheDocument();
  });
});
