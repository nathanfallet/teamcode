ALTER TABLE cloud_settings ADD member_transfers VARCHAR(16) DEFAULT 'offer' NOT NULL;
ALTER TABLE cloud_settings ADD transfer_offer_days INT DEFAULT 7 NOT NULL;
ALTER TABLE cloud_settings ADD transfer_pending_inputs VARCHAR(16) DEFAULT 'cancel' NOT NULL;