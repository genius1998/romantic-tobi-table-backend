from __future__ import annotations

import argparse
import json
import mimetypes
import os
import re
import time
import unicodedata
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import pandas as pd
import requests
from dotenv import dotenv_values
from openai import OpenAI


ROOT_DIR = Path(__file__).resolve().parents[1]
INPUT_DIR = ROOT_DIR / "input_sample"
DEFAULT_OUTPUT_PARENT = ROOT_DIR / "output"
ENV_PATH = ROOT_DIR / ".env"
DEFAULT_BACKEND_URL = "http://127.0.0.1:8080"
DEFAULT_MODEL = "gpt-5.4"
DEFAULT_POLL_INTERVAL_SECONDS = 15
DEFAULT_MAX_WAIT_SECONDS = 1800

SYSTEM_PROMPT = (
    "너는 한국어 카드 영수증 OCR 텍스트를 구조화하는 영수증 파서다. "
    "입력 OCR 텍스트를 읽고 가게명, 결제금액, 가맹점주소, 결제날짜를 추출한다."
)

USER_PROMPT_TEMPLATE = """목표:
아래 OCR 텍스트에서 가게명, 결제금액, 가맹점주소, 결제날짜를 추출한다.

반드시 지켜야 할 규칙:
1. OCR에 없는 값은 추측하지 말고 null로 둔다.
2. 가게명은 "가맹점명:", "가맹점:", "상호:", "매장명:", "업체명:" 뒤에 있는 값을 우선 사용한다.
3. "KICC", "여신금융협회", "CasyCheck", "삼성카드", "MasterCard", "신고안내", "가맹점명/가맹점주소가 실제와 다른 경우", "가맹점명, 가맹점 주소가 실제와 다를 경우" 같은 경고문은 가게명이 아니다.
4. 대표자명은 가게명이 아니다.
5. 한 줄에 "가게명/대표자명" 형태가 함께 있으면 슬래시(/) 앞쪽만 가게명으로 본다.
6. 주소는 "주소:", "가맹점주소:", "매장주소:" 뒤에 있는 실제 도로명/지번 주소를 우선 사용한다.
7. 결제금액은 고객이 실제 결제한 최종 합계 금액이다.
   - "합계", "합 계", "총액", "총 결제금액", "받을금액"이 있으면 이를 최우선으로 사용한다.
   - 금액은 숫자만 남긴 정수로 본다.
8. 결제날짜는 "거래 일시", "승인 일시", "결제 일시", "매출 일시", "전표 일시"에서 찾는다.
   - 시간이 같이 있어도 payment_date에는 YYYY-MM-DD 형식의 날짜만 넣는다.
   - 날짜가 없으면 null로 둔다.
9. 애매하면 가장 보수적인 값을 선택하고, 확실하지 않으면 null로 둔다.

OCR 텍스트:
\"\"\"
%s
\"\"\""""

OUTPUT_SCHEMA = {
    "type": "object",
    "properties": {
        "store_name": {
            "type": ["string", "null"],
            "description": "영수증의 가게명. 대표자명이나 경고문이 아니라 실제 매장명.",
        },
        "payment_amount": {
            "type": ["integer", "null"],
            "description": "고객이 실제 결제한 최종 합계 금액. 원 단위 정수.",
        },
        "store_address": {
            "type": ["string", "null"],
            "description": "가맹점 주소. OCR에 없으면 null.",
        },
        "payment_date": {
            "anyOf": [
                {"type": "string", "pattern": r"^\d{4}-\d{2}-\d{2}$"},
                {"type": "null"},
            ],
            "description": "결제 날짜. YYYY-MM-DD 형식.",
        },
    },
    "required": ["store_name", "payment_amount", "store_address", "payment_date"],
    "additionalProperties": False,
}

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


@dataclass
class PipelineRecord:
    image_file: str
    image_path: str
    http_status: int
    error_message: Optional[str]
    response_json: Optional[Dict[str, Any]]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the local receipt pipeline on input_sample images, normalize OCR text with GPT-5.4 Batch, and export an Excel comparison workbook."
    )
    parser.add_argument("--user-id", required=True, help="Backend login userId")
    parser.add_argument("--password", required=True, help="Backend login password")
    parser.add_argument("--backend-url", default=DEFAULT_BACKEND_URL, help="Spring backend base URL")
    parser.add_argument("--input-dir", default=str(INPUT_DIR), help="Directory containing receipt images")
    parser.add_argument("--output-dir", default=None, help="Optional output directory")
    parser.add_argument("--pipeline-json", default=None, help="Reuse a previously saved pipeline_records.json file")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="OpenAI model for batch normalization")
    parser.add_argument("--poll-interval", type=int, default=DEFAULT_POLL_INTERVAL_SECONDS, help="Batch poll interval in seconds")
    parser.add_argument("--max-wait-seconds", type=int, default=DEFAULT_MAX_WAIT_SECONDS, help="Maximum batch wait time")
    return parser.parse_args()


def now_stamp() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def load_api_key() -> str:
    values = dotenv_values(ENV_PATH)
    api_key = values.get("OPENAI_API_KEY") or os.environ.get("OPENAI_API_KEY")
    if not api_key:
        raise RuntimeError(f"OPENAI_API_KEY not found in {ENV_PATH}")
    return api_key


def resolve_output_dir(output_dir: Optional[str]) -> Path:
    if output_dir:
        path = Path(output_dir).expanduser().resolve()
    else:
        path = (DEFAULT_OUTPUT_PARENT / f"receipt_sample_batch_eval_{now_stamp()}").resolve()
    path.mkdir(parents=True, exist_ok=True)
    return path


def list_images(input_dir: Path) -> List[Path]:
    if not input_dir.exists():
        raise FileNotFoundError(f"Input directory not found: {input_dir}")
    images = sorted(path for path in input_dir.iterdir() if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS)
    if not images:
        raise FileNotFoundError(f"No images found in {input_dir}")
    return images


def login(base_url: str, user_id: str, password: str) -> str:
    response = requests.post(
        f"{base_url}/api/auth/login",
        json={"userId": user_id, "password": password},
        timeout=30,
    )
    response.raise_for_status()
    payload = response.json()
    token = payload.get("accessToken")
    if not token:
        raise RuntimeError("Login succeeded but accessToken is missing")
    return token


def analyze_images(base_url: str, token: str, images: List[Path]) -> List[PipelineRecord]:
    session = requests.Session()
    headers = {"Authorization": f"Bearer {token}"}
    records: List[PipelineRecord] = []

    for index, image_path in enumerate(images, start=1):
        mime_type = mimetypes.guess_type(image_path.name)[0] or "application/octet-stream"
        print(f"[pipeline] {index}/{len(images)} {image_path.name}")
        try:
            with image_path.open("rb") as file_handle:
                response = session.post(
                    f"{base_url}/api/receipt/analyze",
                    headers=headers,
                    files={"file": (image_path.name, file_handle, mime_type)},
                    timeout=180,
                )
            if response.status_code == 200:
                records.append(
                    PipelineRecord(
                        image_file=image_path.name,
                        image_path=str(image_path),
                        http_status=response.status_code,
                        error_message=None,
                        response_json=response.json(),
                    )
                )
            else:
                records.append(
                    PipelineRecord(
                        image_file=image_path.name,
                        image_path=str(image_path),
                        http_status=response.status_code,
                        error_message=response.text,
                        response_json=None,
                    )
                )
        except Exception as exc:
            records.append(
                PipelineRecord(
                    image_file=image_path.name,
                    image_path=str(image_path),
                    http_status=0,
                    error_message=str(exc),
                    response_json=None,
                )
            )

    return records


def load_pipeline_records(path: Path) -> List[PipelineRecord]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return [PipelineRecord(**item) for item in payload]


def build_batch_body(ocr_text: str, model: str) -> Dict[str, Any]:
    return {
        "model": model,
        "input": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": USER_PROMPT_TEMPLATE % ocr_text},
        ],
        "text": {
            "verbosity": "low",
            "format": {
                "type": "json_schema",
                "name": "receipt_normalization",
                "strict": True,
                "schema": OUTPUT_SCHEMA,
            }
        },
        "reasoning": {"effort": "none"},
        "max_output_tokens": 200,
    }


def write_batch_input(records: List[PipelineRecord], output_dir: Path, model: str) -> Path:
    jsonl_path = output_dir / "gpt54_batch_input.jsonl"
    with jsonl_path.open("w", encoding="utf-8") as handle:
        for record in records:
            if not record.response_json:
                continue
            ocr_text = (((record.response_json or {}).get("ocr") or {}).get("fullText") or "").strip()
            if not ocr_text:
                continue
            request_line = {
                "custom_id": record.image_file,
                "method": "POST",
                "url": "/v1/responses",
                "body": build_batch_body(ocr_text, model),
            }
            handle.write(json.dumps(request_line, ensure_ascii=False) + "\n")
    return jsonl_path


def submit_and_wait_for_batch(
    client: OpenAI,
    batch_input_path: Path,
    output_dir: Path,
    poll_interval: int,
    max_wait_seconds: int,
) -> Tuple[Dict[str, Any], Optional[Path], Optional[Path]]:
    with batch_input_path.open("rb") as handle:
        input_file = client.files.create(file=handle, purpose="batch")

    batch = client.batches.create(
        input_file_id=input_file.id,
        endpoint="/v1/responses",
        completion_window="24h",
        metadata={"description": "receipt sample OCR normalization"},
    )
    print(f"[batch] created {batch.id}")

    started_at = time.time()
    while True:
        current = client.batches.retrieve(batch.id)
        status = current.status
        counts = current.request_counts
        completed = getattr(counts, "completed", None)
        failed = getattr(counts, "failed", None)
        total = getattr(counts, "total", None)
        print(f"[batch] status={status} completed={completed} failed={failed} total={total}")

        if status == "completed":
            output_path = None
            error_path = None
            if current.output_file_id:
                output_text = client.files.content(current.output_file_id).text
                output_path = output_dir / "gpt54_batch_output.jsonl"
                output_path.write_text(output_text, encoding="utf-8")
            if current.error_file_id:
                error_text = client.files.content(current.error_file_id).text
                error_path = output_dir / "gpt54_batch_error.jsonl"
                error_path.write_text(error_text, encoding="utf-8")
            return current.to_dict(), output_path, error_path

        if status in {"failed", "expired", "cancelled"}:
            error_path = None
            if current.error_file_id:
                error_text = client.files.content(current.error_file_id).text
                error_path = output_dir / "gpt54_batch_error.jsonl"
                error_path.write_text(error_text, encoding="utf-8")
            return current.to_dict(), None, error_path

        if time.time() - started_at > max_wait_seconds:
            raise TimeoutError(f"Batch {batch.id} did not complete within {max_wait_seconds} seconds")

        time.sleep(poll_interval)


def extract_response_text(response_body: Dict[str, Any]) -> Optional[str]:
    output_text = response_body.get("output_text")
    if isinstance(output_text, str) and output_text.strip():
        return output_text

    for item in response_body.get("output", []):
        if item.get("type") != "message":
            continue
        for content in item.get("content", []):
            if content.get("type") == "output_text":
                text = content.get("text")
                if isinstance(text, str) and text.strip():
                    return text
    return None


def parse_batch_output(output_path: Optional[Path]) -> Dict[str, Dict[str, Any]]:
    if output_path is None or not output_path.exists():
        return {}

    parsed: Dict[str, Dict[str, Any]] = {}
    for line in output_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        custom_id = row.get("custom_id")
        response = row.get("response") or {}
        response_body = response.get("body") or {}
        raw_text = extract_response_text(response_body)
        parsed_json = None
        parse_error = None

        if raw_text:
            try:
                parsed_json = json.loads(raw_text)
            except json.JSONDecodeError as exc:
                parse_error = f"json_decode_error: {exc}"
        else:
            parse_error = "missing_output_text"

        parsed[custom_id] = {
            "custom_id": custom_id,
            "status_code": response.get("status_code"),
            "request_id": response.get("request_id"),
            "raw_text": raw_text,
            "parsed_json": parsed_json,
            "error": row.get("error"),
            "parse_error": parse_error,
            "response_body": response_body,
        }
    return parsed


def normalize_text(value: Any) -> str:
    if value is None:
        return ""
    text = unicodedata.normalize("NFKC", str(value)).strip().lower()
    text = re.sub(r"[\s\-\_,./(){}\[\]:;\"'`~!@#$%^&*+=|\\<>?]+", "", text)
    return text


def values_equal_strict(left: Any, right: Any) -> bool:
    if left in (None, "") and right in (None, ""):
        return True
    return normalize_text(left) == normalize_text(right)


def values_equal_relaxed_address(left: Any, right: Any) -> bool:
    if left in (None, "") and right in (None, ""):
        return True
    left_norm = normalize_text(left)
    right_norm = normalize_text(right)
    if not left_norm or not right_norm:
        return False
    return left_norm == right_norm or left_norm in right_norm or right_norm in left_norm


def values_equal_amount(left: Any, right: Any) -> bool:
    if left in (None, "") and right in (None, ""):
        return True
    try:
        return int(left) == int(right)
    except Exception:
        return False


def build_rows(
    records: List[PipelineRecord],
    gpt_results: Dict[str, Dict[str, Any]],
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]], List[Dict[str, Any]]]:
    summary_rows: List[Dict[str, Any]] = []
    ocr_rows: List[Dict[str, Any]] = []
    pipeline_raw_rows: List[Dict[str, Any]] = []
    gpt_raw_rows: List[Dict[str, Any]] = []

    for record in records:
        response_json = record.response_json or {}
        ocr = response_json.get("ocr") or {}
        parsed_receipt = response_json.get("parsedReceipt") or {}
        normalized_receipt = response_json.get("normalizedReceipt") or {}
        matched_store = response_json.get("matchedStore") or {}

        gpt_result = gpt_results.get(record.image_file) or {}
        gpt_parsed = gpt_result.get("parsed_json") or {}

        pipeline_store_name = parsed_receipt.get("store_name")
        pipeline_amount_raw = parsed_receipt.get("amount")
        pipeline_address = parsed_receipt.get("address")
        pipeline_paid_at_raw = parsed_receipt.get("paid_at")
        pipeline_amount = normalized_receipt.get("amount")
        pipeline_payment_date = normalized_receipt.get("paidDate")

        gpt_store_name = gpt_parsed.get("store_name")
        gpt_amount = gpt_parsed.get("payment_amount")
        gpt_address = gpt_parsed.get("store_address")
        gpt_payment_date = gpt_parsed.get("payment_date")

        store_name_match = values_equal_strict(pipeline_store_name, gpt_store_name)
        amount_match = values_equal_amount(pipeline_amount, gpt_amount)
        address_match = values_equal_relaxed_address(pipeline_address, gpt_address)
        payment_date_match = values_equal_strict(pipeline_payment_date, gpt_payment_date)
        overall_match = all([store_name_match, amount_match, address_match, payment_date_match])

        note_parts = []
        if record.error_message:
            note_parts.append(f"pipeline_error={record.error_message}")
        if gpt_result.get("parse_error"):
            note_parts.append(f"gpt_parse_error={gpt_result['parse_error']}")
        if gpt_result.get("error"):
            note_parts.append(f"gpt_error={gpt_result['error']}")

        summary_rows.append(
            {
                "image_file": record.image_file,
                "pipeline_http_status": record.http_status,
                "pipeline_store_name": pipeline_store_name,
                "pipeline_amount_raw": pipeline_amount_raw,
                "pipeline_amount_normalized": pipeline_amount,
                "pipeline_store_address": pipeline_address,
                "pipeline_paid_at_raw": pipeline_paid_at_raw,
                "pipeline_payment_date_normalized": pipeline_payment_date,
                "pipeline_matched": response_json.get("matched"),
                "pipeline_matched_store_id": matched_store.get("id"),
                "pipeline_matched_store_name": matched_store.get("name"),
                "pipeline_matched_store_address": matched_store.get("address"),
                "pipeline_decision_type": response_json.get("decisionType"),
                "gpt_store_name": gpt_store_name,
                "gpt_payment_amount": gpt_amount,
                "gpt_store_address": gpt_address,
                "gpt_payment_date": gpt_payment_date,
                "store_name_match": store_name_match,
                "payment_amount_match": amount_match,
                "store_address_match": address_match,
                "payment_date_match": payment_date_match,
                "overall_parse_match": overall_match,
                "notes": " | ".join(note_parts) if note_parts else None,
            }
        )

        ocr_rows.append(
            {
                "image_file": record.image_file,
                "ocr_line_count": len(ocr.get("lines") or []),
                "ocr_full_text": ocr.get("fullText"),
            }
        )

        pipeline_raw_rows.append(
            {
                "image_file": record.image_file,
                "pipeline_response_json": json.dumps(response_json, ensure_ascii=False),
            }
        )

        gpt_raw_rows.append(
            {
                "image_file": record.image_file,
                "gpt_status_code": gpt_result.get("status_code"),
                "gpt_raw_text": gpt_result.get("raw_text"),
                "gpt_response_json": json.dumps(gpt_result.get("response_body") or {}, ensure_ascii=False),
            }
        )

    return summary_rows, ocr_rows, pipeline_raw_rows, gpt_raw_rows


def auto_fit_worksheet(worksheet: Any) -> None:
    for column_cells in worksheet.columns:
        max_length = 0
        column_letter = column_cells[0].column_letter
        for cell in column_cells:
            value = "" if cell.value is None else str(cell.value)
            max_length = max(max_length, min(len(value), 120))
        worksheet.column_dimensions[column_letter].width = max(12, min(max_length + 2, 80))


def write_excel(
    output_dir: Path,
    summary_rows: List[Dict[str, Any]],
    ocr_rows: List[Dict[str, Any]],
    pipeline_raw_rows: List[Dict[str, Any]],
    gpt_raw_rows: List[Dict[str, Any]],
) -> Path:
    excel_path = output_dir / "receipt_sample_batch_eval.xlsx"

    summary_df = pd.DataFrame(summary_rows)
    ocr_df = pd.DataFrame(ocr_rows)
    pipeline_raw_df = pd.DataFrame(pipeline_raw_rows)
    gpt_raw_df = pd.DataFrame(gpt_raw_rows)

    with pd.ExcelWriter(excel_path, engine="openpyxl") as writer:
        summary_df.to_excel(writer, index=False, sheet_name="summary")
        ocr_df.to_excel(writer, index=False, sheet_name="ocr_text")
        pipeline_raw_df.to_excel(writer, index=False, sheet_name="pipeline_raw")
        gpt_raw_df.to_excel(writer, index=False, sheet_name="gpt_batch_raw")

        for sheet_name in writer.book.sheetnames:
            auto_fit_worksheet(writer.book[sheet_name])

    return excel_path


def write_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    args = parse_args()
    input_dir = Path(args.input_dir).expanduser().resolve()
    output_dir = resolve_output_dir(args.output_dir)
    api_key = load_api_key()
    print(f"[config] output_dir={output_dir}")
    print(f"[config] input_dir={input_dir}")

    if args.pipeline_json:
        pipeline_json_path = Path(args.pipeline_json).expanduser().resolve()
        pipeline_records = load_pipeline_records(pipeline_json_path)
        print(f"[config] pipeline_json={pipeline_json_path}")
    else:
        images = list_images(input_dir)
        print(f"[config] images={len(images)}")
        token = login(args.backend_url.rstrip("/"), args.user_id, args.password)
        pipeline_records = analyze_images(args.backend_url.rstrip("/"), token, images)

    print(f"[config] pipeline_records={len(pipeline_records)}")
    write_json(output_dir / "pipeline_records.json", [record.__dict__ for record in pipeline_records])

    batch_input_path = write_batch_input(pipeline_records, output_dir, args.model)
    print(f"[batch] input file written to {batch_input_path}")

    client = OpenAI(api_key=api_key)
    batch_meta, batch_output_path, batch_error_path = submit_and_wait_for_batch(
        client=client,
        batch_input_path=batch_input_path,
        output_dir=output_dir,
        poll_interval=args.poll_interval,
        max_wait_seconds=args.max_wait_seconds,
    )
    write_json(output_dir / "gpt54_batch_meta.json", batch_meta)

    if batch_error_path:
        print(f"[batch] error file saved to {batch_error_path}")
    if batch_output_path:
        print(f"[batch] output file saved to {batch_output_path}")

    gpt_results = parse_batch_output(batch_output_path)
    write_json(output_dir / "gpt54_batch_parsed.json", gpt_results)

    summary_rows, ocr_rows, pipeline_raw_rows, gpt_raw_rows = build_rows(pipeline_records, gpt_results)
    excel_path = write_excel(output_dir, summary_rows, ocr_rows, pipeline_raw_rows, gpt_raw_rows)

    match_count = sum(1 for row in summary_rows if row["overall_parse_match"])
    print(f"[result] overall_parse_match={match_count}/{len(summary_rows)}")
    print(f"[result] excel={excel_path}")


if __name__ == "__main__":
    main()
