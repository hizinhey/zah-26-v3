import type { ComponentType, ReactElement, SVGProps } from "react";
import type { OfficialAccountInput, Platform } from "../../api/generated";
import { AndroidIcon, IosIcon, PcIcon, WebIcon } from "../../components/icons";
import { ContentPreview } from "./ContentPreview";
import styles from "./OaEditor.module.css";

const CONTENT_MAX_LENGTH = 3000;

export type EditableOaField = "platform" | "thumbnailUrl" | "content" | "buttonText" | "redirectUrl";

// Only ANDROID is generated/validated/executed end-to-end today (backend rejects any other
// platform when saving OAs). The other three are offered here to match the mockup's platform
// selector; picking one will surface a normal validation/save error until they're implemented.
const PLATFORMS: { value: Platform; label: string; Icon: ComponentType<SVGProps<SVGSVGElement>> }[] = [
  { value: "ANDROID", label: "Android", Icon: AndroidIcon },
  { value: "IOS", label: "iOS", Icon: IosIcon },
  { value: "PC", label: "PC", Icon: PcIcon },
  { value: "WEB", label: "Web", Icon: WebIcon },
];

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
      <span className={styles.requiredMark} aria-hidden="true">
        {" "}
        *
      </span>
    </span>
  );
}

export function OaEditor({ oa, onFieldChange }: OaEditorProps): ReactElement {
  return (
    <div className={styles.editor}>
      <div className={styles.field}>
        <label className={styles.fieldLabelWrapper}>
          <FieldLabel number={1} text="Platform" />
        </label>
        <div className={styles.platformGroup} role="group" aria-label="Platform" data-testid="oa-platform">
          {PLATFORMS.map((platform) => (
            <button
              key={platform.value}
              type="button"
              className={styles.platformOption}
              aria-pressed={oa.platform === platform.value}
              data-selected={oa.platform === platform.value || undefined}
              onClick={() => onFieldChange("platform", platform.value)}
            >
              <platform.Icon />
              {platform.label}
            </button>
          ))}
        </div>
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
          <FieldLabel number={5} text="URL Redirect" />
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
