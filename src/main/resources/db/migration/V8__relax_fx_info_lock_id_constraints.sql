ALTER TABLE fx_info
    DROP INDEX uk_fx_info_lock_id,
    MODIFY COLUMN lock_id VARCHAR(36) NULL;
