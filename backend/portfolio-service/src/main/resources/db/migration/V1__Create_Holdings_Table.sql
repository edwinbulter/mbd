CREATE TABLE holdings (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    fund_id BIGINT NOT NULL,
    quantity DECIMAL(19, 4) NOT NULL,
    average_price DECIMAL(19, 2) NOT NULL,
    current_value DECIMAL(19, 2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    FOREIGN KEY (fund_id) REFERENCES funds(id) ON DELETE CASCADE,
    UNIQUE(account_id, fund_id)
);

CREATE INDEX idx_holdings_account_id ON holdings(account_id);
CREATE INDEX idx_holdings_fund_id ON holdings(fund_id);
