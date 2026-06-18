"""
프롬프트 구성 모듈.

build_prompt(intent, question, rag_docs, context_data) → (system_prompt, user_prompt)
"""

from __future__ import annotations

import json
from typing import Any, Optional, Tuple

from app.schemas import (
    MeContext,
    ReceiptContext,
    RecentReceiptsContext,
    StoreSearchContext,
)

# ── 시스템 프롬프트 ────────────────────────────────────
SYSTEM_PROMPT = """\
너는 토미키우기 앱의 AI 안내 챗봇 "토미도우미"다.
토미키우기는 구미시 소상공인 영수증을 OCR로 인증하여 포인트를 적립하고, 그 포인트로 캐릭터 토미를 성장시키는 리워드 앱이다.

[역할]
- 사용자의 영수증 인증, 포인트, 가맹점, 토미 성장, 앱 사용법을 도와준다.
- 반드시 아래 [문서 검색 결과]와 [사용자 데이터]에 근거해서만 답변한다.
- 근거가 없으면 "현재 정보만으로는 확인하기 어렵습니다."라고 한다.
- 절대 추측하지 않는다.

[답변 원칙]
- 사용자에게 친절하고 짧게 (3~5문장 내외) 답한다.
- 실패 사유가 있으면 다음에 무엇을 하면 좋은지 구체적으로 안내한다.
- 영수증 이미지 URL, 민감한 OCR 원문 전체, 개인정보는 절대 언급하지 않는다.
- 한국어로 답한다.
"""

# ── 실패 사유 한국어 변환 ──────────────────────────────
_FAILURE_REASON_KO = {
    "NO_CANDIDATE": "DB에서 일치하는 가게를 찾지 못했습니다",
    "LOW_CONFIDENCE": "가게명 또는 주소 일치도가 낮아 자동 매칭이 되지 않았습니다",
    "STORE_NAME_MISSING": "영수증에서 가게명을 인식하지 못했습니다",
    "DUPLICATE_RECEIPT": "이미 인증된 영수증입니다",
    "OCR_FAILED": "영수증 이미지를 인식하지 못했습니다",
    "NOT_REWARD_ELIGIBLE": "해당 가게는 포인트 적립 불가 가맹점입니다",
    "UNKNOWN": "알 수 없는 오류가 발생했습니다",
}

_VERIFICATION_STATUS_KO = {
    "SUCCESS": "인증 성공",
    "FAILED": "인증 실패",
    "DUPLICATE": "중복 영수증",
    "PENDING": "처리 중",
}


def _format_receipt(r: ReceiptContext) -> str:
    status_ko = _VERIFICATION_STATUS_KO.get(r.verificationStatus or "", r.verificationStatus or "")
    reason_ko = _FAILURE_REASON_KO.get(r.failureReason or "", "")
    lines = [
        f"- 영수증 ID: {r.receiptId}",
        f"  가게명(OCR): {r.storeName or '인식 불가'}",
        f"  결제금액: {r.paymentAmount:,}원" if r.paymentAmount else "  결제금액: 미확인",
        f"  결제일: {r.paymentDate or '미확인'}",
        f"  인증 상태: {status_ko}",
    ]
    if reason_ko:
        lines.append(f"  실패 사유: {reason_ko}")
    if r.matchScore is not None:
        lines.append(f"  매칭 점수: {r.matchScore:.2f} ({r.matchMethod or '-'})")
    if r.earnedPoint is not None and r.earnedPoint > 0:
        lines.append(f"  적립 포인트: {r.earnedPoint}P")
    return "\n".join(lines)


def _format_store(s) -> str:
    eligible = "✅ 적립 가능" if s.rewardEligible else "❌ 적립 불가"
    return (
        f"- {s.name} ({s.primaryCategory or '업종미상'}) | {s.district or ''} | {s.address or ''} | {eligible}"
    )


# ─────────────────────────────────────────────────────
# 메인 빌더
# ─────────────────────────────────────────────────────

def build_prompt(
    intent: str,
    question: str,
    rag_docs: list,
    me_context: Optional[MeContext] = None,
    receipts_context: Optional[RecentReceiptsContext] = None,
    receipt_detail: Optional[ReceiptContext] = None,
    stores_context: Optional[StoreSearchContext] = None,
) -> Tuple[str, str]:
    """(system_prompt, user_prompt) 반환."""

    sections: list[str] = []

    # ── RAG 검색 결과 ──────────────────────────────────
    if rag_docs:
        doc_text = "\n\n---\n\n".join(
            f"[출처: {d['source_file']} / chunk {d['chunk_index']}]\n{d['text'][:600]}"
            for d in rag_docs
        )
        sections.append(f"[문서 검색 결과]\n{doc_text}")

    # ── 사용자 데이터 ──────────────────────────────────
    if me_context:
        pet = me_context.pet
        sections.append(
            f"[사용자 데이터]\n"
            f"현재 포인트: {me_context.currentPoint:,}P\n"
            f"토미 레벨: {pet.level}단계 / EXP: {pet.exp}\n"
            f"다음 레벨까지 필요 EXP: {pet.nextLevelRequiredExp} (남은 EXP: {pet.remainingExpToNextLevel})"
        )

    if receipt_detail:
        sections.append(f"[영수증 상세]\n{_format_receipt(receipt_detail)}")

    if receipts_context and receipts_context.receipts:
        receipt_lines = "\n".join(_format_receipt(r) for r in receipts_context.receipts)
        sections.append(f"[최근 영수증 목록]\n{receipt_lines}")

    if stores_context:
        if stores_context.stores:
            store_lines = "\n".join(_format_store(s) for s in stores_context.stores[:10])
            sections.append(f"[가맹점 검색 결과] (총 {stores_context.totalResults}건)\n{store_lines}")
        else:
            sections.append("[가맹점 검색 결과]\n검색된 가맹점이 없습니다.")

    if intent == "STORE_RECOMMENDATION":
        if stores_context and stores_context.stores:
            sections.append(
                "[추천 응답 가이드]\n"
                "가맹점 검색 결과 안에서 2~3곳만 골라 추천하고, 질문에 지역이나 카테고리 조건이 있으면 그 기준에 맞는 이유를 한 줄씩 덧붙이세요.\n"
                "질문에 별도 조건이 없으면 구미시 내 랜덤 추천 후보로 자연스럽게 소개하세요."
            )
        else:
            sections.append(
                "[추천 응답 가이드]\n"
                "조건에 맞는 추천 결과가 없으면 가까운 동네명, 업종, 또는 정확한 가게 종류를 조금 더 구체적으로 알려 달라고 안내하세요."
            )

    context_block = "\n\n".join(sections) if sections else "(제공된 데이터 없음)"

    user_prompt = f"""\
{context_block}

---

사용자 질문: {question}

위 정보를 바탕으로 답변해줘. 근거가 없는 내용은 추측하지 마.
"""
    return SYSTEM_PROMPT, user_prompt
