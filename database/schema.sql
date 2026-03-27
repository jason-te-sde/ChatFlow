CREATE DATABASE IF NOT EXISTS chatflow;
USE chatflow;

CREATE TABLE IF NOT EXISTS messages (
                                        message_id   VARCHAR(36)  NOT NULL,
    room_id      VARCHAR(10)  NOT NULL,
    user_id      INT          NOT NULL,
    username     VARCHAR(20)  NOT NULL,
    message      VARCHAR(500) NOT NULL,
    message_type ENUM('TEXT','JOIN','LEAVE') NOT NULL DEFAULT 'TEXT',
    server_id    VARCHAR(20)  NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    -- Partition key must be part of PRIMARY KEY
    PRIMARY KEY (message_id, room_id),
    INDEX idx_room_time (room_id, created_at),
    INDEX idx_user_time (user_id, created_at),
    INDEX idx_user_room (user_id, room_id)
    ) ENGINE=InnoDB
    DEFAULT CHARSET=utf8mb4
    ROW_FORMAT=COMPRESSED
    PARTITION BY KEY(room_id) PARTITIONS 20;

CREATE TABLE IF NOT EXISTS message_stats (
                                             stat_time    DATETIME     NOT NULL,
                                             room_id      VARCHAR(10)  NOT NULL,
    message_count INT         NOT NULL DEFAULT 0,
    unique_users  INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (stat_time, room_id),
    INDEX idx_stat_time (stat_time)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;