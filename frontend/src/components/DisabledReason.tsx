import { cloneElement, isValidElement, useId, type ReactElement, type ReactNode } from "react";
import styles from "./DisabledReason.module.css";

export interface DisabledReasonProps {
  /**
   * Why the wrapped control is disabled. When absent, the control is enabled
   * and rendered as-is with no reason text.
   */
  reason?: string | null;
  /** A single interactive element (e.g. a <button>) to gate. */
  children: ReactNode;
}

/**
 * Wraps an interactive control and, when a `reason` is supplied, disables the
 * control and renders a visible, screen-reader-linked explanation next to it.
 * This gives Tasks 10-11 a consistent way to surface gating rules such as
 * "Generate is only enabled once every OA has passed AI validation."
 */
export function DisabledReason({ reason, children }: DisabledReasonProps): ReactElement {
  const reasonId = useId();
  const isDisabled = Boolean(reason);

  const control =
    isValidElement(children) && isDisabled
      ? cloneElement(children as ReactElement<Record<string, unknown>>, {
          disabled: true,
          "aria-disabled": true,
          "aria-describedby": reasonId,
        })
      : children;

  return (
    <span className={styles.wrapper}>
      {control}
      {isDisabled ? (
        <span id={reasonId} role="note" className={styles.reason}>
          {reason}
        </span>
      ) : null}
    </span>
  );
}
