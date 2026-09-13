--liquibase formatted sql

--changeset evals:0006-price-book-digest
-- The full sha256 of the file a price book was imported from. price_book.version keeps only its first
-- 12 hex characters (litellm-<12 hex>); home.tessary.ai names a published book by the whole digest, and
-- the heartbeat reports the digest this install holds, so the instance has to be able to answer "is this
-- the book I already have" in home's terms.
--
-- Nullable: books imported before this changeset carry none until PriceBookImporter's next boot fills
-- the bundled book's digest from the jar. A fetched book always carries one.
ALTER TABLE price_book ADD COLUMN digest text;

CREATE UNIQUE INDEX ux_price_book_digest ON price_book (digest) WHERE digest IS NOT NULL;

--rollback DROP INDEX ux_price_book_digest; ALTER TABLE price_book DROP COLUMN digest;
