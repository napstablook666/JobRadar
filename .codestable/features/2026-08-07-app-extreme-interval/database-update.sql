BEGIN;

UPDATE liepin_config
SET min_delay_seconds = 30,
    max_delay_seconds = 60,
    search_min_delay_seconds = 10,
    search_max_delay_seconds = 20,
    page_min_delay_seconds = 5,
    page_max_delay_seconds = 10,
    detail_min_delay_seconds = 8,
    detail_max_delay_seconds = 15,
    rate_guard_batch_size = 5,
    batch_cooldown_min_seconds = 300,
    batch_cooldown_max_seconds = 450
WHERE id = 1;

COMMIT;

SELECT id,
       min_delay_seconds,
       max_delay_seconds,
       search_min_delay_seconds,
       search_max_delay_seconds,
       page_min_delay_seconds,
       page_max_delay_seconds,
       detail_min_delay_seconds,
       detail_max_delay_seconds,
       rate_guard_batch_size,
       batch_cooldown_min_seconds,
       batch_cooldown_max_seconds
FROM liepin_config
WHERE id = 1;
