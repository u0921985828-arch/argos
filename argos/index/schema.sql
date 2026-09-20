-- ARGOS metadata index.
--
-- Design principle: the video is not the database. Every question an operator
-- asks -- "show me red vans that stopped near the loading bay after 22:00" --
-- is answered entirely from this schema, and pixels are fetched only to render
-- the answer. That is what turns a 30-day archive into something queryable in
-- milliseconds instead of a rendering job.

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- --------------------------------------------------------------------------
-- Cameras and calibration
-- --------------------------------------------------------------------------

CREATE TABLE camera (
    camera_id       text PRIMARY KEY,
    label           text NOT NULL,
    site_id         text NOT NULL,
    fps             real NOT NULL DEFAULT 25,
    width           int  NOT NULL,
    height          int  NOT NULL,
    -- Homography image->ground plane. Present means trajectories, speeds and
    -- zones are expressed in metres rather than pixels, which is what makes
    -- rules portable between cameras and speed evidence defensible.
    homography      double precision[9],
    ground_srid     int,
    installed_at    timestamptz NOT NULL DEFAULT now(),
    -- Retention is a property of the camera, not a global setting: a car park
    -- and a staff corridor rarely share a lawful retention period.
    retention_days  int NOT NULL DEFAULT 30,
    lawful_basis    text NOT NULL,
    dpia_ref        text
);

-- --------------------------------------------------------------------------
-- Tubes: the atom of the system
-- --------------------------------------------------------------------------

CREATE TABLE tube (
    tube_id         bigserial PRIMARY KEY,
    camera_id       text NOT NULL REFERENCES camera(camera_id) ON DELETE CASCADE,
    class_name      text NOT NULL,
    t_start         timestamptz NOT NULL,
    t_end           timestamptz NOT NULL,
    frame_start     bigint NOT NULL,
    frame_end       bigint NOT NULL,
    n_obs           int NOT NULL,
    score           real NOT NULL,

    -- Geometry. `path_img` is the pixel-space centroid track; `path_world` is
    -- the same track on the ground plane when the camera is calibrated.
    bbox_union      box NOT NULL,
    path_img        geometry(LineString, 0),
    path_world      geometry(LineString, 4326),

    -- Denormalised attributes: cheap filters that avoid touching vectors.
    dominant_colour text,
    colour_lab      real[3],
    direction_deg   real,
    speed_mps       real,
    dwell_s         real,
    is_stationary   boolean NOT NULL DEFAULT false,

    -- Appearance vector for re-identification across time and cameras.
    reid            vector(512),
    -- Semantic vector for natural-language search ("man with a red backpack").
    clip            vector(768),

    attrs           jsonb NOT NULL DEFAULT '{}'::jsonb,
    patch_ref       text,          -- object-store key for the patch bundle
    created_at      timestamptz NOT NULL DEFAULT now(),
    redacted_at     timestamptz,
    CONSTRAINT tube_time_ok CHECK (t_end >= t_start)
);

-- Time is the first filter in essentially every query, so it leads every index.
CREATE INDEX tube_cam_time     ON tube (camera_id, t_start DESC);
CREATE INDEX tube_class_time   ON tube (class_name, t_start DESC);
CREATE INDEX tube_time_range   ON tube USING gist (tstzrange(t_start, t_end));
CREATE INDEX tube_path_img     ON tube USING gist (path_img);
CREATE INDEX tube_path_world   ON tube USING gist (path_world);
CREATE INDEX tube_attrs        ON tube USING gin (attrs jsonb_path_ops);
CREATE INDEX tube_colour       ON tube (dominant_colour) WHERE dominant_colour IS NOT NULL;

-- HNSW over cosine distance: sub-second nearest-neighbour re-ID over tens of
-- millions of tubes. `m`/`ef_construction` traded toward recall because a
-- missed match in an investigation is far more costly than a slow query.
CREATE INDEX tube_reid_hnsw ON tube USING hnsw (reid vector_cosine_ops)
    WITH (m = 32, ef_construction = 128);
CREATE INDEX tube_clip_hnsw ON tube USING hnsw (clip vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- --------------------------------------------------------------------------
-- Per-frame observations. Partitioned by day so that expiring a retention
-- window is a DETACH, not a mass DELETE with the vacuum storm that follows.
-- --------------------------------------------------------------------------

CREATE TABLE observation (
    tube_id     bigint NOT NULL,
    ts          timestamptz NOT NULL,
    frame_idx   bigint NOT NULL,
    x1 real NOT NULL, y1 real NOT NULL, x2 real NOT NULL, y2 real NOT NULL,
    score       real NOT NULL,
    mask        bytea,             -- zlib-packed silhouette bitfield
    PRIMARY KEY (tube_id, frame_idx)
) PARTITION BY RANGE (ts);

-- --------------------------------------------------------------------------
-- Identities: an operator-asserted grouping of tubes. Deliberately separate
-- from `tube`, and deliberately NOT automatic.
-- --------------------------------------------------------------------------

CREATE TABLE identity (
    identity_id     bigserial PRIMARY KEY,
    label           text,
    kind            text NOT NULL CHECK (kind IN ('appearance_cluster', 'operator_asserted')),
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    justification   text NOT NULL,   -- required: no unexplained identity links
    expires_at      timestamptz
);

CREATE TABLE identity_member (
    identity_id bigint NOT NULL REFERENCES identity(identity_id) ON DELETE CASCADE,
    tube_id     bigint NOT NULL REFERENCES tube(tube_id) ON DELETE CASCADE,
    confidence  real NOT NULL,
    confirmed_by text,
    PRIMARY KEY (identity_id, tube_id)
);

-- --------------------------------------------------------------------------
-- Zones and rules
-- --------------------------------------------------------------------------

CREATE TABLE zone (
    zone_id     bigserial PRIMARY KEY,
    camera_id   text NOT NULL REFERENCES camera(camera_id) ON DELETE CASCADE,
    name        text NOT NULL,
    kind        text NOT NULL CHECK (kind IN ('area', 'line', 'exclusion')),
    poly_img    geometry(Polygon, 0),
    line_img    geometry(LineString, 0),
    poly_world  geometry(Polygon, 4326)
);
CREATE INDEX zone_poly_img ON zone USING gist (poly_img);

CREATE TABLE rule (
    rule_id     bigserial PRIMARY KEY,
    camera_id   text REFERENCES camera(camera_id) ON DELETE CASCADE,
    name        text NOT NULL,
    kind        text NOT NULL,     -- intrusion | crossing | loitering | speed | count | absence
    params      jsonb NOT NULL,
    schedule    jsonb,             -- active windows; most false alarms are "wrong hour"
    enabled     boolean NOT NULL DEFAULT true
);

CREATE TABLE event (
    event_id    bigserial PRIMARY KEY,
    rule_id     bigint REFERENCES rule(rule_id) ON DELETE SET NULL,
    tube_id     bigint REFERENCES tube(tube_id) ON DELETE CASCADE,
    camera_id   text NOT NULL,
    ts          timestamptz NOT NULL,
    severity    smallint NOT NULL DEFAULT 1,
    payload     jsonb NOT NULL DEFAULT '{}'::jsonb,
    ack_by      text,
    ack_at      timestamptz,
    -- Operator feedback is stored, not discarded: it is the training signal for
    -- rule tuning and the only honest measure of the false-alarm rate.
    verdict     text CHECK (verdict IN ('true', 'false', 'unclear'))
);
CREATE INDEX event_cam_ts ON event (camera_id, ts DESC);

-- --------------------------------------------------------------------------
-- Governance
-- --------------------------------------------------------------------------

-- Every read of personal data is logged. Under GDPR Art. 5(2) the controller
-- must be able to *demonstrate* compliance, and "who looked at whom, when, and
-- under what authority" is the part auditors always ask for first.
CREATE TABLE access_log (
    log_id      bigserial PRIMARY KEY,
    ts          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    action      text NOT NULL,     -- search | render | export | redact | identity_link
    query       jsonb NOT NULL,
    n_results   int,
    case_ref    text,              -- mandatory for export actions
    ip          inet
);
CREATE INDEX access_log_actor_ts ON access_log (actor, ts DESC);

-- Erasure requests: satisfied by dropping the tube's patches and vectors while
-- keeping a tombstone, so a later audit can show the request was honoured.
CREATE TABLE erasure (
    erasure_id  bigserial PRIMARY KEY,
    tube_id     bigint NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    executed_at timestamptz,
    basis       text NOT NULL,
    actor       text NOT NULL
);

-- Retention enforcement. Runs as a scheduled job; per-camera, not global.
CREATE OR REPLACE FUNCTION enforce_retention() RETURNS int AS $$
DECLARE
    n int := 0;
BEGIN
    WITH doomed AS (
        DELETE FROM tube t
        USING camera c
        WHERE t.camera_id = c.camera_id
          AND t.t_end < now() - (c.retention_days || ' days')::interval
        RETURNING t.tube_id
    )
    SELECT count(*) INTO n FROM doomed;
    RETURN n;
END;
$$ LANGUAGE plpgsql;
