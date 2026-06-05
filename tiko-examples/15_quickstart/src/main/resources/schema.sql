CREATE TABLE IF NOT EXISTS notes (
    id          UUID         PRIMARY KEY,
    text        VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMP    NOT NULL
);
