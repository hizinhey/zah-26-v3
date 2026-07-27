import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { DisabledReason } from "./DisabledReason";

describe("DisabledReason", () => {
  it("renders the visible reason text when a reason is supplied", () => {
    render(
      <DisabledReason reason="Generate is only enabled once every OA has passed AI validation.">
        <button type="button">Generate</button>
      </DisabledReason>,
    );

    expect(
      screen.getByText("Generate is only enabled once every OA has passed AI validation."),
    ).toBeInTheDocument();
  });

  it("wires the reason note as a discoverable role=note element", () => {
    render(
      <DisabledReason reason="Blocked until validation completes.">
        <button type="button">Generate</button>
      </DisabledReason>,
    );

    const note = screen.getByRole("note");
    expect(note).toHaveTextContent("Blocked until validation completes.");
  });

  it("associates the disabled control with the reason via aria-describedby", () => {
    render(
      <DisabledReason reason="Blocked until validation completes.">
        <button type="button">Generate</button>
      </DisabledReason>,
    );

    const button = screen.getByRole("button", { name: /generate/i });
    const note = screen.getByRole("note");

    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-disabled", "true");
    expect(button).toHaveAttribute("aria-describedby", note.id);
    expect(note.id).toBeTruthy();
  });

  it("renders no reason note and leaves the control enabled when reason is absent", () => {
    render(
      <DisabledReason>
        <button type="button">Generate</button>
      </DisabledReason>,
    );

    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    const button = screen.getByRole("button", { name: /generate/i });
    expect(button).not.toBeDisabled();
  });

  it("allows the wrapped control to receive keyboard focus when enabled", async () => {
    const user = userEvent.setup();
    render(
      <DisabledReason>
        <button type="button">Generate</button>
      </DisabledReason>,
    );

    const button = screen.getByRole("button", { name: /generate/i });

    await user.tab();

    expect(document.activeElement).toBe(button);
    expect(button.matches(":focus-visible")).toBe(true);
  });
});
