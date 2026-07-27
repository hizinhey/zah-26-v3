/**
 * Fixed, five-case-per-OA catalog metadata (display only). The catalog itself
 * — order, templateId, count — is owned by the backend
 * (com.opshub.generation.domain.TemplateId / TestPlanService); this table
 * only maps each fixed templateId to the label/group shown in mockup 3
 * ("Generate Test Cases"). It must never be used to add, remove, or reorder
 * cases — the five rows always come from the server response, in server order.
 */
export interface TestCaseCatalogEntry {
  label: string;
  description: string;
  group: string;
}

export const TEST_CASE_CATALOG: Record<string, TestCaseCatalogEntry> = {
  "android-oa-delivery-v1": {
    label: "TC-01",
    description: "Open OA form verification",
    group: "Smoke",
  },
  "android-thumbnail-v1": {
    label: "TC-02",
    description: "Validate input field",
    group: "Functional",
  },
  "android-content-v1": {
    label: "TC-03",
    description: "Verify content input",
    group: "Functional",
  },
  "android-button-text-v1": {
    label: "TC-04",
    description: "Validate button test",
    group: "UI",
  },
  "android-redirect-v1": {
    label: "TC-05",
    description: "Validate URL tracking",
    group: "Integration",
  },
};

export function testCaseLabel(order: number): string {
  return `TC-${String(order).padStart(2, "0")}`;
}
