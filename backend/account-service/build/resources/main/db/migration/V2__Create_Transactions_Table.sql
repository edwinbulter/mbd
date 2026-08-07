CREATE TABLE transactions (
    id SERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    type VARCHAR(20) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE INDEX idx_transactions_account_id ON transactions(account_id);
CREATE INDEX idx_transactions_created_at ON transactions(created_at);
