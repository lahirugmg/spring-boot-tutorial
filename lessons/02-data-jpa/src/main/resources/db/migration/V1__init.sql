-- INTERVIEW: "Why Flyway instead of hibernate.ddl-auto=update?"
--
--   ddl-auto=update never DROPs, never renames, cannot express a data migration, behaves
--   differently across Hibernate versions, and gives you no record of what ran. It is fine
--   for a throwaway demo and disqualifying in production.
--
--   Flyway applies ordered, immutable, checksummed migrations and records them in
--   flyway_schema_history. Every environment converges on the same schema, and a changed
--   checksum on an already-applied file fails the startup loudly.
--
-- In this module ddl-auto is set to `validate`: Hibernate verifies that the entity
-- mappings match the Flyway-created schema and refuses to start if they drifted. That
-- combination — Flyway owns the schema, Hibernate validates it — is the answer you want.

-- allocationSize=50 in the @SequenceGenerator MUST equal INCREMENT BY here.
-- Mismatch = duplicate key violations under concurrency.
CREATE SEQUENCE author_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE book_seq   START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE audit_seq  START WITH 1 INCREMENT BY 50;

CREATE TABLE author (
    id         BIGINT       PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    country    VARCHAR(100),
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE book (
    id           BIGINT         PRIMARY KEY,
    title        VARCHAR(300)   NOT NULL,
    isbn         VARCHAR(20)    NOT NULL,
    price        NUMERIC(10, 2) NOT NULL,
    published_on DATE,
    stock        INTEGER        NOT NULL DEFAULT 0,
    author_id    BIGINT         NOT NULL,
    version      SMALLINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_book_isbn   UNIQUE (isbn),
    CONSTRAINT fk_book_author FOREIGN KEY (author_id) REFERENCES author (id)
);

-- Postgres does NOT auto-index foreign keys (unlike MySQL/InnoDB). Without this index
-- every "books of this author" lookup and every DELETE on author does a sequential scan.
-- A reliably good thing to point out in a schema-review question.
CREATE INDEX idx_book_author_id ON book (author_id);
CREATE INDEX idx_book_published_on ON book (published_on);

CREATE TABLE audit_event (
    id          BIGINT       PRIMARY KEY,
    action      VARCHAR(60)  NOT NULL,
    detail      VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMP    NOT NULL DEFAULT now()
);
