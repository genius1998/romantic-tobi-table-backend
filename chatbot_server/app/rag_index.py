"""
RAG 문서 인덱싱 및 검색.

검색 전략:
  Primary  — BM25 (rank_bm25, 메모리 경량, 한국어 형태소 불필요)
  Optional — FAISS + sentence-transformers (USE_NEURAL_EMBEDDING=true 설정 시)

지원 문서:
  ../chatbot_data/docs/*.md
  ../chatbot_data/json/*.json

사용:
  python -m app.rag_index           # 인덱스 생성 (이미 있으면 스킵)
  python -m app.rag_index --force   # 강제 재생성
"""

from __future__ import annotations

import json
import logging
import os
import pickle
from pathlib import Path
from typing import Optional

from app.config import (
    CHUNK_OVERLAP,
    CHUNK_SIZE,
    DOCS_DIR,
    EMBEDDING_MODEL,
    JSON_DIR,
    TOP_K,
    VECTORSTORE_DIR,
)

logger = logging.getLogger(__name__)

# neural embedding 사용 여부 (환경변수로 제어)
_USE_NEURAL = os.getenv("USE_NEURAL_EMBEDDING", "false").lower() == "true"

_BM25_PATH = VECTORSTORE_DIR / "bm25.pkl"       # BM25 인덱스
_CHUNKS_PATH = VECTORSTORE_DIR / "chunks.pkl"    # 청크 메타데이터
_INDEX_PATH = VECTORSTORE_DIR / "faiss.index"   # FAISS (optional)

# ── 전역 상태 ─────────────────────────────────────────
_bm25 = None
_chunks: list = []
_faiss_index = None          # FAISS (optional)
_embedder_cache = None       # sentence-transformers (optional)


# ─────────────────────────────────────────────────────
# 문서 읽기
# ─────────────────────────────────────────────────────

def _read_docs() -> list:
    docs = []
    for md_file in sorted(DOCS_DIR.glob("*.md")):
        text = md_file.read_text(encoding="utf-8")
        docs.append({"source": md_file.name, "text": text})
    for json_file in sorted(JSON_DIR.glob("*.json")):
        raw = json.loads(json_file.read_text(encoding="utf-8"))
        text = _json_to_text(raw, json_file.name)
        docs.append({"source": json_file.name, "text": text})
    return docs


def _json_to_text(data, filename: str) -> str:
    lines = [f"[파일: {filename}]"]

    def _walk(obj, indent=0):
        prefix = "  " * indent
        if isinstance(obj, dict):
            for k, v in obj.items():
                if isinstance(v, (dict, list)):
                    lines.append(f"{prefix}{k}:")
                    _walk(v, indent + 1)
                else:
                    lines.append(f"{prefix}{k}: {v}")
        elif isinstance(obj, list):
            for item in obj:
                if isinstance(item, (dict, list)):
                    _walk(item, indent)
                    lines.append("")
                else:
                    lines.append(f"{prefix}- {item}")

    _walk(data)
    return "\n".join(lines)


# ─────────────────────────────────────────────────────
# 청킹
# ─────────────────────────────────────────────────────

def _chunk_text(text: str, size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP) -> list:
    if len(text) <= size:
        return [text]
    chunks = []
    start = 0
    while start < len(text):
        end = start + size
        chunks.append(text[start:end])
        start += size - overlap
    return chunks


def _build_chunks(docs: list) -> list:
    chunks = []
    for doc in docs:
        parts = _chunk_text(doc["text"])
        for i, part in enumerate(parts):
            chunks.append({
                "source_file": doc["source"],
                "chunk_index": i,
                "text": part,
                "preview": part[:120].replace("\n", " ").strip(),
            })
    return chunks


# ─────────────────────────────────────────────────────
# BM25 헬퍼
# ─────────────────────────────────────────────────────

def _tokenize(text: str) -> list:
    """간단한 한국어/영문 토크나이저 (공백 + 2-gram 문자)."""
    tokens = text.lower().split()
    # 문자 2-gram 추가 (한국어 형태소 분석 없이도 유사도 향상)
    chars = text.replace(" ", "")
    bigrams = [chars[i:i+2] for i in range(len(chars) - 1)]
    return tokens + bigrams


# ─────────────────────────────────────────────────────
# 인덱스 빌드 / 저장 / 로드
# ─────────────────────────────────────────────────────

def build_index(force: bool = False) -> None:
    """BM25 인덱스를 생성 또는 재생성한다."""
    from rank_bm25 import BM25Okapi

    if _BM25_PATH.exists() and _CHUNKS_PATH.exists() and not force:
        logger.info("BM25 인덱스가 이미 존재합니다. --force 로 재생성 가능합니다.")
        return

    VECTORSTORE_DIR.mkdir(parents=True, exist_ok=True)

    logger.info("문서 읽는 중...")
    docs = _read_docs()
    chunks = _build_chunks(docs)
    logger.info(f"총 {len(chunks)}개 청크 생성 (문서 {len(docs)}개)")

    logger.info("BM25 인덱스 빌드 중...")
    tokenized = [_tokenize(c["text"]) for c in chunks]
    bm25 = BM25Okapi(tokenized)

    with open(_BM25_PATH, "wb") as f:
        pickle.dump(bm25, f)
    with open(_CHUNKS_PATH, "wb") as f:
        pickle.dump(chunks, f)

    logger.info(f"BM25 인덱스 저장 완료: {_BM25_PATH}")

    # 선택적 FAISS 인덱스
    if _USE_NEURAL:
        _build_faiss(chunks)


def _build_faiss(chunks: list) -> None:
    """FAISS 벡터 인덱스를 빌드한다 (USE_NEURAL_EMBEDDING=true 시)."""
    try:
        import faiss
        import numpy as np
        embedder = _get_embedder()
        texts = [c["text"] for c in chunks]
        logger.info(f"신경망 임베딩 생성 중 ({len(texts)}개 청크)...")
        vectors = embedder.encode(texts, normalize_embeddings=True, show_progress_bar=True)
        dim = vectors.shape[1]
        index = faiss.IndexFlatIP(dim)
        index.add(vectors.astype("float32"))
        faiss.write_index(index, str(_INDEX_PATH))
        logger.info(f"FAISS 인덱스 저장 완료: {_INDEX_PATH}")
    except Exception as e:
        logger.warning(f"FAISS 인덱스 빌드 실패 (BM25 폴백 사용): {e}")


def load_index() -> bool:
    """저장된 BM25 인덱스를 메모리에 로드. 성공 여부 반환."""
    global _bm25, _chunks, _faiss_index
    try:
        if not _BM25_PATH.exists() or not _CHUNKS_PATH.exists():
            logger.warning(
                "인덱스 파일이 없습니다. "
                "`python -m app.rag_index`로 생성 후 서버를 재시작하세요.\n"
                f"  (찾는 경로: {_BM25_PATH})"
            )
            return False

        with open(_BM25_PATH, "rb") as f:
            _bm25 = pickle.load(f)
        with open(_CHUNKS_PATH, "rb") as f:
            _chunks = pickle.load(f)
        logger.info(f"BM25 인덱스 로드 완료: {len(_chunks)}개 청크")

        # 선택적 FAISS 로드
        if _USE_NEURAL and _INDEX_PATH.exists():
            try:
                import faiss
                _faiss_index = faiss.read_index(str(_INDEX_PATH))
                logger.info("FAISS 인덱스도 로드됨 (하이브리드 검색 활성화)")
            except Exception as e:
                logger.warning(f"FAISS 로드 실패, BM25만 사용: {e}")

        return True
    except Exception as e:
        logger.error(f"인덱스 로드 실패: {e}")
        return False


# ─────────────────────────────────────────────────────
# 검색
# ─────────────────────────────────────────────────────

def search(query: str, top_k: int = TOP_K, source_filter: Optional[str] = None) -> list:
    """
    query와 유사한 청크를 반환한다.

    Returns:
        [{"source_file": ..., "chunk_index": ..., "preview": ..., "score": ..., "text": ...}]
    """
    if _bm25 is None or not _chunks:
        logger.warning("BM25 인덱스가 로드되지 않았습니다.")
        return []

    tokens = _tokenize(query)
    scores = _bm25.get_scores(tokens)

    # source_filter가 있으면 해당 파일 점수를 1.5배 부스트
    if source_filter:
        for i, chunk in enumerate(_chunks):
            if source_filter in chunk["source_file"]:
                scores[i] *= 1.5

    # 점수 내림차순 정렬
    import numpy as np
    ranked = sorted(enumerate(scores), key=lambda x: x[1], reverse=True)

    results = []
    seen_keys: set = set()
    for idx, score in ranked:
        if score <= 0:
            continue
        chunk = _chunks[idx]
        key = f"{chunk['source_file']}_{chunk['chunk_index']}"
        if key in seen_keys:
            continue
        seen_keys.add(key)
        results.append({
            "source_file": chunk["source_file"],
            "chunk_index": chunk["chunk_index"],
            "preview": chunk["preview"],
            "score": float(score),
            "text": chunk["text"],
        })
        if len(results) >= top_k:
            break

    return results


# ─────────────────────────────────────────────────────
# 신경망 임베딩 (선택적)
# ─────────────────────────────────────────────────────

def _get_embedder():
    global _embedder_cache
    if _embedder_cache is None:
        from sentence_transformers import SentenceTransformer
        logger.info(f"임베딩 모델 로딩: {EMBEDDING_MODEL}")
        _embedder_cache = SentenceTransformer(EMBEDDING_MODEL)
    return _embedder_cache


# ─────────────────────────────────────────────────────
# CLI 진입점
# ─────────────────────────────────────────────────────

if __name__ == "__main__":
    import sys
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    force = "--force" in sys.argv or "-f" in sys.argv
    build_index(force=force)
    print("인덱스 생성 완료")

    # 간단한 검색 테스트
    load_index()
    test_queries = ["영수증 인증", "포인트 적립", "토미 성장"]
    print("\n[검색 테스트]")
    for q in test_queries:
        results = search(q, top_k=2)
        if results:
            print(f"  '{q}' → {results[0]['source_file']} (score={results[0]['score']:.2f})")
        else:
            print(f"  '{q}' → 결과 없음")
