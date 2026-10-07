CREATE TABLE training_sync_rule_versions (
    version_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    CONSTRAINT training_sync_rule_status CHECK (status IN ('VALID','INVALID'))
);

CREATE TABLE training_sync_policy_versions (
    version_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    CONSTRAINT training_sync_policy_status CHECK (status IN ('VALID','INVALID'))
);

INSERT INTO training_sync_rule_versions(version_id,status)
VALUES ('SIT_AGGREGATION_V1_PROTOTYPE:1','VALID')
ON CONFLICT (version_id) DO NOTHING;

INSERT INTO training_sync_policy_versions(version_id,status)
VALUES ('SIT_POLICY:1','VALID')
ON CONFLICT (version_id) DO NOTHING;

CREATE TABLE training_sync_sessions (
    session_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES accounts(user_id) ON DELETE CASCADE,
    dog_id TEXT NOT NULL,
    rule_version_id TEXT NOT NULL,
    policy_version_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT training_sync_session_status CHECK (status IN ('ACTIVE','COMPLETED','INVALID','REJECTED')),
    CONSTRAINT training_sync_rule_fk FOREIGN KEY (rule_version_id) REFERENCES training_sync_rule_versions(version_id),
    CONSTRAINT training_sync_policy_fk FOREIGN KEY (policy_version_id) REFERENCES training_sync_policy_versions(version_id),
    CONSTRAINT training_sync_session_owner_dog_fk FOREIGN KEY (dog_id,user_id) REFERENCES dogs(dog_id,owner_user_id) ON DELETE CASCADE
);

CREATE INDEX idx_training_sync_sessions_user_status
    ON training_sync_sessions(user_id,status);

CREATE TABLE training_sync_records (
    client_generated_id TEXT NOT NULL,
    user_id TEXT NOT NULL REFERENCES accounts(user_id) ON DELETE CASCADE,
    record_type TEXT NOT NULL,
    record_id TEXT NOT NULL,
    session_id TEXT NOT NULL REFERENCES training_sync_sessions(session_id) ON DELETE CASCADE,
    dog_id TEXT NOT NULL,
    rule_version_id TEXT NOT NULL,
    policy_version_id TEXT NOT NULL,
    canonical_payload JSONB NOT NULL,
    supersedes_record_id TEXT,
    attempt_id TEXT,
    evidence_status TEXT,
    attempt_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    basis_evaluation_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    identity_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id,client_generated_id),
    CONSTRAINT training_sync_record_type CHECK (record_type IN ('SESSION','ATTEMPT','EVIDENCE','EVALUATION','DECISION')),
    CONSTRAINT training_sync_evidence_status CHECK (evidence_status IS NULL OR evidence_status IN ('VALID','INVALID')),
    CONSTRAINT training_sync_rule_fk_record FOREIGN KEY (rule_version_id) REFERENCES training_sync_rule_versions(version_id),
    CONSTRAINT training_sync_policy_fk_record FOREIGN KEY (policy_version_id) REFERENCES training_sync_policy_versions(version_id),
    CONSTRAINT training_sync_record_session_owner_fk FOREIGN KEY (session_id) REFERENCES training_sync_sessions(session_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_training_sync_record_identity
    ON training_sync_records(user_id,record_type,record_id);

CREATE INDEX idx_training_sync_records_session
    ON training_sync_records(user_id,session_id,record_type,created_at);

CREATE INDEX idx_training_sync_records_supersede
    ON training_sync_records(user_id,supersedes_record_id);
