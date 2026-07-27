import { useState, type ReactElement } from "react";
import { StatusBadge } from "../../components/StatusBadge";
import type { GeneratedTestCase } from "../../api/generated";
import { TEST_CASE_CATALOG, testCaseLabel } from "./testCaseCatalog";
import styles from "./GenerateScreen.module.css";

export interface TestCaseRowProps {
  testCase: GeneratedTestCase;
}

/**
 * Read-only row for one generated test case, with an expandable script
 * preview. There is no edit/add/delete affordance: the approved catalog is
 * fixed (always these five templates, in this order), and no endpoint exists
 * to mutate an individual case.
 */
export function TestCaseRow({ testCase }: TestCaseRowProps): ReactElement {
  const [expanded, setExpanded] = useState(false);
  const catalogEntry = TEST_CASE_CATALOG[testCase.templateId];
  const label = catalogEntry?.label ?? testCaseLabel(testCase.order);
  const description = catalogEntry?.description ?? testCase.templateId;
  const group = catalogEntry?.group ?? "General";
  const isReady = testCase.status === "READY";

  return (
    <>
      <tr>
        <td>{label}</td>
        <td>
          <button
            type="button"
            className={styles.scriptToggle}
            onClick={() => setExpanded((current) => !current)}
            aria-expanded={expanded}
          >
            {description}
          </button>
        </td>
        <td>
          <span className={styles.groupChip}>{group}</span>
        </td>
        <td>
          {isReady ? (
            <StatusBadge status="READY" label="Ready" />
          ) : (
            <StatusBadge status="FAILED" label="Not Ready" />
          )}
        </td>
      </tr>
      {expanded ? (
        <tr>
          <td colSpan={4} className={styles.scriptPreview}>
            <dl>
              <dt>Template</dt>
              <dd>
                {testCase.templateId} (v{testCase.templateVersion})
              </dd>
              <dt>Expected header</dt>
              <dd>{testCase.parameters.expectedHeader}</dd>
              <dt>Expected body</dt>
              <dd>{testCase.parameters.expectedBody}</dd>
              <dt>Expected button text</dt>
              <dd>{testCase.parameters.expectedButtonText}</dd>
              <dt>Expected redirect</dt>
              <dd>{testCase.parameters.expectedRedirectUrl}</dd>
              {!isReady && testCase.reason ? (
                <>
                  <dt>Not ready reason</dt>
                  <dd>{testCase.reason}</dd>
                </>
              ) : null}
            </dl>
          </td>
        </tr>
      ) : null}
    </>
  );
}
