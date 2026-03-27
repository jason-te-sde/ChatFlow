# Database — ChatFlow Assignment 3

## Database Choice: MySQL 8.0

### Justification

MySQL was chosen over alternatives for the following reasons:

| Criterion | MySQL | DynamoDB | Cassandra |
|---|---|---|---|
| Cost | Free on EC2 | Pay per request | Free on EC2 |
| SQL support | Full | Limited | CQL only |
| Joins / analytics | Native | Manual | Limited |
| Write throughput | High with batching | Very high | Very high |
| Operational complexity | Low | Low | High |

The assignment requires complex analytics queries (top users, top rooms, time-range aggregations) that map naturally to SQL. MySQL with batch inserts and proper indexing achieves sufficient write throughput for this workload.

---

## Schema Design

### messages table

The primary storage table for all chat messages.

```sql
CREATE TABLE messages (
    message_id   VARCHAR(36)  NOT NULL,   -- UUID, idempotent key
    room_id      VARCHAR(10)  NOT NULL,
    user_id      INT          NOT NULL,
    username     VARCHAR(20)  NOT NULL,
    message      VARCHAR(500) NOT NULL,
    message_type ENUM('TEXT','JOIN','LEAVE') NOT NULL DEFAULT 'TEXT',
    server_id    VARCHAR(20)  NOT NULL,
    created_at   DATETIME(3)  NOT NULL,   -- millisecond precision
    PRIMARY KEY (message_id),
    INDEX idx_room_time (room_id, created_at),
    INDEX idx_user_time (user_id, created_at),
    INDEX idx_user_room (user_id, room_id)
);
```

### message_stats table

Pre-aggregated per-minute statistics for fast analytics queries.

```sql
CREATE TABLE message_stats (
    stat_time     DATETIME    NOT NULL,
    room_id       VARCHAR(10) NOT NULL,
    message_count INT         NOT NULL DEFAULT 0,
    unique_users  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (stat_time, room_id),
    INDEX idx_stat_time (stat_time)
);
```

---

## Indexing Strategy

| Index | Columns | Supports | Selectivity |
|---|---|---|---|
| PRIMARY | message_id | Idempotent upsert, deduplication | Very high (UUID) |
| idx_room_time | room_id, created_at | Query 1: room messages in time range | High |
| idx_user_time | user_id, created_at | Query 2: user message history | High |
| idx_user_room | user_id, room_id | Query 4: rooms user participated in | Medium |

Composite indexes are ordered by equality column first (room_id / user_id), then range column (created_at), following MySQL's leftmost prefix rule.

Write performance impact is minimal because inserts are batched (500 messages per batch), amortizing index update overhead across many rows per transaction.

---

## Setup Instructions

```bash
# Install MySQL
sudo apt-get install -y mysql-server
sudo systemctl start mysql

# Create database and user
sudo mysql -e "
CREATE DATABASE chatflow;
CREATE USER 'chatflow'@'%' IDENTIFIED BY 'chatflow123';
GRANT ALL ON chatflow.* TO 'chatflow'@'%';
FLUSH PRIVILEGES;"

# Allow remote connections
sudo sed -i 's/bind-address.*=.*/bind-address = 0.0.0.0/' \
  /etc/mysql/mysql.conf.d/mysqld.cnf
sudo systemctl restart mysql

# Run schema
mysql -u chatflow -pchatflow123 chatflow < schema.sql
```

---

## Scaling Considerations

- **Horizontal sharding**: partition by `room_id % N` across multiple MySQL instances if write volume exceeds single-node capacity
- **Read replicas**: add MySQL read replica for analytics queries to offload from write path
- **Archiving**: move messages older than 30 days to cold storage (S3) to keep table size manageable
- **InnoDB buffer pool**: increase `innodb_buffer_pool_size` to 70% of available RAM on dedicated DB instance

## Backup and Recovery

```bash
# Daily logical backup
mysqldump -u chatflow -pchatflow123 chatflow messages \
  | gzip > backup_$(date +%Y%m%d).sql.gz

# Point-in-time recovery via binary log
# Enable in /etc/mysql/mysql.conf.d/mysqld.cnf:
# log_bin = /var/log/mysql/mysql-bin.log
# expire_logs_days = 7
```