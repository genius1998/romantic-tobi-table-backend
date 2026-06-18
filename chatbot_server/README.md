# 토미도우미 챗봇 서버

토미키우기 앱의 AI 안내 챗봇 **"토미도우미"** FastAPI 서버.

---

## 목적

- 사용자의 영수증 인증 실패 원인, 포인트/레벨 현황, 가맹점 안내, 앱 사용법 질문에 답변한다.
- Spring Boot DB에 직접 접근하지 않고 Spring Boot Context API를 호출하여 사용자별 동적 데이터를 가져온다.
- 앱 정책/FAQ/가이드는 `../chatbot_data/docs` 와 `../chatbot_data/json` 문서를 RAG 검색하여 활용한다.

---

## 아키텍처

```
React Native 앱
    │  POST /chat  (Bearer JWT)
    ▼
FastAPI 챗봇 서버 (포트 8001)
    ├── intent_classifier.py   — 키워드 기반 intent 분류 (10종)
    ├── spring_client.py       — Spring Boot Context API 호출 (JWT 전달)
    ├── rag_index.py           — BM25 문서 검색 (FAISS 선택적)
    ├── prompt_builder.py      — 프롬프트 구성
    └── llm_client.py          — LLM 호출 (openai 기본 / local / anthropic)
    │
    ▼
Spring Boot 서버 (포트 8080)
    └── GET /api/chatbot/context/**  (JWT 인증 필요)
         ├── /me                       — 포인트, 펫 레벨/EXP
         ├── /receipts/recent          — 최근 영수증 목록
         ├── /receipts/{id}            — 특정 영수증 상세
         └── /stores/search            — 가맹점 검색
```

---

## 설치

### 사전 요구사항

- Python 3.8+
- (선택) conda 또는 venv

```bash
cd chatbot_server
python -m venv .venv
# Windows
.venv\Scripts\activate
# macOS/Linux
source .venv/bin/activate

pip install -r requirements.txt
```

---

## 환경 설정

```bash
cp .env.example .env
# .env 파일을 열어 필요한 항목 설정
```

### 기본 실행: OpenAI gpt-4.1-mini (권장)

```env
LLM_PROVIDER=openai
OPENAI_API_KEY=sk-proj-...
OPENAI_MODEL=gpt-4.1-mini
```

> `gpt-4.1-mini`는 대화 최적화 모델로 챗봇 응답 품질과 속도가 좋습니다.  
> reasoning 모델(`gpt-5-nano` 등)은 내부 추론 토큰 소모로 응답이 비어 반환될 수 있으니 사용하지 마세요.

### 선택 옵션: 로컬 LLM (timeout 이슈 있음)

```env
LLM_PROVIDER=local
LOCAL_LLM_URL=http://169.213.5.157:3000/api/chat
LOCAL_LLM_TIMEOUT=120
```

### 선택 옵션: Anthropic

```env
LLM_PROVIDER=anthropic
ANTHROPIC_API_KEY=sk-ant-...
```

| 변수 | 설명 | 기본값 |
|---|---|---|
| `SPRING_BASE_URL` | Spring Boot 서버 주소 | `http://localhost:8080` |
| `LLM_PROVIDER` | `local` / `anthropic` / `openai` | `local` |
| `LOCAL_LLM_URL` | 로컬 LLM API 엔드포인트 | `http://169.213.5.157:3000/api/chat` |
| `LOCAL_LLM_MODEL` | 로컬 LLM 모델명 (참고용) | `gpt-oss:20b` |
| `LOCAL_LLM_TIMEOUT` | 로컬 LLM 응답 대기 시간(초) | `120` |
| `LOCAL_LLM_REFERER` | 로컬 LLM Referer 헤더 | `http://169.213.5.157:3000/` |
| `LOCAL_LLM_USER_AGENT` | 로컬 LLM User-Agent 헤더 | `Mozilla/5.0` |
| `ANTHROPIC_API_KEY` | Anthropic API 키 (선택) | — |
| `OPENAI_API_KEY` | OpenAI API 키 (선택) | — |
| `CHUNK_SIZE` | 문서 청크 크기 (문자) | `800` |
| `CHUNK_OVERLAP` | 청크 중첩 크기 (문자) | `120` |
| `TOP_K` | RAG 검색 결과 수 | `5` |

---

## 문서 인덱싱

서버 실행 **전에** 반드시 인덱스를 생성해야 합니다.

```bash
cd chatbot_server
python -m app.rag_index
```

- `../chatbot_data/docs/*.md` 와 `../chatbot_data/json/*.json` 을 읽어 인덱스를 빌드합니다.
- 인덱스는 `chatbot_server/data/vectorstore/` 에 저장됩니다.
- 문서가 변경되면 `python -m app.rag_index --force` 로 재생성합니다.

---

## 서버 실행

```bash
cd chatbot_server
uvicorn app.main:app --reload --port 8001
```

Swagger UI: http://localhost:8001/docs

---

## Spring Boot와 함께 실행

1. Spring Boot 서버를 먼저 실행합니다 (포트 8080).
2. `.env` 의 `SPRING_BASE_URL=http://localhost:8080` 을 확인합니다.
3. 챗봇 서버를 실행합니다 (포트 8001).

Spring Boot 없이도 챗봇 서버는 실행됩니다. 인증 없이 호출하면 RAG 문서 기반으로만 답변합니다.

> **Spring Boot 재빌드가 필요한 경우:**  
> `ChatbotContextController`가 포함된 최신 JAR이 필요합니다.  
> ```bash
> cd D:\tomy
> ./gradlew bootJar -x test
> java -jar build/libs/tomy-0.0.1-SNAPSHOT.jar
> ```

---

## curl 테스트 예시

### 인증 없이 (문서 기반 답변)

```bash
# Windows PowerShell
Invoke-RestMethod -Method POST -Uri http://localhost:8001/chat `
  -ContentType "application/json" `
  -Body '{"question": "영수증 인증은 어떻게 하나요?"}'
```

Python (Windows 인코딩 호환):
```python
import urllib.request, json

data = json.dumps({"question": "영수증 인증은 어떻게 하나요?"}).encode("utf-8")
req = urllib.request.Request(
    "http://localhost:8001/chat",
    data=data,
    headers={"Content-Type": "application/json"},
    method="POST",
)
with urllib.request.urlopen(req) as r:
    print(json.loads(r.read().decode("utf-8")))
```

### 인증 포함 (사용자 데이터 포함 답변)

```python
import urllib.request, json

JWT_TOKEN = "eyJhbGciOi..."  # Spring Boot 로그인 후 발급된 토큰

data = json.dumps({"question": "포인트 얼마나 있어요?"}).encode("utf-8")
req = urllib.request.Request(
    "http://localhost:8001/chat",
    data=data,
    headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {JWT_TOKEN}",
    },
    method="POST",
)
with urllib.request.urlopen(req) as r:
    print(json.loads(r.read().decode("utf-8")))
```

### 디버그 API

```bash
# 서버 상태 (LLM provider 포함)
curl http://localhost:8001/health

# 인텐트 목록
curl http://localhost:8001/intents

# RAG 검색 직접 테스트
curl "http://localhost:8001/debug/search?query=영수증+실패+이유"
```

---

## 테스트 시나리오

| 질문 | 예상 intent | Spring Boot 호출 여부 |
|---|---|---|
| "영수증 인증은 어떻게 하나요?" | `RECEIPT_UPLOAD_GUIDE` | 없음 (RAG만) |
| "제 영수증 왜 실패했어요?" | `RECEIPT_FAILURE_REASON` | `get_recent_receipts` |
| "이 가게 적립돼요? 맘스터치" | `STORE_ELIGIBILITY` | `search_stores(keyword=맘스터치)` |
| "포인트 얼마나 있어요?" | `USER_POINT_STATUS` | `get_me_context` |
| "다음 레벨까지 얼마나 남았어요?" | `NEXT_LEVEL` | `get_me_context` |
| "영수증 사진은 어떻게 찍어야 해요?" | `RECEIPT_UPLOAD_GUIDE` | 없음 (RAG만) |

---

## 현재 MVP 한계

1. **Intent 분류**: 키워드 기반이므로 복잡한 표현에서 정확도가 낮을 수 있음
2. **가게명 추출**: 질문에서 가게명 추출이 단순 휴리스틱 수준
3. **대화 이력 없음**: 단일 턴(single-turn)만 지원, 이전 대화를 기억하지 않음
4. **로컬 LLM 응답 속도**: 모델 크기에 따라 수~수십 초 소요
5. **OCR 신뢰도**: `ocr_confidence` 컬럼은 현재 NULL (Spring Boot에서 미수집)

---

## 다음 개선 방향

1. **LLM 기반 intent 분류**: 복잡한 표현도 정확하게 분류
2. **멀티턴 대화**: Redis로 대화 이력 관리
3. **스트리밍 응답**: SSE(Server-Sent Events)로 타이핑 효과 구현
4. **가게명 NER**: 한국어 NER 모델로 질문에서 가게명 정확히 추출
5. **피드백 루프**: 좋아요/싫어요로 답변 품질 지속 개선
