-- Test-only migration: creates tables owned by other services so that
-- portfolio-service foreign keys can be validated in an isolated
-- Testcontainers database. In the real cluster these tables already
-- exist in the shared PostgreSQL instance because account-service,
-- fund-service and user-service run their own Flyway migrations.
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    keycloak_id VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    role VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_number VARCHAR(20) UNIQUE NOT NULL,
    balance DECIMAL(19, 2) DEFAULT 0.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

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
