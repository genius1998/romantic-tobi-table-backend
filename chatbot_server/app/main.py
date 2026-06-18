"""
토미도우미 FastAPI 챗봇 서버.

실행:
  uvicorn app.main:app --reload --port 8001
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, Header, HTTPException, Query, status
from fastapi.middleware.cors import CORSMiddleware

from app import chatbot_service, intent_classifier as ic, rag_index
from app.config import LLM_PROVIDER
from app.schemas import ChatRequest, ChatResponse

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
)
logger = logging.getLogger(__name__)


# ── 앱 시작 시 인덱스 로드 ────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    loaded = rag_index.load_index()
    if not loaded:
        logger.warning(
            "RAG 인덱스가 없습니다. "
            "`python -m app.rag_index` 를 먼저 실행하세요. "
            "인덱스 없이도 서버는 동작하지만 RAG 검색이 비활성화됩니다."
        )
    yield


app = FastAPI(
    title="토미도우미 챗봇 서버",
    description="토미키우기 앱의 AI 안내 챗봇 API",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── POST /chat ────────────────────────────────────────
@app.post("/chat", response_model=ChatResponse)
async def chat(
    request: ChatRequest,
    authorization: Optional[str] = Header(default=None),
):
    """
    토미도우미 챗봇 메인 엔드포인트.

    - Authorization 헤더의 Bearer 토큰을 Spring Boot Context API에 그대로 전달한다.
    - 토큰이 없으면 RAG 문서 검색 기반으로만 답변한다.
    """
    if not request.question or not request.question.strip():
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="질문을 입력해 주세요.",
        )

    # Bearer 토큰 추출
    auth_token: Optional[str] = None
    if authorization and authorization.lower().startswith("bearer "):
        auth_token = authorization[7:].strip()

    return await chatbot_service.handle(request, auth_token)


# ── GET /health ───────────────────────────────────────
@app.get("/health")
async def health():
    bm25_loaded = rag_index._bm25 is not None
    chunk_count = len(rag_index._chunks)
    faiss_loaded = rag_index._faiss_index is not None
    return {
        "status": "ok",
        "rag_index_loaded": bm25_loaded,
        "chunk_count": chunk_count,
        "search_backend": "BM25" + ("+FAISS" if faiss_loaded else ""),
        "llm_provider": LLM_PROVIDER,
    }


# ── GET /intents ──────────────────────────────────────
@app.get("/intents")
async def list_intents():
    """사용 가능한 intent 목록과 키워드 예시를 반환한다."""
    return {
        "intents": ic.ALL_INTENTS,
        "examples": {
            ic.APP_USAGE: ["앱 어떻게 사용해요?", "처음 써요"],
            ic.RECEIPT_UPLOAD_GUIDE: ["영수증 어떻게 찍어요?", "영수증 촬영 방법"],
            ic.RECEIPT_FAILURE_REASON: ["영수증 왜 실패했나요?", "인증 안 돼요"],
            ic.RECEIPT_DUPLICATE: ["같은 영수증 또 낼 수 있나요?", "중복 영수증"],
            ic.STORE_ELIGIBILITY: ["이 가게 적립 돼요?", "맘스터치 되나요?"],
            ic.STORE_RECOMMENDATION: ["가맹점 목록이 뭐예요?", "음식점 알려줘"],
            ic.POINT_RULE: ["포인트 어떻게 쌓이나요?", "적립 규칙"],
            ic.USER_POINT_STATUS: ["포인트 얼마나 있어요?", "내 포인트"],
            ic.CHARACTER_GROWTH: ["토미 어떻게 키워요?", "먹이 주기"],
            ic.NEXT_LEVEL: ["다음 레벨까지 얼마나요?", "레벨업 조건"],
        },
    }


# ── GET /debug/search ─────────────────────────────────
@app.get("/debug/search")
async def debug_search(query: str = Query(..., description="검색할 키워드")):
    """RAG 인덱스에서 query와 유사한 청크를 반환한다. (디버그용)"""
    results = rag_index.search(query, top_k=5)
    return {
        "query": query,
        "results": [
            {
                "source_file": r["source_file"],
                "chunk_index": r["chunk_index"],
                "score": round(r["score"], 4),
                "preview": r["preview"],
            }
            for r in results
        ],
    }
