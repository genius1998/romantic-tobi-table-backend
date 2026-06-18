from __future__ import annotations

import csv
from pathlib import Path
from datetime import datetime

ROOT = Path(__file__).resolve().parents[1]
CSV_PATH = ROOT / "식품_일반음식점.csv"
OUTPUT_PATH = ROOT / "src" / "main" / "resources" / "db" / "migration" / "V12__replace_store_with_national_restaurant_csv.sql"
BATCH_SIZE = 500

TARGET_CITY = "경상북도 구미시"
OPEN_STATUS = "영업/정상"

# CSV column indices
COL_LICENSED_AT = 2
COL_STATUS = 3
COL_AREA = 5
COL_NAME = 8
COL_CATEGORY = 9
COL_ROAD_ADDRESS = 19
COL_PHONE = 33
COL_COORD_X = 34
COL_COORD_Y = 35
COL_JIBUN_ADDRESS = 36


def clean(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = value.strip()
    return normalized or None


def sql_str(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def sql_float(value: str | None) -> str:
    if not value or not value.strip():
        return "NULL"
    try:
        return str(float(value.strip()))
    except ValueError:
        return "NULL"


def sql_date(value: str | None) -> str:
    if not value or not value.strip():
        return "NULL"
    raw = value.strip()
    for fmt in ("%Y-%m-%d", "%Y%m%d"):
        try:
            dt = datetime.strptime(raw, fmt)
            return f"'{dt.strftime('%Y-%m-%d')}'"
        except ValueError:
            continue
    return "NULL"


def derive_district(road_address: str | None, jibun_address: str | None) -> str | None:
    for source in (road_address, jibun_address):
        if not source:
            continue
        parts = source.replace(",", " ").split()
        # '경상북도 구미시 선산읍 ...' → index 2
        if len(parts) >= 3:
            return parts[2]
    return None


def load_rows(csv_path: Path) -> list[dict]:
    rows = []
    with csv_path.open("r", encoding="cp949", newline="") as f:
        reader = csv.reader(f)
        next(reader, None)  # skip header
        for record in reader:
            if len(record) <= COL_JIBUN_ADDRESS:
                continue
            if record[COL_STATUS].strip() != OPEN_STATUS:
                continue

            road_address = clean(record[COL_ROAD_ADDRESS])
            jibun_address = clean(record[COL_JIBUN_ADDRESS])
            address = road_address or jibun_address

            if not address or TARGET_CITY not in address:
                continue

            name = clean(record[COL_NAME])
            if not name:
                continue

            rows.append({
                "name": name,
                "address": address,
                "jibun_address": jibun_address if road_address else None,
                "district": derive_district(road_address, jibun_address),
                "category": clean(record[COL_CATEGORY]),
                "phone": clean(record[COL_PHONE]),
                "coord_x": clean(record[COL_COORD_X]),
                "coord_y": clean(record[COL_COORD_Y]),
                "area": clean(record[COL_AREA]),
                "licensed_at": clean(record[COL_LICENSED_AT]),
            })
    return rows


def render_batch(batch: list[dict]) -> str:
    values = []
    for r in batch:
        values.append(
            "("
            + ", ".join([
                sql_str(r["name"]),
                sql_str(r["address"]),
                sql_str(r["jibun_address"]),
                sql_str(r["district"]),
                sql_str(r["category"]),
                sql_str(r["phone"]),
                sql_float(r["coord_x"]),
                sql_float(r["coord_y"]),
                sql_float(r["area"]),
                sql_date(r["licensed_at"]),
            ])
            + ")"
        )
    cols = "`name`, `address`, `jibun_address`, `district`, `primary_category`, `phone`, `coord_x`, `coord_y`, `area`, `licensed_at`"
    return f"INSERT INTO `store` ({cols}) VALUES\n" + ",\n".join(values) + ";\n"


def build_sql(rows: list[dict]) -> str:
    header = f"""-- Flyway Migration V12: Replace store data with national restaurant CSV (구미시 영업중 필터).
-- Source file: {CSV_PATH.name}
-- Filter: 영업상태 = '영업/정상', 도로명주소 LIKE '경상북도 구미시%'
-- Row count: {len(rows)}
-- Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS=0;

-- Add new columns if not exist
SET @sql := (SELECT IF(EXISTS(
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'jibun_address'
), 'SELECT 1', 'ALTER TABLE `store` ADD COLUMN `jibun_address` VARCHAR(255) NULL'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(EXISTS(
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'coord_x'
), 'SELECT 1', 'ALTER TABLE `store` ADD COLUMN `coord_x` DOUBLE NULL'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(EXISTS(
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'coord_y'
), 'SELECT 1', 'ALTER TABLE `store` ADD COLUMN `coord_y` DOUBLE NULL'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(EXISTS(
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'area'
), 'SELECT 1', 'ALTER TABLE `store` ADD COLUMN `area` DECIMAL(10,2) NULL'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := (SELECT IF(EXISTS(
  SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store' AND COLUMN_NAME = 'licensed_at'
), 'SELECT 1', 'ALTER TABLE `store` ADD COLUMN `licensed_at` DATE NULL'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

ALTER TABLE `store` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

DELETE FROM `review`;
UPDATE `receipt` SET `store_id` = NULL;
DELETE FROM `store`;
ALTER TABLE `store` AUTO_INCREMENT = 1;

SET FOREIGN_KEY_CHECKS=1;

"""
    batches = [
        render_batch(rows[i: i + BATCH_SIZE])
        for i in range(0, len(rows), BATCH_SIZE)
    ]
    return header + "\n".join(batches)


def main() -> None:
    print(f"Reading {CSV_PATH} ...")
    rows = load_rows(CSV_PATH)
    print(f"Filtered rows: {len(rows)}")
    sql = build_sql(rows)
    with OUTPUT_PATH.open("w", encoding="utf-8", newline="\n") as f:
        f.write(sql)
    print(f"Generated: {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
