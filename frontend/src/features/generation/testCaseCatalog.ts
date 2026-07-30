import type { TemplateParametersV1 } from "../../api/generated";

/**
 * Fixed, five-case-per-OA catalog metadata (display only). The catalog itself
 * — order, templateId, count — is owned by the backend
 * (com.opshub.generation.domain.TemplateId / TestPlanService); this table
 * only maps each fixed templateId to the label/group/relevant-fields shown in
 * mockup 3 ("Generate Test Cases") and the Execute screen's script preview.
 * It must never be used to add, remove, or reorder cases — the five rows
 * always come from the server response, in server order.
 */
export interface TestCaseCatalogField {
  key: keyof TemplateParametersV1;
  label: string;
}

export interface TestCaseCatalogEntry {
  label: string;
  description: string;
  group: string;
  /** Which of the shared TemplateParameters this specific case actually asserts against. */
  fields: TestCaseCatalogField[];
}

export const TEST_CASE_CATALOG: Record<string, TestCaseCatalogEntry> = {
  "android-oa-delivery-v1": {
    label: "TC-01",
    description: "Open OA form verification",
    group: "Smoke",
    fields: [{ key: "oaName", label: "OA name" }],
  },
  "android-thumbnail-v1": {
    label: "TC-02",
    description: "Validate reward image",
    group: "Functional",
    fields: [{ key: "thumbnailUrl", label: "Expected thumbnail" }],
  },
  "android-content-v1": {
    label: "TC-03",
    description: "Verify content input",
    group: "Functional",
    fields: [
      { key: "expectedHeader", label: "Expected header" },
      { key: "expectedBody", label: "Expected body" },
    ],
  },
  "android-button-text-v1": {
    label: "TC-04",
    description: "Validate button test",
    group: "UI",
    fields: [{ key: "expectedButtonText", label: "Expected button text" }],
  },
  "android-redirect-v1": {
    label: "TC-05",
    description: "Validate URL tracking",
    group: "Integration",
    fields: [
      { key: "expectedRedirectUrl", label: "Expected redirect" },
      { key: "expectedRedirectDomain", label: "Expected redirect domain" },
    ],
  },
};

export function testCaseLabel(order: number): string {
  return `TC-${String(order).padStart(2, "0")}`;
}
