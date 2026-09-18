-- Demo business table. The idempotency_records table is managed by the starter
-- (idempotency.persistence.schema.mode: create).

CREATE TABLE IF NOT EXISTS payments (

    payment_id  VARCHAR(64)    NOT NULL,
    order_id    VARCHAR(64)    NOT NULL,
    recipient   VARCHAR(128)   NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    PRIMARY KEY (payment_id)
);
