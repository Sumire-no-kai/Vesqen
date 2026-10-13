-- #120: activation codes for the GitHub build (docs/ACTIVATION_CODES.md). The owner signs codes
-- offline and uploads them; the Worker never holds the signing key. An order is a Stripe Checkout
-- Session id; nothing here names or locates a buyer (no email, name or IP). Rows are kept as the
-- license record: a lost code is found again by its order id, and refunds revoke by payment.
CREATE TABLE activation_codes (
    key_id INTEGER NOT NULL CHECK(key_id BETWEEN 1 AND 255),
    serial INTEGER NOT NULL CHECK(serial >= 1),
    code TEXT NOT NULL UNIQUE CHECK(length(code) = 117),
    order_id TEXT UNIQUE,
    payment_intent TEXT,
    assigned_at INTEGER,
    revoked_at INTEGER,
    revoke_reason TEXT CHECK(revoke_reason IN ('refund', 'dispute', 'leak')),
    PRIMARY KEY(key_id, serial),
    CHECK((order_id IS NULL) = (assigned_at IS NULL)),
    CHECK((revoked_at IS NULL) = (revoke_reason IS NULL))
) STRICT;
-- The next free code is always the lowest key id and serial still unassigned and not revoked.
CREATE INDEX activation_codes_free ON activation_codes(key_id, serial) WHERE order_id IS NULL AND revoked_at IS NULL;
CREATE INDEX activation_codes_payment ON activation_codes(payment_intent) WHERE payment_intent IS NOT NULL;
