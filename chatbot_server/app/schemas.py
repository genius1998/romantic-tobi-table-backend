from __future__ import annotations

from typing import Any, List, Optional
from pydantic import BaseModel


# ── 요청 ──────────────────────────────────────────────
class ClientContext(BaseModel):
    screen: Optional[str] = None
    extra: Optional[dict] = None


class ChatRequest(BaseModel):
    question: str
    receiptId: Optional[int] = None
    clientContext: Optional[ClientContext] = None


# ── 응답 부품 ─────────────────────────────────────────
class SourceDoc(BaseModel):
    sourceFile: str
    chunkIndex: int
    preview: str


class ChatAction(BaseModel):
    type: str          # OPEN_SCREEN | COPY_TEXT | EXTERNAL_LINK
    label: str
    target: str


# ── 최종 응답 ─────────────────────────────────────────
class ChatResponse(BaseModel):
    answer: str
    intent: str
    sources: List[SourceDoc] = []
    actions: List[ChatAction] = []


# ── Spring Boot Context 응답 스키마 (타입 힌트용) ──────
class PetContext(BaseModel):
    level: int
    exp: int
    nextLevelRequiredExp: int
    remainingExpToNextLevel: int


class MeContext(BaseModel):
    userId: int
    currentPoint: int
    pet: PetContext


class ReceiptContext(BaseModel):
    receiptId: int
    storeName: Optional[str] = None
    matchedStoreId: Optional[int] = None
    matchedStoreName: Optional[str] = None
    paymentAmount: Optional[int] = None
    paymentDate: Optional[str] = None
    verificationStatus: Optional[str] = None
    failureReason: Optional[str] = None
    matchScore: Optional[float] = None
    matchMethod: Optional[str] = None
    earnedPoint: Optional[int] = None
    createdAt: Optional[str] = None


class RecentReceiptsContext(BaseModel):
    receipts: List[ReceiptContext] = []


class StoreContext(BaseModel):
    storeId: int
    name: str
    address: Optional[str] = None
    district: Optional[str] = None
    primaryCategory: Optional[str] = None
    secondaryCategory: Optional[str] = None
    aliases: Optional[str] = None
    rewardEligible: Optional[bool] = None


class StoreSearchContext(BaseModel):
    totalResults: int
    stores: List[StoreContext] = []
