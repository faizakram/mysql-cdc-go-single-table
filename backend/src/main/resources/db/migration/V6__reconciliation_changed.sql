-- Content-level checksum validation (issue #48): sampled rows present in both sides but with
-- differing column values.
ALTER TABLE reconciliation_result ADD COLUMN changed BIGINT;
