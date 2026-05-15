CREATE TABLE IF NOT EXISTS orders (
    id          UUID        PRIMARY KEY,
    customer    VARCHAR(255) NOT NULL,
    status      VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS order_items (
    order_id    UUID        NOT NULL,
    line_no     INT         NOT NULL,
    sku         VARCHAR(64) NOT NULL,
    qty         INT         NOT NULL,
    PRIMARY KEY (order_id, line_no),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
