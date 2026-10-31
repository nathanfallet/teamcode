ALTER TABLE automations ADD workflow_id VARCHAR(36) NULL;
ALTER TABLE automations ADD workflow_inputs text NULL;
CREATE INDEX automation_workflow ON automations (workflow_id, team_id);