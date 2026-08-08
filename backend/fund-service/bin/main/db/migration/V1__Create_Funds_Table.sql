CREATE TABLE funds (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    isin VARCHAR(12) UNIQUE NOT NULL,
    current_price DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'EUR',
    volatility DOUBLE PRECISION DEFAULT 0.02,
    update_frequency_minutes INT DEFAULT 5,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_funds_isin ON funds(isin);
CREATE INDEX idx_funds_name ON funds(name);
