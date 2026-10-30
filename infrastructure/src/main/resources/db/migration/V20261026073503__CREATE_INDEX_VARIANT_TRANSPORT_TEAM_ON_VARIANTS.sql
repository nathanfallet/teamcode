CREATE INDEX variant_transport_team ON variants (transport, team_id);
ALTER TABLE variants DROP INDEX variant_transport;