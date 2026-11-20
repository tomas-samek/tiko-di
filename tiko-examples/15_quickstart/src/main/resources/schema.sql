CREATE TABLE IF NOT EXISTS notes (
    id          UUID         PRIMARY KEY,
    text        VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS note_tags (
    note_id     UUID         NOT NULL REFERENCES notes (id),
    tag         VARCHAR(40)  NOT NULL,
    PRIMARY KEY (note_id, tag)
);
