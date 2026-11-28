USE hoj;

CREATE TABLE IF NOT EXISTS codeforces_sync_scope (
    kind VARCHAR(12) NOT NULL,
    scope_id BIGINT NOT NULL,
    uid VARCHAR(32) NOT NULL,
    account_id BIGINT NULL,
    handle_key VARCHAR(64) NOT NULL,
    PRIMARY KEY (kind,scope_id,uid),
    KEY idx_cf_sync_scope_account (account_id),
    CONSTRAINT fk_cf_sync_scope_account FOREIGN KEY (account_id) REFERENCES external_account(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS codeforces_sync_cursor (
    account_id BIGINT NOT NULL PRIMARY KEY,
    synced_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_cf_sync_cursor_account FOREIGN KEY (account_id) REFERENCES external_account(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS codeforces_sync_projection (
    kind VARCHAR(12) NOT NULL,
    scope_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    external_id BIGINT NOT NULL,
    submit_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (kind,scope_id,slot_id,external_id),
    UNIQUE KEY uk_cf_sync_judge (submit_id),
    CONSTRAINT fk_cf_sync_projection_judge FOREIGN KEY (submit_id) REFERENCES judge(submit_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
