from __future__ import annotations

import csv
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CSV_GLOB = "*20250415.csv"
OUTPUT_PATH = ROOT / "src" / "main" / "resources" / "db" / "migration" / "V11__replace_store_with_gumi_restaurants_csv.sql"
BATCH_SIZE = 500


def resolve_csv_path() -> Path:
    matches = sorted(ROOT.glob(CSV_GLOB))
    if not matches:
        raise FileNotFoundError(f"No CSV matched {CSV_GLOB} under {ROOT}")
    return matches[0]


def clean(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = value.strip()
    return normalized or None


def sql_string(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def derive_district(road_address: str | None, jibun_address: str | None) -> str | None:
    for source in (jibun_address, road_address):
        if not source:
            continue
        parts = source.replace(",", " ").split()
        if len(parts) >= 3:
            return parts[2]
    return None


def load_rows(csv_path: Path) -> list[tuple[str, str, str | None, str, None, str | None]]:
    with csv_path.open("r", encoding="cp949", newline="") as csv_file:
        reader = csv.reader(csv_file)
        next(reader, None)

        rows: list[tuple[str, str, str | None, str, None, str | None]] = []
        for record in reader:
            if len(record) < 5:
                continue

            category = clean(record[0])
            name = clean(record[1])
            road_address = clean(record[2])
            jibun_address = clean(record[3])
            phone = clean(record[4])
            address = road_address or jibun_address

            if not name or not address:
                continue

            rows.append(
                (
                    name,
                    address,
                    derive_district(road_address, jibun_address),
                    category or "general_restaurant",
                    None,
                    phone,
                )
            )

    return rows


def render_insert_batch(batch: list[tuple[str, str, str | None, str, None, str | None]]) -> str:
    values = []
    for name, address, district, primary_category, secondary_category, phone in batch:
        values.append(
            "("
            + ", ".join(
                [
                    sql_string(name),
                    sql_string(address),
                    sql_string(district),
                    sql_string(primary_category),
                    sql_string(secondary_category),
                    sql_string(phone),
                ]
            )
            + ")"
        )

    return (
        "INSERT INTO `store` (`name`, `address`, `district`, `primary_category`, `secondary_category`, `phone`) VALUES\n"
        + ",\n".join(values)
        + ";\n"
    )


def build_sql(csv_path: Path, rows: list[tuple[str, str, str | None, str, None, str | None]]) -> str:
    header = f"""-- Flyway Migration V11: Replace store data with Gumi general restaurant CSV.
-- Source file: {csv_path.name}
-- Source row count: {len(rows)}
-- Source columns: category, store_name, road_address, jibun_address, phone, manager_phone, manager_name

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS=0;

SET @sql := (
  SELECT IF(EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'district'
  ), 'SELECT 1', 'ALTER TABLE `store` ADD COLUMN `district` VARCHAR(50) NULL'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (
  SELECT IF(EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'primary_category'
  ), 'SELECT 1', 'ALTER TABLE `store` ADD COLUMN `primary_category` VARCHAR(100) NULL'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (
  SELECT IF(EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'secondary_category'
  ), 'SELECT 1', 'ALTER TABLE `store` ADD COLUMN `secondary_category` VARCHAR(100) NULL'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (
  SELECT IF(EXISTS(
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'phone'
  ), 'SELECT 1', 'ALTER TABLE `store` ADD COLUMN `phone` VARCHAR(50) NULL'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

ALTER TABLE `store` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

DELETE FROM `review`;
UPDATE `receipt` SET `store_id` = NULL;
DELETE FROM `store`;
ALTER TABLE `store` AUTO_INCREMENT = 1;

SET FOREIGN_KEY_CHECKS=1;

"""

    batches = [
        render_insert_batch(rows[index : index + BATCH_SIZE])
        for index in range(0, len(rows), BATCH_SIZE)
    ]
    return header + "\n".join(batches)


def main() -> None:
    csv_path = resolve_csv_path()
    rows = load_rows(csv_path)
    sql = build_sql(csv_path, rows)
    with OUTPUT_PATH.open("w", encoding="utf-8", newline="\n") as output_file:
        output_file.write(sql)
    print(f"Generated {OUTPUT_PATH} with {len(rows)} rows")


if __name__ == "__main__":
    main()
