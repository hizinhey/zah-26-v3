import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { StatusBadge } from "./StatusBadge";

describe("StatusBadge", () => {
  it("renders a passing status with the success color class and an accessible name", () => {
    render(<StatusBadge status="PASSED" />);
    const badge = screen.getByText("Passed");
    expect(badge.className).toMatch(/success/);
  });

  it("renders a failed status with the danger color class", () => {
    render(<StatusBadge status="FAILED" />);
    const badge = screen.getByText("Failed");
    expect(badge.className).toMatch(/danger/);
  });

  it("renders a warning status with the warning color class", () => {
    render(<StatusBadge status="WARNING" />);
    const badge = screen.getByText("Warning");
    expect(badge.className).toMatch(/warning/);
  });

  it("renders in-progress statuses with the info color class", () => {
    render(<StatusBadge status="RUNNING" />);
    const badge = screen.getByText("Running");
    expect(badge.className).toMatch(/info/);
  });

  it("renders neutral/unknown statuses with the neutral color class", () => {
    render(<StatusBadge status="PENDING" />);
    const badge = screen.getByText("Pending");
    expect(badge.className).toMatch(/neutral/);
  });

  it("renders a status with multi-word enum value as readable humanized text", () => {
    render(<StatusBadge status="UNABLE_TO_CHECK" />);
    expect(screen.getByText(/unable to check/i)).toBeInTheDocument();
  });
});
