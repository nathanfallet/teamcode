ALTER TABLE variants ADD transport VARCHAR(16) DEFAULT 'http' NOT NULL, ADD command VARCHAR(16) NULL, ADD args text NULL;
