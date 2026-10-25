ALTER TABLE variants ADD transport VARCHAR(16) DEFAULT 'http' NOT NULL;
ALTER TABLE variants ADD command VARCHAR(16) NULL;
ALTER TABLE variants ADD args text NULL;