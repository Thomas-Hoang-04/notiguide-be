-- Realistic analytics seed data for 3 stores over the last 30 days (including today).
-- Clears existing analytics_event rows for these stores before inserting.
--
-- Store profiles (from DB):
--   Cà phê Bách Khoa  269de397-...-29dd57a105bd  counter c9a5b454-...
--   Trạm Y tế Bách Khoa  12e4189b-...-5df4cb4d14de  counter 029092de-...
--   Phúc Long  bafca564-...-46debe727aed  counter 8415c1d0-...

DELETE FROM analytics_event
WHERE store_id IN (
    '269de397-63d4-4724-b0f2-29dd57a105bd'::uuid,
    '12e4189b-7ac8-4ea0-8bb4-5df4cb4d14de'::uuid,
    'bafca564-bf44-4717-a886-46debe727aed'::uuid
);

WITH store_profiles AS (
    SELECT *
    FROM (
        VALUES
            -- Cà phê Bách Khoa: busy café, morning + afternoon peaks, fast service
            (
                '269de397-63d4-4724-b0f2-29dd57a105bd'::uuid,
                'c9a5b454-c1ef-4e94-8ca3-ac18f67fd64b'::text,
                52,   -- base_daily_tickets (weekday)
                82,   -- completed_pct
                10,   -- cancelled_pct
                8,    -- skipped_pct
                180,  -- base_wait_sec (~3 min avg)
                240,  -- base_service_sec (~4 min avg)
                9,    -- peak_hour_1 (morning rush)
                14,   -- peak_hour_2 (afternoon)
                7,    -- open_hour
                19    -- close_hour
            ),
            -- Trạm Y tế Bách Khoa: health clinic, slower service, higher cancellation
            (
                '12e4189b-7ac8-4ea0-8bb4-5df4cb4d14de'::uuid,
                '029092de-94d0-4605-a468-a17d561c9363'::text,
                38,
                70,
                18,
                12,
                420,  -- base_wait_sec (~7-min avg, clinics are slower)
                480,  -- base_service_sec (~8 min avg)
                8,    -- peak_hour_1 (early morning)
                13,   -- peak_hour_2 (after lunch)
                7,
                17
            ),
            -- Phúc Long: medium traffic, balanced profile
            (
                'bafca564-bf44-4717-a886-46debe727aed'::uuid,
                '8415c1d0-9f94-4eaf-abca-93dcbd1d4f81'::text,
                44,
                78,
                11,
                11,
                270,  -- base_wait_sec (~4.5 min avg)
                300,  -- base_service_sec (~5 min avg)
                10,   -- peak_hour_1 (mid-morning)
                15,   -- peak_hour_2 (mid-afternoon)
                8,
                20
            )
    ) AS v(
        store_id, counter_id,
        base_daily_tickets, completed_pct, cancelled_pct, skipped_pct,
        base_wait_sec, base_service_sec,
        peak_hour_1, peak_hour_2,
        open_hour, close_hour
    )
),

-- Generate day offsets 0..30 (0 = today, 30 = 30 days ago)
days AS (
    SELECT
        sp.*,
        gs.day_offset,
        ((now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date - gs.day_offset) AS business_day
    FROM store_profiles sp
    CROSS JOIN generate_series(0, 30) AS gs(day_offset)
),

-- Per-day volume with realistic variance:
--   - weekends get 60-75% of weekday traffic
--   - random-ish day-to-day fluctuation via hash
--   - today (day_offset=0) gets partial volume based on current hour
daily_volume AS (
    SELECT
        d.*,
        GREATEST(
            8,
            (
                d.base_daily_tickets
                -- Weekend reduction (Sat/Sun)
                * CASE
                    WHEN EXTRACT(ISODOW FROM d.business_day) = 6 THEN 0.72
                    WHEN EXTRACT(ISODOW FROM d.business_day) = 7 THEN 0.58
                    ELSE 1.0
                  END
                -- Day-to-day variance: ±20% via pseudo-random hash
                * (0.80 + 0.40 * (
                    (hashtext(d.store_id::text || d.day_offset::text) & x'7FFFFFFF'::int)::double precision
                    / x'7FFFFFFF'::int::double precision
                ))
                -- Today: scale by fraction of operating hours elapsed
                * CASE
                    WHEN d.day_offset = 0 THEN
                        GREATEST(0.1,
                            LEAST(1.0,
                                (EXTRACT(HOUR FROM now() AT TIME ZONE 'Asia/Ho_Chi_Minh') - d.open_hour + 1)::double precision
                                / (d.close_hour - d.open_hour)::double precision
                            )
                        )
                    ELSE 1.0
                  END
            )
        )::int AS tickets_for_day
    FROM days d
),

-- Generate individual tickets with realistic hourly distribution
tickets AS (
    SELECT
        dv.*,
        seq.ticket_seq,
        gen_random_uuid() AS ticket_id,
        'A' || lpad(seq.ticket_seq::text, 3, '0') AS ticket_number,

        -- Outcome: deterministic but well-distributed via hash
        CASE
            WHEN abs(hashtext(dv.store_id::text || dv.day_offset::text || seq.ticket_seq::text)) % 100
                < dv.completed_pct THEN 'COMPLETED'
            WHEN abs(hashtext(dv.store_id::text || dv.day_offset::text || seq.ticket_seq::text)) % 100
                < dv.completed_pct + dv.cancelled_pct THEN 'CANCELLED'
            ELSE 'SKIPPED'
        END AS outcome,

        -- Issue hour: weighted bell curve around peak hours
        -- Uses a hash to pick from a weighted distribution
        (CASE
            -- 30% near peak_hour_1 (±1h)
            WHEN abs(hashtext('h' || dv.store_id::text || seq.ticket_seq::text || dv.day_offset::text)) % 100 < 30
                THEN dv.peak_hour_1 + (abs(hashtext('h1' || seq.ticket_seq::text || dv.day_offset::text)) % 3) - 1
            -- 25% near peak_hour_2 (±1h)
            WHEN abs(hashtext('h' || dv.store_id::text || seq.ticket_seq::text || dv.day_offset::text)) % 100 < 55
                THEN dv.peak_hour_2 + (abs(hashtext('h2' || seq.ticket_seq::text || dv.day_offset::text)) % 3) - 1
            -- 20% in mid-morning / early afternoon fill
            WHEN abs(hashtext('h' || dv.store_id::text || seq.ticket_seq::text || dv.day_offset::text)) % 100 < 75
                THEN dv.open_hour + 2 + abs(hashtext('h3' || seq.ticket_seq::text || dv.day_offset::text)) % (dv.peak_hour_2 - dv.open_hour - 1)
            -- 15% in shoulder hours (opening, late afternoon)
            WHEN abs(hashtext('h' || dv.store_id::text || seq.ticket_seq::text || dv.day_offset::text)) % 100 < 90
                THEN dv.close_hour - 2 - abs(hashtext('h4' || seq.ticket_seq::text || dv.day_offset::text)) % 3
            -- 10% in early / late hours
            ELSE dv.open_hour + abs(hashtext('h5' || seq.ticket_seq::text || dv.day_offset::text)) % (dv.close_hour - dv.open_hour)
        END) AS issue_hour_local,

        -- Issue minute: spread across the hour
        abs(hashtext('m' || dv.store_id::text || seq.ticket_seq::text || dv.day_offset::text)) % 60 AS issue_minute_local

    FROM daily_volume dv
    CROSS JOIN LATERAL generate_series(1, dv.tickets_for_day) AS seq(ticket_seq)
),

-- Clamp issue hours to operating range and compute full timeline.
-- For today (day_offset=0), also clamp to 2 hours before current time
-- so the full ticket lifecycle (issue → wait → service) fits in the past.
ticket_timeline AS (
    SELECT
        t.*,
        -- Clamp hour: operating range, plus today gets clamped to current_hour - 2
        LEAST(
            CASE
                WHEN t.day_offset = 0
                    THEN GREATEST(t.open_hour, (EXTRACT(HOUR FROM now() AT TIME ZONE 'Asia/Ho_Chi_Minh'))::int - 2)
                ELSE t.close_hour - 1
            END,
            GREATEST(t.open_hour, t.issue_hour_local)
        ) AS clamped_hour,

        -- Issue timestamp
        ((t.business_day::timestamp
            + make_interval(
                hours => LEAST(
                    CASE
                        WHEN t.day_offset = 0
                            THEN GREATEST(t.open_hour, (EXTRACT(HOUR FROM now() AT TIME ZONE 'Asia/Ho_Chi_Minh'))::int - 2)
                        ELSE t.close_hour - 1
                    END,
                    GREATEST(t.open_hour, t.issue_hour_local)
                ),
                mins => t.issue_minute_local
            )) AT TIME ZONE 'Asia/Ho_Chi_Minh'
        ) AS issued_ts,

        -- Wait time: longer during peak hours, variance via hash
        GREATEST(
            45,
            t.base_wait_sec
                -- Peak hour congestion: +50-80% wait during peaks
                + CASE
                    WHEN LEAST(t.close_hour - 1, GREATEST(t.open_hour, t.issue_hour_local))
                        BETWEEN t.peak_hour_1 - 1 AND t.peak_hour_1 + 1 THEN (t.base_wait_sec * 0.6)::int
                    WHEN LEAST(t.close_hour - 1, GREATEST(t.open_hour, t.issue_hour_local))
                        BETWEEN t.peak_hour_2 - 1 AND t.peak_hour_2 + 1 THEN (t.base_wait_sec * 0.75)::int
                    ELSE 0
                  END
                -- Weekend: shorter waits (less traffic)
                + CASE
                    WHEN EXTRACT(ISODOW FROM t.business_day) IN (6, 7) THEN -(t.base_wait_sec * 0.25)::int
                    ELSE 0
                  END
                -- Per-ticket variance: ±30%
                + ((abs(hashtext('w' || t.store_id::text || t.ticket_seq::text || t.day_offset::text)) % (t.base_wait_sec * 6 / 10))
                    - t.base_wait_sec * 3 / 10)
        )::int AS wait_seconds,

        -- Service time: some variance
        GREATEST(
            60,
            t.base_service_sec
                + ((abs(hashtext('s' || t.store_id::text || t.ticket_seq::text || t.day_offset::text)) % (t.base_service_sec / 2))
                    - t.base_service_sec / 4)
        )::int AS service_seconds,

        -- Some canceled tickets were called first (canceled after being called)
        (t.outcome = 'CANCELLED'
            AND abs(hashtext('c' || t.store_id::text || t.ticket_seq::text || t.day_offset::text)) % 3 = 0
        ) AS cancelled_after_call
    FROM tickets t
),

-- Compute each ticket's final (latest) event timestamp so we can
-- filter out entire tickets that haven't fully resolved yet.
resolved_tickets AS (
    SELECT
        tt.*,
        -- The latest event time for this ticket (the terminal event)
        CASE outcome
            WHEN 'COMPLETED' THEN issued_ts + make_interval(secs => wait_seconds + service_seconds)
            WHEN 'CANCELLED' THEN issued_ts + make_interval(
                secs => CASE
                    WHEN cancelled_after_call THEN wait_seconds + GREATEST(30, service_seconds / 4)
                    ELSE GREATEST(45, wait_seconds / 3)
                END
            )
            WHEN 'SKIPPED' THEN issued_ts + make_interval(secs => wait_seconds + GREATEST(45, service_seconds / 5))
        END AS final_event_ts
    FROM ticket_timeline tt
),

-- Only keep tickets whose entire lifecycle is in the past
past_tickets AS (
    SELECT * FROM resolved_tickets
    WHERE final_event_ts <= now()
),

-- Flatten into individual analytics events
event_rows AS (
    -- TICKET_ISSUED: every ticket
    SELECT
        issued_ts AS time,
        store_id,
        'TICKET_ISSUED'::analytics_event_type AS event_type,
        ticket_id,
        NULL::int AS wait_duration_seconds,
        NULL::uuid AS device_id,
        jsonb_build_object('ticket_number', ticket_number) AS metadata
    FROM past_tickets

    UNION ALL

    -- TICKET_CALLED: completed + skipped + canceled-after-call
    SELECT
        issued_ts + make_interval(secs => wait_seconds),
        store_id,
        'TICKET_CALLED'::analytics_event_type,
        ticket_id,
        wait_seconds,
        NULL::uuid,
        jsonb_build_object(
            'ticket_number', ticket_number,
            'counter_id', counter_id
        )
    FROM past_tickets
    WHERE outcome IN ('COMPLETED', 'SKIPPED')
       OR (outcome = 'CANCELLED' AND cancelled_after_call)

    UNION ALL

    -- TICKET_COMPLETED
    SELECT
        issued_ts + make_interval(secs => wait_seconds + service_seconds),
        store_id,
        'TICKET_COMPLETED'::analytics_event_type,
        ticket_id,
        service_seconds,
        NULL::uuid,
        jsonb_build_object('ticket_number', ticket_number)
    FROM past_tickets
    WHERE outcome = 'COMPLETED'

    UNION ALL

    -- TICKET_CANCELLED
    SELECT
        issued_ts + make_interval(
            secs => CASE
                WHEN cancelled_after_call
                    THEN wait_seconds + GREATEST(30, service_seconds / 4)
                ELSE GREATEST(45, wait_seconds / 3)
            END
        ),
        store_id,
        'TICKET_CANCELLED'::analytics_event_type,
        ticket_id,
        CASE
            WHEN cancelled_after_call
                THEN wait_seconds + GREATEST(30, service_seconds / 4)
            ELSE GREATEST(45, wait_seconds / 3)
        END,
        NULL::uuid,
        jsonb_build_object(
            'ticket_number', ticket_number,
            'previous_status', CASE WHEN cancelled_after_call THEN 'CALLED' ELSE 'WAITING' END
        )
    FROM past_tickets
    WHERE outcome = 'CANCELLED'

    UNION ALL

    -- TICKET_SKIPPED (no-show after being called)
    SELECT
        issued_ts + make_interval(secs => wait_seconds + GREATEST(45, service_seconds / 5)),
        store_id,
        'TICKET_SKIPPED'::analytics_event_type,
        ticket_id,
        wait_seconds + GREATEST(45, service_seconds / 5),
        NULL::uuid,
        jsonb_build_object('ticket_number', ticket_number)
    FROM past_tickets
    WHERE outcome = 'SKIPPED'
)

INSERT INTO analytics_event (
    time, store_id, event_type, ticket_id,
    wait_duration_seconds, device_id, metadata
)
SELECT
    time, store_id, event_type, ticket_id,
    wait_duration_seconds, device_id, metadata
FROM event_rows
ORDER BY store_id, time;
