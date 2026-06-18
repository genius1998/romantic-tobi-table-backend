"""
Spring Boot Context API 클라이언트.

FastAPI /chat 요청의 Authorization Bearer 토큰을 그대로 Spring Boot에 전달한다.
Spring Boot DB에 직접 접근하지 않는다.
"""

from __future__ import annotations

import logging
from typing import Optional

import httpx

from app.config import SPRING_BASE_URL
from app.schemas import (
    MeContext,
    RecentReceiptsContext,
    ReceiptContext,
    StoreSearchContext,
)

logger = logging.getLogger(__name__)

_TIMEOUT = httpx.Timeout(10.0, connect=5.0)


# ── 공통 예외 ──────────────────────────────────────────
class SpringAuthError(Exception):
    """401 / 403 — 인증 필요"""


class SpringNotFoundError(Exception):
    """404 — 리소스 없음"""


class SpringConnectionError(Exception):
    """Spring Boot 서버 연결 실패"""


# ── 내부 헬퍼 ─────────────────────────────────────────

def _headers(auth_token: str) -> dict:
    token = auth_token[7:].strip() if auth_token.lower().startswith("bearer ") else auth_token.strip()
    return {"Authorization": f"Bearer {token}"}


async def _get(path: str, auth_token: str, params: Optional[dict] = None) -> dict:
    url = f"{SPRING_BASE_URL}{path}"
    try:
        async with httpx.AsyncClient(timeout=_TIMEOUT) as client:
            resp = await client.get(url, headers=_headers(auth_token), params=params or {})
    except (httpx.ConnectError, httpx.TimeoutException) as e:
        logger.error(f"Spring Boot 연결 실패: {url} — {e}")
        raise SpringConnectionError(str(e))

    if resp.status_code in (401, 403):
        raise SpringAuthError(f"Spring Boot 인증 오류 ({resp.status_code})")
    if resp.status_code == 404:
        raise SpringNotFoundError(f"리소스를 찾을 수 없습니다: {path}")
    if resp.status_code >= 400:
        logger.warning(f"Spring Boot 오류 응답: {resp.status_code} {url}")
        resp.raise_for_status()

    return resp.json()


# ── Public API ────────────────────────────────────────

async def get_me_context(auth_token: str) -> MeContext:
    """현재 사용자 포인트, 펫 레벨/EXP, 다음 레벨까지 남은 EXP."""
    data = await _get("/api/chatbot/context/me", auth_token)
    return MeContext(**data)


async def get_recent_receipts(auth_token: str, limit: int = 5) -> RecentReceiptsContext:
    """최근 영수증 인증 기록."""
    data = await _get(
        "/api/chatbot/context/receipts/recent",
        auth_token,
        params={"limit": limit},
    )
    return RecentReceiptsContext(**data)


async def get_receipt_detail(auth_token: str, receipt_id: int) -> ReceiptContext:
    """특정 영수증 상세 (본인 것만)."""
    data = await _get(f"/api/chatbot/context/receipts/{receipt_id}", auth_token)
    return ReceiptContext(**data)


async def search_stores(
    auth_token: str,
    keyword: Optional[str] = None,
    category: Optional[str] = None,
    district: Optional[str] = None,
    limit: int = 10,
) -> StoreSearchContext:
    """가맹점 검색."""
    params: dict = {"limit": limit}
    if keyword:
        params["keyword"] = keyword
    if category:
        params["category"] = category
    if district:
        params["district"] = district
    data = await _get("/api/chatbot/context/stores/search", auth_token, params=params)
    return StoreSearchContext(**data)


# ── 에러 → 사용자 메시지 변환 ─────────────────────────

def user_message_for_error(e: Exception) -> str:
    if isinstance(e, SpringAuthError):
        return "로그인이 필요합니다. 앱에서 로그인 후 다시 시도해 주세요."
    if isinstance(e, SpringNotFoundError):
        return "해당 정보를 찾을 수 없습니다."
    if isinstance(e, SpringConnectionError):
        return "현재 서버 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요."
    return "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
