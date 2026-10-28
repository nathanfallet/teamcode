ALTER TABLE cloud_settings ADD max_automations_per_member INT DEFAULT 20 NOT NULL;
ALTER TABLE cloud_settings ADD max_running_automations_per_member INT DEFAULT 2 NOT NULL;