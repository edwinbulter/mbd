-- Insert initial funds for demo purposes
INSERT INTO funds (name, isin, current_price, currency, volatility, update_frequency_minutes, created_at, updated_at)
VALUES
    ('Fund 1', 'FU01', 100.00, 'EUR', 0.01, 5, NOW(), NOW()),
    ('Fund 2', 'FU02', 100.00, 'EUR', 0.02, 5, NOW(), NOW())
ON CONFLICT (isin) DO NOTHING;
