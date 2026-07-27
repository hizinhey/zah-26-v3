import { test, expect, type Page } from "@playwright/test";

/**
 * End-to-end flow across Screen 3 (Generate Test Cases) and Screen 4
 * (Confirm & Start Execution). The backend is not available in this
 * environment, so REST calls are stubbed at the network layer with shapes
 * derived from com.opshub.generation.api.TestPlanController and
 * com.opshub.execution.api.ExecutionController. There is no browser-facing
 * WebSocket/SSE endpoint in this codebase yet (see frontend/src/realtime/
 * useExecutionChannel.ts for the documented gap), so this test does not
 * attempt to simulate live envelopes - it exercises routing, the generate/
 * approve/start mutations, and the REST-derived rendering of both screens.
 */
const operationId = "11111111-1111-1111-1111-111111111111";
const planId = "44444444-4444-4444-4444-444444444444";

function templateParameters(oaOrder: number) {
  return {
    oaName: `OA #${oaOrder}`,
    thumbnailUrl: "https://example.com/thumb.png",
    expectedHeader: "Header",
    expectedBody: "Body",
    expectedButtonText: "Try now",
    expectedRedirectUrl: "https://example.com/redirect",
    expectedRedirectDomain: "example.com",
  };
}

function fiveCasesFor(oaOrder: number) {
  const templates = [
    "android-oa-delivery-v1",
    "android-thumbnail-v1",
    "android-content-v1",
    "android-button-text-v1",
    "android-redirect-v1",
  ];
  return templates.map((templateId, index) => ({
    testCaseId: `${oaOrder}-${index + 1}`,
    planId,
    oaOrder,
    order: index + 1,
    templateId,
    templateVersion: 1,
    templateSha256: "sha",
    parameters: templateParameters(oaOrder),
    status: "READY",
    reason: null,
  }));
}

async function mockApi(page: Page): Promise<void> {
  await page.route(`**/api/v1/operations/${operationId}`, async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        id: operationId,
        jiraId: "ZVAS-1424",
        revision: 3,
        status: "READY_FOR_APPROVAL",
        createdAt: "2026-07-27T00:00:00Z",
        updatedAt: "2026-07-27T00:00:00Z",
        oas: [],
      },
    });
  });

  await page.route(`**/api/v1/operations/${operationId}/plans`, async (route) => {
    await route.fulfill({
      status: 200,
      json: {
        planId,
        operationId,
        sourceRevision: 3,
        templateCatalogVersion: "v1",
        status: "READY",
        approvalStatus: "PENDING",
        testCases: [...fiveCasesFor(1), ...fiveCasesFor(2)],
      },
    });
  });

  await page.route(`**/api/v1/plans/${planId}/approve`, async (route) => {
    await route.fulfill({ status: 204 });
  });

  await page.route(`**/api/v1/operations/${operationId}/executions`, async (route) => {
    await route.fulfill({
      status: 201,
      json: {
        id: "55555555-5555-5555-5555-555555555555",
        operationId,
        planId,
        sourceRevision: 3,
        status: "QUEUED",
      },
    });
  });
}

test("generates a five-case-per-OA plan, confirms it, and starts execution", async ({ page }) => {
  await mockApi(page);

  await page.goto(`/operations/${operationId}/generate`);

  await expect(page.getByRole("heading", { name: "OA #1" })).toBeVisible();
  const oa1Table = page.locator("table").first();
  await expect(oa1Table.locator("tbody tr")).toHaveCount(5);

  const confirm = page.getByRole("button", { name: "Confirm" });
  await expect(confirm).toBeEnabled();
  await confirm.click();

  await expect(page).toHaveURL(/\/execute$/);
  await expect(page.getByRole("list", { name: "Execution Queue (by OA Sequence)" })).toBeVisible();
  await expect(page.getByText("OA #1")).toBeVisible();
  await expect(page.getByText("OA #2")).toBeVisible();

  const start = page.getByRole("button", { name: "Start Execution" });
  await expect(start).toBeEnabled();
  await start.click();

  await expect(page.getByText(/Progress: 0 \/ 10 test cases/)).toBeVisible();
});
