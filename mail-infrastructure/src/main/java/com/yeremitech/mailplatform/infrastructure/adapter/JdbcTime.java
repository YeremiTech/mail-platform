package com.yeremitech.mailplatform.infrastructure.adapter;

import java.sql.Timestamp;
import java.time.Instant;

final class JdbcTime {
    private JdbcTime() {}

    static Timestamp value(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
