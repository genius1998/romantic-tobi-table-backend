"""
Rule-based intent 분류기.
LLM 없이 키워드 매칭으로 intent를 결정한다.
나중에 LLM 기반 분류로 교체하려면 classify() 함수 내부만 변경하면 된다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional

# ── Intent 상수 ───────────────────────────────────────
APP_USAGE = "APP_USAGE"
RECEIPT_UPLOAD_GUIDE = "RECEIPT_UPLOAD_GUIDE"
RECEIPT_FAILURE_REASON = "RECEIPT_FAILURE_REASON"
RECEIPT_DUPLICATE = "RECEIPT_DUPLICATE"
STORE_ELIGIBILITY = "STORE_ELIGIBILITY"
STORE_RECOMMENDATION = "STORE_RECOMMENDATION"
POINT_RULE = "POINT_RULE"
USER_POINT_STATUS = "USER_POINT_STATUS"
CHARACTER_GROWTH = "CHARACTER_GROWTH"
NEXT_LEVEL = "NEXT_LEVEL"
UNKNOWN = "UNKNOWN"

ALL_INTENTS = [
    APP_USAGE, RECEIPT_UPLOAD_GUIDE, RECEIPT_FAILURE_REASON, RECEIPT_DUPLICATE,
    STORE_ELIGIBILITY, STORE_RECOMMENDATION, POINT_RULE, USER_POINT_STATUS,
    CHARACTER_GROWTH, NEXT_LEVEL, UNKNOWN,
]


@dataclass(frozen=True)
class StoreRecommendationQuery:
    keyword: Optional[str] = None
    category: Optional[str] = None
    category_keyword: Optional[str] = None
    district: Optional[str] = None
    location_keyword: Optional[str] = None

    @property
    def is_random(self) -> bool:
        return not any((
            self.keyword,
            self.category,
            self.category_keyword,
            self.district,
            self.location_keyword,
        ))


_STORE_CATEGORY_RULES: list[tuple[str, tuple[str, ...]]] = [
    ("한식", ("한식", "백반", "국밥", "찌개", "삼겹살", "불고기")),
    ("중국식", ("중국식", "중식", "중국집", "짜장", "짬뽕", "마라")),
    ("일식", ("일식", "초밥", "스시", "라멘", "우동")),
    ("경양식", ("경양식", "양식", "파스타", "스테이크", "브런치")),
    ("분식", ("분식", "떡볶이", "김밥")),
    ("패스트푸드", ("패스트푸드", "햄버거", "버거")),
]

_STORE_CATEGORY_KEYWORDS = (
    "카페", "커피", "디저트", "베이커리", "빵집",
    "치킨", "피자", "술집", "포차", "막창",
)

_STORE_REGION_KEYWORDS = (
    "구평", "광평", "고아", "공단", "구미", "남통", "도량", "문성", "봉곡",
    "비산", "사곡", "산동", "상모", "선산", "송정", "신평", "양포", "옥계",
    "오태", "원평", "인의", "인동", "임수", "진평", "형곡", "황상", "거의",
)

_STORE_LOCATION_ALIASES: tuple[tuple[str, dict[str, str]], ...] = (
    ("금오공대", {"district": "대학로", "location_keyword": "대학로"}),
    ("금오대학교", {"district": "대학로", "location_keyword": "대학로"}),
    ("대학로", {"district": "대학로", "location_keyword": "대학로"}),
    ("금오산", {"location_keyword": "금오산로"}),
)

_STORE_GENERIC_TERMS = {
    "가게", "식당", "음식점", "카페", "맛집", "가맹점", "추천", "추천해줘", "추천해",
    "근처", "주변", "인근",
}

# ── 키워드 규칙 (우선순위 높은 것이 앞에) ─────────────
_RULES: list = [
    # 중복 영수증 — RECEIPT_FAILURE_REASON 보다 앞에
    (RECEIPT_DUPLICATE, [
        "중복", "같은 영수증", "이미 인증", "이미 제출", "다시 제출", "두 번", "또 제출",
    ]),
    # 영수증 실패 원인
    (RECEIPT_FAILURE_REASON, [
        "왜 실패", "실패 이유", "실패 원인", "인증 실패", "안 됐", "안됐", "왜 안",
        "인증이 왜", "포인트가 안", "적립이 안", "인증 안", "왜 못", "오류",
        "실패했", "안되", "안 돼", "왜 안 됐", "왜 안됐",
    ]),
    # 현재 포인트 잔액 — POINT_RULE보다 앞에 (포인트 얼마나 충돌 방지)
    (USER_POINT_STATUS, [
        "포인트 얼마", "내 포인트", "지금 포인트", "포인트 잔액", "포인트 확인",
        "포인트 몇", "포인트가 얼마", "포인트 남은",
        "얼마나 있", "포인트 있어",
    ]),
    # 포인트 적립 규칙 — RECEIPT_UPLOAD_GUIDE보다 앞에 (포인트 사용/어떻게 충돌 방지)
    (POINT_RULE, [
        "포인트 어떻게", "포인트 쌓이", "포인트 적립", "포인트 사용", "포인트 쓰",
        "포인트 규칙", "얼마나 적립", "포인트 얼마나",
        "기프티콘", "리워드", "쿠폰", "교환",
    ]),
    # 영수증 촬영/제출 방법
    (RECEIPT_UPLOAD_GUIDE, [
        "어떻게 찍", "촬영", "영수증 올리", "영수증 업로드", "사진 찍", "사진 어떻게",
        "영수증 어떻게", "영수증 인증 방법", "영수증 제출", "어떻게 올려",
        "어떻게 하나요", "어떻게 합니까", "인증 방법", "인증은 어떻게",
        "영수증 사진", "영수증 찍", "어떻게 인증",
    ]),
    # 가게 적립 가능 여부
    (STORE_ELIGIBILITY, [
        "적립 되", "적립되", "적립돼", "포인트 되", "여기서 되", "이 가게", "이가게",
        "가맹점인", "가맹점 인지", "이 식당", "이 카페", "이곳",
        "되나요", "돼요", "가능한가", "적립 가능", "포인트 적립 되", "포인트 적립 돼",
    ]),
    # 가게 추천/목록
    (STORE_RECOMMENDATION, [
        "가맹점", "어떤 가게", "가게 목록", "가게 알려", "음식점 알려", "카페 알려",
        "어디서", "어느 가게", "참여 가게", "어느 식당", "생활업종",
        "추천", "추천해", "추천해줘", "맛집",
    ]),
    # 다음 레벨 — CHARACTER_GROWTH 보다 앞에
    (NEXT_LEVEL, [
        "다음 레벨", "레벨업", "레벨 올리", "얼마나 남", "남은 exp", "남은 경험치",
        "레벨까지", "레벨 올라", "몇 포인트", "몇 exp", "언제 레벨",
    ]),
    # 캐릭터 성장 일반
    (CHARACTER_GROWTH, [
        "토미 성장", "캐릭터 성장", "어떻게 키워", "토미 키우", "토미 레벨", "토미 경험치",
        "토미 exp", "먹이", "레벨 조건", "성장 조건", "의상",
    ]),
    # 앱 일반 사용법
    (APP_USAGE, [
        "어떻게 사용", "앱 사용", "사용 방법", "앱 설명", "토미키우기가", "어떻게 시작",
        "처음", "회원가입", "로그인", "출석", "인사",
    ]),
]


def classify(question: str) -> str:
    """사용자 질문에서 intent를 결정한다."""
    q = question.lower().strip()

    for intent, keywords in _RULES:
        for kw in keywords:
            if kw in q:
                return intent

    return UNKNOWN


def extract_store_keyword(question: str) -> Optional[str]:
    """
    가게 이름 후보를 질문에서 추출한다.
    예: "맘스터치 적립 되나요?" → "맘스터치"
    """
    # 따옴표로 감싼 경우: '"맘스터치"', "'편의점'"
    quoted = re.findall(r'["\'「」]([\w\s가-힣]{1,20})["\' 」]', question)
    if quoted:
        return quoted[0].strip()

    # 고유명사 패턴: 한글/영문 2~10자 뒤에 가게/식당/카페/마트 등이 붙은 경우
    patterns = [
        r'([\w가-힣]{2,10}(?:카페|식당|마트|편의점|분식|치킨|피자|버거|커피|점|가게))',
    ]
    for p in patterns:
        m = re.search(p, question)
        if m:
            return m.group(1).strip()

    # 키워드 앞 명사 추출 (간단 휴리스틱)
    trigger_words = [
        "에서", "가", "이", "도", "은", "는", "적립", "되", "인지", "포인트",
        "추천", "추천해", "추천해줘", "알려", "목록",
    ]
    for tw in trigger_words:
        idx = question.find(tw)
        if idx > 1:
            candidate = question[:idx].strip().split()
            if candidate:
                return candidate[-1]

    return None


def extract_store_recommendation_query(question: str) -> StoreRecommendationQuery:
    category = None
    category_keyword = None
    district = None
    location_keyword = None

    for canonical, aliases in _STORE_CATEGORY_RULES:
        matched_alias = next((alias for alias in aliases if alias in question), None)
        if matched_alias:
            category = canonical
            if matched_alias != canonical:
                category_keyword = matched_alias
            break

    if category_keyword is None:
        category_keyword = next((alias for alias in _STORE_CATEGORY_KEYWORDS if alias in question), None)

    district = next(
        (
            region for region in _STORE_REGION_KEYWORDS
            if region != "구미" and region in question
        ),
        None,
    )

    for alias, resolved in _STORE_LOCATION_ALIASES:
        if alias not in question:
            continue
        district = resolved.get("district", district)
        location_keyword = resolved.get("location_keyword", location_keyword)
        break

    if location_keyword is None:
        nearby_match = re.search(r'([가-힣A-Za-z0-9\s]{2,20})\s*(근처|주변|인근)', question)
        if nearby_match:
            candidate = nearby_match.group(1).strip()
            for alias, resolved in _STORE_LOCATION_ALIASES:
                if alias in candidate:
                    district = resolved.get("district", district)
                    location_keyword = resolved.get("location_keyword", location_keyword)
                    break
            else:
                if candidate not in _STORE_GENERIC_TERMS:
                    location_keyword = candidate

    keyword = extract_store_keyword(question)
    if keyword:
        normalized = keyword.strip()
        if (
            normalized in _STORE_GENERIC_TERMS
            or normalized == category
            or normalized == category_keyword
            or normalized in _STORE_REGION_KEYWORDS
            or normalized == location_keyword
        ):
            keyword = None

    return StoreRecommendationQuery(
        keyword=keyword,
        category=category,
        category_keyword=category_keyword,
        district=district,
        location_keyword=location_keyword,
    )
