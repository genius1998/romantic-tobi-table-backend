"""
Receipt RAG Evaluation Script
------------------------------
input_sample 폴더의 영수증 이미지를 백엔드 /api/receipt/analyze 엔드포인트로 전송해
OCR + 벡터임베딩 + LLM 매칭 결과를 수집하고 Excel로 내보냅니다.

사용법:
  python scripts/receipt_rag_eval.py --user-id root --password 0000
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

import pandas as pd
import requests

ROOT_DIR = Path(__file__).resolve().parents[1]
INPUT_DIR = ROOT_DIR / "input_sample"
OUTPUT_DIR = ROOT_DIR / "output"
DEFAULT_BACKEND_URL = "http://127.0.0.1:8080"
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Receipt RAG batch evaluation")
    p.add_argument("--user-id", required=True)
    p.add_argument("--password", required=True)
    p.add_argument("--backend-url", default=DEFAULT_BACKEND_URL)
    p.add_argument("--input-dir", default=str(INPUT_DIR))
    p.add_argument("--output-dir", default=None)
    return p.parse_args()


def now_stamp() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def login(base_url: str, user_id: str, password: str) -> str:
    resp = requests.post(
        f"{base_url}/api/auth/login",
        json={"userId": user_id, "password": password},
        timeout=30,
    )
    resp.raise_for_status()
    token = resp.json().get("accessToken")
    if not token:
        raise RuntimeError("accessToken missing from login response")
    return token


def analyze_images(base_url: str, token: str, images: List[Path]) -> List[Dict[str, Any]]:
    session = requests.Session()
    headers = {"Authorization": f"Bearer {token}"}
    results: List[Dict[str, Any]] = []

    for i, img in enumerate(images, 1):
        mime = mimetypes.guess_type(img.name)[0] or "application/octet-stream"
        print(f"  [{i}/{len(images)}] {img.name} ...", end=" ", flush=True)
        try:
            with img.open("rb") as fh:
                resp = session.post(
                    f"{base_url}/api/receipt/analyze",
                    headers=headers,
                    files={"file": (img.name, fh, mime)},
                    timeout=180,
                )
            if resp.status_code == 200:
                print("OK")
                results.append({
                    "image_file": img.name,
                    "http_status": resp.status_code,
                    "error": None,
                    "data": resp.json(),
                })
            else:
                print(f"ERROR {resp.status_code}")
                results.append({
                    "image_file": img.name,
                    "http_status": resp.status_code,
                    "error": resp.text[:500],
                    "data": None,
                })
        except Exception as exc:
            print(f"EXCEPTION: {exc}")
            results.append({
                "image_file": img.name,
                "http_status": 0,
                "error": str(exc),
                "data": None,
            })

    return results


def _s(val: Any, default: str = "") -> str:
    return str(val) if val is not None else default


def build_summary_row(rec: Dict[str, Any]) -> Dict[str, Any]:
    data = rec["data"] or {}
    pr = data.get("parsedReceipt") or {}
    nr = data.get("normalizedReceipt") or {}
    ms = data.get("matchedStore") or {}
    md = data.get("matchingDebug") or {}
    llm = md.get("llmDecision") or {}

    # parsedReceipt LLM debug
    raw = pr.get("raw") or {}
    llm_result = raw.get("llm_result") or {}

    return {
        "영수증파일": rec["image_file"],
        "HTTP상태": rec["http_status"],
        "OCR_가게명": pr.get("store_name"),
        "OCR_주소": pr.get("address"),
        "OCR_금액(원문)": pr.get("amount"),
        "OCR_결제일(원문)": pr.get("paid_at"),
        "정규화_금액": nr.get("amount"),
        "정규화_결제일": _s(nr.get("paidDate")),
        "예상포인트": nr.get("expectedPoints"),
        "매칭여부": data.get("matched"),
        "의사결정타입": data.get("decisionType"),
        "검색방식": md.get("retrievalMethod"),
        "매칭점수": md.get("score"),
        "매칭가게ID": ms.get("id"),
        "매칭가게명": ms.get("name"),
        "매칭가게주소": ms.get("address"),
        "LLM_신뢰도": llm.get("confidence"),
        "LLM_이유": llm.get("reason"),
        "LLM_에러": llm.get("error"),
        "파싱LLM_가게명신뢰도": (llm_result.get("store_name") or {}).get("confidence"),
        "파싱LLM_금액신뢰도": (llm_result.get("paid_amount") or {}).get("confidence"),
        "파싱LLM_주소신뢰도": (llm_result.get("address") or {}).get("confidence"),
        "파싱LLM_날짜신뢰도": (llm_result.get("paid_date") or {}).get("confidence"),
        "오류메시지": rec["error"],
    }


def build_candidate_rows(rec: Dict[str, Any]) -> List[Dict[str, Any]]:
    data = rec["data"] or {}
    md = data.get("matchingDebug") or {}
    candidates = md.get("candidates") or []
    matched_id = (data.get("matchedStore") or {}).get("id")

    rows = []
    for rank, c in enumerate(candidates, 1):
        rows.append({
            "영수증파일": rec["image_file"],
            "순위": rank,
            "후보가게ID": c.get("storeId"),
            "후보가게명": c.get("name"),
            "후보가게주소": c.get("address"),
            "종합점수": c.get("score"),
            "이름점수": c.get("nameScore"),
            "주소점수": c.get("addressScore"),
            "의미점수(벡터)": c.get("semanticScore"),
            "검색소스": c.get("source"),
            "최종매칭여부": c.get("storeId") == matched_id and matched_id is not None,
        })
    return rows


def build_ocr_rows(rec: Dict[str, Any]) -> Dict[str, Any]:
    data = rec["data"] or {}
    ocr = data.get("ocr") or {}
    lines = ocr.get("lines") or []
    return {
        "영수증파일": rec["image_file"],
        "OCR_줄수": len(lines),
        "OCR_전체텍스트": ocr.get("fullText"),
    }


def auto_fit(ws: Any) -> None:
    for col_cells in ws.columns:
        max_len = max((min(len(str(c.value or "")), 120) for c in col_cells), default=10)
        ws.column_dimensions[col_cells[0].column_letter].width = max(12, min(max_len + 2, 80))


def write_excel(output_path: Path, summary: list, candidates: list, ocr: list, raw: list) -> None:
    with pd.ExcelWriter(output_path, engine="openpyxl") as writer:
        for df, sheet in [
            (pd.DataFrame(summary), "summary"),
            (pd.DataFrame(candidates), "candidates"),
            (pd.DataFrame(ocr), "ocr_text"),
            (pd.DataFrame(raw), "raw_json"),
        ]:
            df.to_excel(writer, index=False, sheet_name=sheet)
        for name in writer.book.sheetnames:
            auto_fit(writer.book[name])


def main() -> None:
    args = parse_args()
    input_dir = Path(args.input_dir).expanduser().resolve()
    if not input_dir.exists():
        raise FileNotFoundError(f"input_dir not found: {input_dir}")

    images = sorted(p for p in input_dir.iterdir() if p.is_file() and p.suffix.lower() in IMAGE_EXTENSIONS)
    if not images:
        raise FileNotFoundError(f"No images in {input_dir}")

    out_root = Path(args.output_dir).resolve() if args.output_dir else OUTPUT_DIR / f"rag_eval_{now_stamp()}"
    out_root.mkdir(parents=True, exist_ok=True)
    excel_path = out_root / "receipt_rag_eval.xlsx"

    print(f"[config] input_dir = {input_dir}")
    print(f"[config] images    = {len(images)}")
    print(f"[config] output    = {excel_path}")
    print()

    print("[step 1] 로그인 중 ...")
    token = login(args.backend_url.rstrip("/"), args.user_id, args.password)
    print("[step 1] 로그인 성공")
    print()

    print("[step 2] 영수증 분석 중 ...")
    records = analyze_images(args.backend_url.rstrip("/"), token, images)
    print()

    # save raw JSON for debugging
    raw_json_path = out_root / "pipeline_records.json"
    raw_json_path.write_text(
        json.dumps(records, ensure_ascii=False, indent=2, default=str),
        encoding="utf-8",
    )

    summary_rows = [build_summary_row(r) for r in records]
    candidate_rows = [row for r in records for row in build_candidate_rows(r)]
    ocr_rows = [build_ocr_rows(r) for r in records]
    raw_rows = [{"영수증파일": r["image_file"], "raw_json": json.dumps(r["data"], ensure_ascii=False)} for r in records]

    print("[step 3] Excel 작성 중 ...")
    write_excel(excel_path, summary_rows, candidate_rows, ocr_rows, raw_rows)

    matched_count = sum(1 for r in summary_rows if r.get("매칭여부"))
    print(f"[결과] 총 {len(records)}개 / 매칭성공 {matched_count}개 / 매칭실패 {len(records) - matched_count}개")
    print(f"[결과] Excel 저장 완료 → {excel_path}")


if __name__ == "__main__":
    main()
