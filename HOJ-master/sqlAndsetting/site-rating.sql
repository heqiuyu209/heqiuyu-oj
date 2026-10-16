-- Apply once to an existing database, before deploying the new backend.
-- Coarse difficulty remains for legacy compatibility; it is never converted to rating.
ALTER TABLE problem ADD COLUMN rating INT NULL COMMENT 'Station task rating, manually calibrated';
ALTER TABLE contest ADD COLUMN rated TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Updates station contest rating';
CREATE TABLE IF NOT EXISTS site_rating_lock (
    id INT PRIMARY KEY, updated_at DATETIME(3) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO site_rating_lock(id) VALUES (1);
CREATE TABLE IF NOT EXISTS site_rating_profile (
    uid VARCHAR(32) NOT NULL, type VARCHAR(12) NOT NULL,
    rating INT NOT NULL, peak INT NOT NULL, delta INT NOT NULL, rated_count INT NOT NULL,
    PRIMARY KEY(uid,type), KEY idx_site_rating_rank(type,rating)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS site_rating_history (
    uid VARCHAR(32) NOT NULL, type VARCHAR(12) NOT NULL, source_key VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL, occurred_at DATETIME(3) NOT NULL,
    old_rating INT NOT NULL, rating INT NOT NULL, delta INT NOT NULL, place DOUBLE NULL,
    PRIMARY KEY(uid,type,source_key), KEY idx_site_rating_history(uid,type,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
