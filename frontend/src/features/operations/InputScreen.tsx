import { useEffect, useRef, useState, type ReactElement } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { StepProgress } from "../../components/StepProgress";
import { OperationBreadcrumb } from "../../components/OperationBreadcrumb";
import { Card } from "../../components/Card";
import { DisabledReason } from "../../components/DisabledReason";
import { OPERATION_STEPS } from "../../app/router";
import type { OfficialAccountInput } from "../../api/generated";
import { OaEditor, type EditableOaField } from "./OaEditor";
import { SparkleIcon } from "../../components/icons";
import {
  NEW_OPERATION_ID,
  isRevisionConflict,
  operationQueryKey,
  useCreateOperationMutation,
  useOperationQuery,
  useReplaceOasMutation,
  useValidateOperationMutation,
  validationQueryKey,
} from "./useOperation";
import styles from "./InputScreen.module.css";

function emptyOa(): OfficialAccountInput {
  return {
    platform: "ANDROID",
    oaName: "",
    thumbnailUrl: "",
    content: "",
    buttonText: "",
    redirectUrl: "",
  };
}

function withOaNames(oas: OfficialAccountInput[]): OfficialAccountInput[] {
  return oas.map((oa, index) => ({ ...oa, oaName: `OA #${index + 1}` }));
}

function isOaComplete(oa: OfficialAccountInput): boolean {
  return (
    oa.thumbnailUrl.trim().length > 0 &&
    oa.content.trim().length > 0 &&
    oa.buttonText.trim().length > 0 &&
    oa.redirectUrl.trim().length > 0
  );
}

export function InputScreen(): ReactElement {
  const params = useParams<{ operationId: string }>();
  const operationId = params.operationId ?? NEW_OPERATION_ID;
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const queryClient = useQueryClient();

  const operationQuery = useOperationQuery(operationId);
  const createOperation = useCreateOperationMutation();
  const replaceOas = useReplaceOasMutation(operationId === NEW_OPERATION_ID ? "" : operationId);
  const validate = useValidateOperationMutation(operationId === NEW_OPERATION_ID ? "" : operationId);

  const [jiraId, setJiraId] = useState("");
  const [oas, setOas] = useState<OfficialAccountInput[]>([emptyOa()]);
  const [activeIndex, setActiveIndex] = useState(0);
  const [conflictMessage, setConflictMessage] = useState<string | null>(null);
  const initialized = useRef(false);

  useEffect(() => {
    if (initialized.current) {
      return;
    }
    if (operationQuery.data) {
      initialized.current = true;
      setJiraId(operationQuery.data.jiraId);
      setOas(
        operationQuery.data.oas.length > 0
          ? operationQuery.data.oas.map((oa) => ({ ...oa }))
          : [emptyOa()],
      );
    }
  }, [operationQuery.data]);

  useEffect(() => {
    const requestedOa = searchParams.get("oa");
    if (requestedOa) {
      const index = Number.parseInt(requestedOa, 10) - 1;
      if (Number.isInteger(index) && index >= 0 && index < oas.length) {
        setActiveIndex(index);
      }
    }
    // Only react to the initial navigation intent, not every OA edit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  function updateOaField(index: number, field: EditableOaField, value: string): void {
    setOas((current) =>
      current.map((oa, oaIndex) => (oaIndex === index ? { ...oa, [field]: value } : oa)),
    );
  }

  function addOa(): void {
    setOas((current) => {
      const next = [...current, emptyOa()];
      setActiveIndex(next.length - 1);
      return next;
    });
  }

  function removeOa(index: number): void {
    setOas((current) => {
      if (current.length <= 1) {
        return current;
      }
      const next = current.filter((_, oaIndex) => oaIndex !== index);
      setActiveIndex((activeCurrent) => Math.max(0, Math.min(activeCurrent, next.length - 1)));
      return next;
    });
  }

  function moveOa(index: number, direction: -1 | 1): void {
    setOas((current) => {
      const targetIndex = index + direction;
      if (targetIndex < 0 || targetIndex >= current.length) {
        return current;
      }
      const next = [...current];
      const [moved] = next.splice(index, 1);
      next.splice(targetIndex, 0, moved);
      setActiveIndex(targetIndex);
      return next;
    });
  }

  const requiredFieldsFilled = jiraId.trim().length > 0 && oas.every(isOaComplete);
  const isSubmitting = createOperation.isPending || replaceOas.isPending || validate.isPending;
  const submitDisabledReason = !requiredFieldsFilled
    ? "Vui lòng nhập đầy đủ thông tin cho OA đang chọn."
    : null;

  async function handleSubmit(): Promise<void> {
    setConflictMessage(null);
    const payload = withOaNames(oas);
    try {
      let currentOperationId = operationId;
      let expectedRevision: number;

      if (operationId === NEW_OPERATION_ID) {
        const created = await createOperation.mutateAsync(jiraId);
        currentOperationId = created.id;
        expectedRevision = created.revision;
      } else {
        expectedRevision = operationQuery.data?.revision ?? 1;
      }

      const updated = await replaceOasForOperation(currentOperationId, expectedRevision, payload);
      const validated = await validateForOperation(currentOperationId, updated.revision);
      // The replaceOas/validate mutations only auto-populate the query cache
      // when they run bound to the operationId already in the URL. For a
      // brand-new operation the id becomes known mid-flow, so seed the cache
      // explicitly here for whatever screen renders next (Screen 2 reads the
      // validation run purely from cache; there is no GET for it).
      queryClient.setQueryData(operationQueryKey(currentOperationId), updated);
      queryClient.setQueryData(validationQueryKey(currentOperationId), validated);
      navigate(`/operations/${currentOperationId}/verify`);
    } catch (error) {
      if (isRevisionConflict(error)) {
        setConflictMessage(
          `This operation changed elsewhere (current revision ${error.body.currentRevision}). Refresh and try again.`,
        );
        await operationQuery.refetch();
        return;
      }
      throw error;
    }
  }

  // replaceOas/validate mutations are bound to the operationId known at
  // render time; for a brand-new operation that id only exists after
  // createOperation resolves, so call the API client directly with the
  // freshly created id rather than relying on the pre-bound mutation.
  async function replaceOasForOperation(
    targetOperationId: string,
    expectedRevision: number,
    oasPayload: OfficialAccountInput[],
  ) {
    if (targetOperationId === operationId) {
      return replaceOas.mutateAsync({ expectedRevision, oas: oasPayload });
    }
    const { apiClient } = await import("../../api/client");
    const operation = await apiClient.replaceOperationOas(targetOperationId, {
      expectedRevision,
      oas: oasPayload,
    });
    return operation;
  }

  async function validateForOperation(targetOperationId: string, expectedRevision: number) {
    if (targetOperationId === operationId) {
      return validate.mutateAsync(expectedRevision);
    }
    const { apiClient } = await import("../../api/client");
    return apiClient.validateOperation(targetOperationId, { expectedRevision });
  }

  const activeOa = oas[activeIndex] ?? oas[0];

  return (
    <div>
      <OperationBreadcrumb operationId={operationId} currentStepId="input" />
      <h1 className="ops-page-title">Test Operations</h1>
      <StepProgress steps={OPERATION_STEPS} currentStepId="input" />

      <div className={styles.layout}>
        <Card title="Jira & OA Input">
          <div className={styles.field}>
            <label className={styles.label} htmlFor="jira-id">
              Jira ID{" "}
              <span className={styles.requiredMark} aria-hidden="true">
                *
              </span>
            </label>
            <input
              id="jira-id"
              className={styles.input}
              type="text"
              value={jiraId}
              onChange={(event) => setJiraId(event.target.value)}
            />
          </div>

          <div className={styles.tabsWrapper}>
            <div className={styles.tabs} role="tablist" aria-label="Official accounts">
              {oas.map((oa, index) => (
                <div key={index} className={styles.tabGroup}>
                  <button
                    type="button"
                    role="tab"
                    aria-selected={index === activeIndex}
                    className={`${styles.tab} ${index === activeIndex ? styles.tabActive : ""}`}
                    onClick={() => setActiveIndex(index)}
                  >
                    OA #{index + 1}
                  </button>
                  {index === activeIndex ? (
                    <span className={styles.tabActions}>
                      <button
                        type="button"
                        aria-label={`Move OA #${index + 1} earlier`}
                        className={styles.tabActionButton}
                        disabled={index === 0}
                        onClick={() => moveOa(index, -1)}
                      >
                        ↑
                      </button>
                      <button
                        type="button"
                        aria-label={`Move OA #${index + 1} later`}
                        className={styles.tabActionButton}
                        disabled={index === oas.length - 1}
                        onClick={() => moveOa(index, 1)}
                      >
                        ↓
                      </button>
                      <button
                        type="button"
                        aria-label={`Remove OA #${index + 1}`}
                        className={styles.tabActionButton}
                        disabled={oas.length <= 1}
                        onClick={() => removeOa(index)}
                      >
                        ✕
                      </button>
                    </span>
                  ) : null}
                </div>
              ))}
              <button type="button" className={styles.addOa} onClick={addOa}>
                + Add OA
              </button>
            </div>
          </div>

          {activeOa ? (
            <OaEditor
              oa={activeOa}
              onFieldChange={(field, value) => updateOaField(activeIndex, field, value)}
            />
          ) : null}

          {conflictMessage ? (
            <p className={styles.conflict} role="alert">
              {conflictMessage}
            </p>
          ) : null}
        </Card>

        <div className={styles.overviewColumn}>
          <Card title="OA Overview" className={styles.overview}>
            <div className={styles.overviewRow}>
              <span>Tổng số OA</span>
              <strong className={styles.overviewValue}>{oas.length}</strong>
            </div>
            <div className={styles.overviewRow}>
              <span>Đã nhập đầy đủ</span>
              <strong className={styles.overviewValue}>{oas.filter(isOaComplete).length}</strong>
            </div>
            <div className={styles.overviewRow}>
              <span>Chưa hoàn tất</span>
              <strong className={styles.overviewValue}>
                {oas.filter((oa) => !isOaComplete(oa)).length}
              </strong>
            </div>
          </Card>

          <div className={styles.actions}>
            <div className={styles.actionButtonRow}>
              <DisabledReason reason={submitDisabledReason}>
                <button
                  type="button"
                  className={`ops-primary-button ${styles.validateButton}`}
                  onClick={handleSubmit}
                  disabled={isSubmitting}
                >
                  <SparkleIcon />
                  {isSubmitting ? "Validating…" : "AI Validation"}
                </button>
              </DisabledReason>
            </div>
            <p className={styles.hint}>
              Vui lòng nhập đầy đủ thông tin cho OA đang chọn.
              <br />
              Sau khi Verify, bạn có thể tiếp tục với các bước tiếp theo.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
