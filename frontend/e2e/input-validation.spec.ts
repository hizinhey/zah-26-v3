import { test, expect, type Page } from "@playwright/test";

/**
 * End-to-end flow across Screen 1 (Input OA Details) and Screen 2 (Verify
 * Inputs). The backend is not available in this environment, so the REST
 * API is stubbed at the network layer with contracts/openapi/opshub-v1.yaml
 * response shapes. This still exercises real routing, TanStack Query
 * wiring, and the two screens end-to-end in a real browser.
 */
async function mockApi(page: Page): Promise<void> {
  const operation = {
    id: "11111111-1111-1111-1111-111111111111",
    jiraId: "ZVAS-1424",
    revision: 1,
    status: "DRAFT",
    createdAt: "2026-07-27T00:00:00Z",
    updatedAt: "2026-07-27T00:00:00Z",
    oas: [],
  };

  await page.route("**/api/v1/operations", async (route) => {
    if (route.request().method() === "POST") {
      await route.fulfill({ status: 201, json: operation });
      return;
    }
    await route.continue();
  });

  await page.route(`**/api/v1/operations/${operation.id}/oas`, async (route) => {
    await route.fulfill({
      status: 200,
      json: { ...operation, revision: 2, status: "DRAFT", oas: [
        {
          id: "22222222-2222-2222-2222-222222222222",
          oaOrder: 1,
          platform: "ANDROID",
          oaName: "OA #1",
          thumbnailUrl: "https://example.com/thumb.png",
          content: "Header line\nBody line",
          buttonText: "Try now",
          redirectUrl: "https://example.com/redirect",
        },
      ] },
    });
  });

  await page.route(`**/api/v1/operations/${operation.id}/validate`, async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        id: "33333333-3333-3333-3333-333333333333",
        operationId: operation.id,
        sourceRevision: 2,
        status: "VALIDATED",
        findings: [
          { fieldName: "oa[1].oaName", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
          { fieldName: "oa[1].thumbnailUrl", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
          { fieldName: "oa[1].content.header", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
          { fieldName: "oa[1].content.body", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
          { fieldName: "oa[1].buttonText", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
          { fieldName: "oa[1].redirectUrl", validatorType: "required", status: "PASSED", issue: null, location: null, suggestion: null, severity: null, confidence: null },
        ],
        canGenerate: true,
        generateDisabledReasons: [],
      },
    });
  });
}

test("fills OA details, runs AI validation, and reaches an enabled Generate action on verify", async ({
  page,
}) => {
  await mockApi(page);

  await page.goto("/operations/new/input");
  await expect(page.getByRole("heading", { name: "Test Operations" })).toBeVisible();

  await page.getByLabel(/Jira ID/).fill("ZVAS-1424");
  await page.getByLabel(/Thumb URL/).fill("https://example.com/thumb.png");
  await page.getByLabel(/Content/).fill("Header line\nBody line");
  await page.getByLabel(/Button Text/).fill("Try now");
  await page.getByLabel(/URL Redirect/).fill("https://example.com/redirect");

  await expect(page.getByTestId("oa-platform")).toHaveText("Android");

  const submit = page.getByRole("button", { name: /AI Validation/ });
  await expect(submit).toBeEnabled();
  await submit.click();

  await expect(page).toHaveURL(/\/verify$/);
  await expect(page.getByRole("heading", { name: "OA Summary" })).toBeVisible();

  const generate = page.getByRole("button", { name: "Generate" });
  await expect(generate).toBeEnabled();
});
