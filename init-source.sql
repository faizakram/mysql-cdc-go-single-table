-- Create the channel_transactions table with sample data
CREATE TABLE IF NOT EXISTS channel_transactions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    transaction_id VARCHAR(100) NOT NULL,
    channel_name VARCHAR(50) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'USD',
    status VARCHAR(20) DEFAULT 'pending',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_transaction_id (transaction_id),
    INDEX idx_channel_name (channel_name),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Grant necessary privileges
GRANT ALL PRIVILEGES ON offercraft.* TO 'srcuser'@'%';
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'srcuser'@'%';
FLUSH PRIVILEGES;

-- Insert some sample data
INSERT INTO channel_transactions (transaction_id, channel_name, amount, currency, status) VALUES
    ('TXN-001', 'web', 150.00, 'USD', 'completed'),
    ('TXN-002', 'mobile', 75.50, 'USD', 'pending'),
    ('TXN-003', 'api', 200.00, 'EUR', 'completed'),
    ('TXN-004', 'web', 99.99, 'USD', 'failed'),
    ('TXN-005', 'mobile', 125.75, 'GBP', 'completed');
