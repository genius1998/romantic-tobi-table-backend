"""
챗봇 전체 처리 흐름.

1. intent 분류
2. intent에 따라 Spring Boot Context API 호출
3. RAG 검색
4. 프롬프트 구성
5. LLM 답변 생성
6. actions 결정
7. 응답 반환
"""

from __future__ import annotations

import logging
import random
from typing import Optional

from app import intent_classifier as ic
from app import llm_client, prompt_builder, rag_index, spring_client
from app.schemas import (
    ChatAction,
    ChatRequest,
    ChatResponse,
    MeContext,
    ReceiptContext,
    RecentReceiptsContext,
    SourceDoc,
    StoreSearchContext,
)

logger = logging.getLogger(__name__)

# ── intent별 actions 정의 ─────────────────────────────
_INTENT_ACTIONS: dict[str, list[dict]] = {
    ic.RECEIPT_UPLOAD_GUIDE: [
        {"type": "OPEN_SCREEN", "label": "영수증 촬영하기", "target": "ReceiptUpload"},
    ],
    ic.RECEIPT_FAILURE_REASON: [
        {"type": "OPEN_SCREEN", "label": "다시 제출하기", "target": "ReceiptUpload"},
    ],
    ic.RECEIPT_DUPLICATE: [
        {"type": "OPEN_SCREEN", "label": "영수증 목록 보기", "target": "ReceiptHistory"},
    ],
    ic.STORE_ELIGIBILITY: [
        {"type": "OPEN_SCREEN", "label": "가맹점 검색하기", "target": "StoreSearch"},
    ],
    ic.STORE_RECOMMENDATION: [
        {"type": "OPEN_SCREEN", "label": "가맹점 검색하기", "target": "StoreSearch"},
    ],
    ic.NEXT_LEVEL: [
        {"type": "OPEN_SCREEN", "label": "토미 확인하기", "target": "PetHome"},
    ],
    ic.USER_POINT_STATUS: [
        {"type": "OPEN_SCREEN", "label": "리워드 보러가기", "target": "RewardShop"},
    ],
    ic.CHARACTER_GROWTH: [
        {"type": "OPEN_SCREEN", "label": "토미 확인하기", "target": "PetHome"},
    ],
    ic.POINT_RULE: [
        {"type": "OPEN_SCREEN", "label": "리워드 보러가기", "target": "RewardShop"},
    ],
}

# ── intent별 RAG 검색 소스 힌트 ───────────────────────
_INTENT_DOC_HINTS: dict[str, list[str]] = {
    ic.APP_USAGE:             ["app_guide.md"],
    ic.RECEIPT_UPLOAD_GUIDE:  ["app_guide.md", "receipt_policy.md"],
    ic.RECEIPT_FAILURE_REASON:["receipt_policy.md"],
    ic.RECEIPT_DUPLICATE:     ["receipt_policy.md"],
    ic.STORE_ELIGIBILITY:     ["store_search_schema.json"],
    ic.STORE_RECOMMENDATION:  ["store_search_schema.json"],
    ic.POINT_RULE:            ["reward_policy.md", "reward_policy.json"],
    ic.USER_POINT_STATUS:     ["reward_policy.md"],
    ic.CHARACTER_GROWTH:      ["character_growth.md", "character_growth_policy.json"],
    ic.NEXT_LEVEL:            ["character_growth.md", "character_growth_policy.json"],
    ic.UNKNOWN:               [],
}


async def handle(request: ChatRequest, auth_token: Optional[str]) -> ChatResponse:
    """챗봇 요청 전체 처리."""
    question = request.question.strip()
    intent = ic.classify(question)
    logger.info(f"intent={intent} | question={question[:60]}")

    # ── Context API 호출 ──────────────────────────────
    me_ctx: Optional[MeContext] = None
    receipts_ctx: Optional[RecentReceiptsContext] = None
    receipt_detail: Optional[ReceiptContext] = None
    stores_ctx: Optional[StoreSearchContext] = None
    api_error_msg: Optional[str] = None

    if auth_token:
        try:
            me_ctx, receipts_ctx, receipt_detail, stores_ctx = await _fetch_context(
                intent, request, auth_token
            )
        except (spring_client.SpringAuthError,
                spring_client.SpringNotFoundError,
                spring_client.SpringConnectionError) as e:
            api_error_msg = spring_client.user_message_for_error(e)
            logger.warning(f"Context API 오류: {e}")
        except Exception as e:
            api_error_msg = "서버 정보를 불러오는 중 오류가 발생했습니다."
            logger.error(f"Context API 예상치 못한 오류: {e}")

    # 인증 오류이면 바로 반환
    if isinstance(api_error_msg, str) and "로그인" in api_error_msg:
        return ChatResponse(
            answer=api_error_msg,
            intent=intent,
            sources=[],
            actions=[],
        )

    # ── RAG 검색 ──────────────────────────────────────
    rag_docs = _search_docs(intent, question)

    # ── 프롬프트 구성 ──────────────────────────────────
    system_prompt, user_prompt = prompt_builder.build_prompt(
        intent=intent,
        question=question,
        rag_docs=rag_docs,
        me_context=me_ctx,
        receipts_context=receipts_ctx,
        receipt_detail=receipt_detail,
        stores_context=stores_ctx,
    )

    # API 오류 안내를 프롬프트에 추가
    if api_error_msg:
        user_prompt += f"\n\n주의: 서버 데이터 조회 실패 — {api_error_msg}"

    # ── LLM 호출 ──────────────────────────────────────
    answer = await llm_client.generate(system_prompt, user_prompt)

    # ── 응답 구성 ──────────────────────────────────────
    sources = [
        SourceDoc(
            sourceFile=d["source_file"],
            chunkIndex=d["chunk_index"],
            preview=d["preview"],
        )
        for d in rag_docs
    ]
    actions = [ChatAction(**a) for a in _INTENT_ACTIONS.get(intent, [])]

    return ChatResponse(
        answer=answer,
        intent=intent,
        sources=sources,
        actions=actions,
    )


# ─────────────────────────────────────────────────────
# 내부 함수
# ─────────────────────────────────────────────────────

async def _fetch_context(
    intent: str,
    request: ChatRequest,
    auth_token: str,
) -> tuple[
    Optional[MeContext],
    Optional[RecentReceiptsContext],
    Optional[ReceiptContext],
    Optional[StoreSearchContext],
]:
    me_ctx = None
    receipts_ctx = None
    receipt_detail = None
    stores_ctx = None

    if intent in (ic.USER_POINT_STATUS, ic.NEXT_LEVEL, ic.CHARACTER_GROWTH):
        me_ctx = await spring_client.get_me_context(auth_token)

    elif intent == ic.RECEIPT_FAILURE_REASON:
        if request.receiptId:
            receipt_detail = await spring_client.get_receipt_detail(auth_token, request.receiptId)
        else:
            receipts_ctx = await spring_client.get_recent_receipts(auth_token, limit=3)

    elif intent == ic.RECEIPT_DUPLICATE:
        receipts_ctx = await spring_client.get_recent_receipts(auth_token, limit=5)

    elif intent == ic.STORE_ELIGIBILITY:
        keyword = ic.extract_store_keyword(request.question)
        if keyword:
            stores_ctx = await spring_client.search_stores(auth_token, keyword=keyword, limit=5)

    elif intent == ic.STORE_RECOMMENDATION:
        stores_ctx = await _build_store_recommendation_context(request, auth_token)

    return me_ctx, receipts_ctx, receipt_detail, stores_ctx


async def _build_store_recommendation_context(
    request: ChatRequest,
    auth_token: str,
) -> StoreSearchContext:
    query = ic.extract_store_recommendation_query(request.question)

    if query.is_random:
        all_stores = await spring_client.search_stores(auth_token, limit=60)
        return _sample_store_context(all_stores, size=5)

    contexts: list[StoreSearchContext] = []

    if query.keyword:
        contexts.append(
            await spring_client.search_stores(
                auth_token,
                keyword=query.keyword,
                district=query.district,
                limit=20,
            )
        )

    if query.location_keyword:
        contexts.append(
            await spring_client.search_stores(
                auth_token,
                keyword=query.location_keyword,
                district=query.district,
                limit=30,
            )
        )

    if query.category or query.district:
        contexts.append(
            await spring_client.search_stores(
                auth_token,
                category=query.category,
                district=query.district,
                limit=30,
            )
        )

    if query.category_keyword:
        contexts.append(
            await spring_client.search_stores(
                auth_token,
                keyword=query.category_keyword,
                district=query.district,
                limit=30,
            )
        )

    merged = _sample_store_context(_merge_store_contexts(*contexts), size=5)

    if merged.totalResults == 0 and (query.district or query.location_keyword) and not query.keyword:
        district_fallback = await spring_client.search_stores(
            auth_token,
            keyword=query.location_keyword or query.district,
            limit=20,
        )
        merged = _sample_store_context(
            _merge_store_contexts(merged, district_fallback),
            size=5,
        )

    return merged


def _merge_store_contexts(*contexts: Optional[StoreSearchContext]) -> StoreSearchContext:
    stores = []
    seen_store_ids: set[int] = set()

    for context in contexts:
        if not context:
            continue
        for store in context.stores:
            if store.storeId in seen_store_ids:
                continue
            seen_store_ids.add(store.storeId)
            stores.append(store)

    return StoreSearchContext(totalResults=len(stores), stores=stores[:10])


def _sample_store_context(context: StoreSearchContext, size: int) -> StoreSearchContext:
    if len(context.stores) <= size:
        return context

    sampled = random.sample(context.stores, k=size)
    return StoreSearchContext(totalResults=context.totalResults, stores=sampled)


def _search_docs(intent: str, question: str) -> list:
    """intent 힌트 기반 RAG 검색."""
    hints = _INTENT_DOC_HINTS.get(intent, [])

    if not hints:
        # UNKNOWN이면 질문 그대로 전체 검색
        return rag_index.search(question, top_k=3)

    results: list[dict] = []
    seen_keys: set[str] = set()

    # 힌트 파일에서 먼저 검색
    for hint in hints:
        docs = rag_index.search(question, top_k=3, source_filter=hint)
        for d in docs:
            key = f"{d['source_file']}_{d['chunk_index']}"
            if key not in seen_keys:
                seen_keys.add(key)
                results.append(d)

    # 힌트 결과가 부족하면 전체 검색으로 보완
    if len(results) < 3:
        fallback = rag_index.search(question, top_k=5)
        for d in fallback:
            key = f"{d['source_file']}_{d['chunk_index']}"
            if key not in seen_keys:
                seen_keys.add(key)
                results.append(d)
            if len(results) >= 5:
                break

    return results[:5]
