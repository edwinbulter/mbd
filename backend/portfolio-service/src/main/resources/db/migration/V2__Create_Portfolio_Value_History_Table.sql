CREATE TABLE portfolio_value_history (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    total_value DECIMAL(19, 2) NOT NULL,
    timestamp TIMESTAMP NOT NULL
);

CREATE INDEX idx_portfolio_value_history_account ON portfolio_value_history(account_id);
CREATE INDEX idx_portfolio_value_history_timestamp ON portfolio_value_history(timestamp);
