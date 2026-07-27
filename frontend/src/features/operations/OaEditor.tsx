import type { ReactElement } from "react";
import type { OfficialAccountInput } from "../../api/generated";
import { ContentPreview } from "./ContentPreview";
import styles from "./OaEditor.module.css";

const CONTENT_MAX_LENGTH = 500;

export type EditableOaField = "thumbnailUrl" | "content" | "buttonText" | "redirectUrl";

export interface OaEditorProps {
  oa: OfficialAccountInput;
  onFieldChange: (field: EditableOaField, value: string) => void;
}

function FieldLabel({ number, text }: { number: number; text: string }): ReactElement {
  return (
    <span className={styles.fieldLabel}>
      <span className={styles.fieldNumber} aria-hidden="true">
        {number}
      </span>
      {text}
      <span aria-hidden="true"> *</span>
    </span>
  );
}

export function OaEditor({ oa, onFieldChange }: OaEditorProps): ReactElement {
  return (
    <div className={styles.editor}>
      <div className={styles.field}>
        <label className={styles.fieldLabelWrapper}>
          <FieldLabel number={1} text="Platform" />
          {/* Only ANDROID is a valid platform per OfficialAccountInput (contracts/openapi/opshub-v1.yaml);
              this MVP does not offer a selector, it states the fixed platform. */}
          <span className={styles.platformBadge} data-testid="oa-platform">
            Android
          </span>
        </label>
      </div>

      <div className={styles.field}>
        <label className={styles.fieldLabelWrapper} htmlFor="oa-thumbnail-url">
          <FieldLabel number={2} text="Thumb URL" />
        </label>
        <input
          id="oa-thumbnail-url"
          className={styles.input}
          type="text"
          value={oa.thumbnailUrl}
          placeholder="https://..."
          onChange={(event) => onFieldChange("thumbnailUrl", event.target.value)}
        />
      </div>

      <div className={styles.field}>
        <label className={styles.fieldLabelWrapper} htmlFor="oa-content">
          <FieldLabel number={3} text="Content" />
        </label>
        <textarea
          id="oa-content"
          className={styles.textarea}
          value={oa.content}
          maxLength={CONTENT_MAX_LENGTH}
          rows={4}
          onChange={(event) => onFieldChange("content", event.target.value)}
        />
        <div className={styles.charCount}>
          {oa.content.length} / {CONTENT_MAX_LENGTH}
        </div>
        <ContentPreview content={oa.content} />
      </div>

      <div className={styles.field}>
        <label className={styles.fieldLabelWrapper} htmlFor="oa-button-text">
          <FieldLabel number={4} text="Button Text" />
        </label>
        <input
          id="oa-button-text"
          className={styles.input}
          type="text"
          value={oa.buttonText}
          onChange={(event) => onFieldChange("buttonText", event.target.value)}
        />
      </div>

      <div className={styles.field}>
        <label className={styles.fieldLabelWrapper} htmlFor="oa-redirect-url">
          <FieldLabel number={5} text="URL Direction" />
        </label>
        <input
          id="oa-redirect-url"
          className={styles.input}
          type="text"
          value={oa.redirectUrl}
          placeholder="https://..."
          onChange={(event) => onFieldChange("redirectUrl", event.target.value)}
        />
      </div>
    </div>
  );
}
