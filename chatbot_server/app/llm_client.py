"""
LLM 클라이언트.

LLM_PROVIDER 환경변수로 사용할 LLM을 선택한다.
  local     — 로컬 LLM API (기본값, 외부 API 키 불필요)
  anthropic — Anthropic Claude API
  openai    — OpenAI API
"""

from __future__ import annotations

import logging
from typing import Optional

from app.config import (
    ANTHROPIC_API_KEY,
    ANTHROPIC_MODEL,
    LLM_PROVIDER,
    LOCAL_LLM_REFERER,
    LOCAL_LLM_TIMEOUT,
    LOCAL_LLM_URL,
    LOCAL_LLM_USER_AGENT,
    OPENAI_API_KEY,
    OPENAI_MODEL,
)

logger = logging.getLogger(__name__)

MAX_TOKENS = 800


async def generate(
    system_prompt: str,
    user_prompt: str,
    max_tokens: int = MAX_TOKENS,
) -> str:
    """
    LLM_PROVIDER에 따라 로컬 LLM, Claude, 또는 OpenAI를 호출한다.
    실패 시 안전한 오류 메시지를 반환한다.
    """
    provider = LLM_PROVIDER.lower()
    try:
        if provider == "local":
            prompt = _merge_prompts(system_prompt, user_prompt)
            return await _call_local_llm(prompt)
        elif provider == "anthropic":
            return await _call_anthropic(system_prompt, user_prompt, max_tokens)
        elif provider == "openai":
            return await _call_openai(system_prompt, user_prompt, max_tokens)
        else:
            logger.error(f"지원하지 않는 LLM_PROVIDER: {provider}")
            return "현재 챗봇 설정에 문제가 있습니다. 관리자에게 문의해 주세요."
    except _LocalLLMConnectionError:
        return "현재 AI 답변 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."
    except _LocalLLMTimeoutError:
        return "AI 답변 생성 시간이 초과되었습니다. 질문을 조금 짧게 다시 입력해 주세요."
    except _LocalLLMParseError:
        return "AI 답변 형식을 해석하지 못했습니다. 서버 응답 형식을 확인해 주세요."
    except Exception as e:
        logger.error(f"LLM 호출 실패 ({provider}): {e}")
        return "답변을 생성하는 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."


# ─────────────────────────────────────────────────────
# 로컬 LLM 전용 예외
# ─────────────────────────────────────────────────────

class _LocalLLMConnectionError(Exception):
    pass

class _LocalLLMTimeoutError(Exception):
    pass

class _LocalLLMParseError(Exception):
    pass


# ─────────────────────────────────────────────────────
# 로컬 LLM
# ─────────────────────────────────────────────────────

def _merge_prompts(system_prompt: str, user_prompt: str) -> str:
    """system_prompt와 user_prompt를 로컬 LLM용 단일 문자열로 합친다."""
    return (
        f"[역할 및 규칙]\n{system_prompt.strip()}\n\n"
        f"[컨텍스트 및 질문]\n{user_prompt.strip()}\n\n"
        "[출력 지시]\n사용자에게 보여줄 최종 답변만 작성해라."
    )


async def _call_local_llm(prompt: str) -> str:
    import httpx

    headers = {
        "Content-Type": "application/json",
        "User-Agent": LOCAL_LLM_USER_AGENT,
        "Referer": LOCAL_LLM_REFERER,
    }
    payload = {"message": prompt}

    try:
        async with httpx.AsyncClient(timeout=LOCAL_LLM_TIMEOUT) as client:
            resp = await client.post(LOCAL_LLM_URL, json=payload, headers=headers)
    except httpx.ConnectError as e:
        logger.error(f"로컬 LLM 연결 실패: {LOCAL_LLM_URL} — {e}")
        raise _LocalLLMConnectionError(str(e))
    except httpx.TimeoutException as e:
        logger.error(f"로컬 LLM 타임아웃: {LOCAL_LLM_URL} — {e}")
        raise _LocalLLMTimeoutError(str(e))

    if resp.status_code >= 400:
        logger.error(f"로컬 LLM HTTP 오류: {resp.status_code} / 응답: {resp.text[:200]}")
        raise _LocalLLMConnectionError(f"HTTP {resp.status_code}")

    try:
        data = resp.json()
    except Exception as e:
        logger.error(f"로컬 LLM 응답 JSON 파싱 실패: {resp.text[:200]}")
        raise _LocalLLMParseError(str(e))

    # 응답에서 content 추출 (우선순위 순)
    content = (
        (data.get("message") or {}).get("content")
        or data.get("content")
        or data.get("response")
        or data.get("answer")
    )
    if not content:
        logger.error(f"로컬 LLM 응답에서 content를 찾을 수 없음: {str(data)[:200]}")
        raise _LocalLLMParseError("content 필드 없음")

    return str(content).strip()


# ─────────────────────────────────────────────────────
# Anthropic
# ─────────────────────────────────────────────────────

async def _call_anthropic(system_prompt: str, user_prompt: str, max_tokens: int) -> str:
    if not ANTHROPIC_API_KEY:
        raise ValueError("ANTHROPIC_API_KEY가 설정되지 않았습니다.")

    import anthropic

    client = anthropic.AsyncAnthropic(api_key=ANTHROPIC_API_KEY)
    message = await client.messages.create(
        model=ANTHROPIC_MODEL,
        max_tokens=max_tokens,
        system=system_prompt,
        messages=[{"role": "user", "content": user_prompt}],
    )
    return message.content[0].text.strip()


# ─────────────────────────────────────────────────────
# OpenAI
# ─────────────────────────────────────────────────────

async def _call_openai(system_prompt: str, user_prompt: str, max_tokens: int) -> str:
    if not OPENAI_API_KEY:
        raise ValueError("OPENAI_API_KEY가 설정되지 않았습니다.")

    from openai import AsyncOpenAI

    client = AsyncOpenAI(api_key=OPENAI_API_KEY)
    # o-series 및 일부 최신 모델은 max_tokens 대신 max_completion_tokens 사용
    params = dict(
        model=OPENAI_MODEL,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    )
    try:
        response = await client.chat.completions.create(
            **params, max_completion_tokens=max_tokens
        )
    except Exception as e:
        if "max_completion_tokens" in str(e):
            # 구형 모델 폴백: max_tokens 파라미터 사용
            response = await client.chat.completions.create(
                **params, max_tokens=max_tokens
            )
        else:
            raise
    return response.choices[0].message.content.strip()
