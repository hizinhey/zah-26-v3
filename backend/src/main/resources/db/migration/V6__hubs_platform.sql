-- Job dispatch (ExecutionService.offerNextJob -> LeaseService.nextOfferableExecution) has never
-- filtered offerable executions by platform: it always offered the oldest QUEUED/RUNNING
-- execution to whichever Hub asked, regardless of that Hub's platform. That was harmless while
-- only ANDROID existed, but now that WEB official accounts/executions exist, an ANDROID Hub can
-- be offered a WEB job, crash rendering an unknown web-* template id, and (because the journal
-- already recorded the claim) skip that execution permanently after restart.
--
-- Persist each Hub's platform (reported by the Local Hub on every connect/heartbeat - see
-- HubConnectionService) so offerNextJob can filter to only the calling Hub's platform. Existing
-- rows default to ANDROID, matching every Hub that existed before WEB support.
ALTER TABLE hubs
    ADD COLUMN platform VARCHAR(16) NOT NULL DEFAULT 'ANDROID' CHECK (platform IN ('ANDROID', 'WEB'));
