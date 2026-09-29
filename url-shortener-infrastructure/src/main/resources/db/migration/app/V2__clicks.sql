-- Application plane: redirect observations. Deliberately free of PII (COMP-002).
CREATE TABLE clicks (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(16) NOT NULL,
    occurred_at   TIMESTAMP(9) WITH TIME ZONE NOT NULL,
    referer_host  VARCHAR(255) NULL,
    ua_class      VARCHAR(16)  NULL,
    CONSTRAINT fk_clicks_links FOREIGN KEY (code) REFERENCES links (code) ON DELETE CASCADE
);

CREATE INDEX idx_clicks_code_time ON clicks (code, occurred_at);
