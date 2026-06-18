# 토미도우미 챗봇 수동 테스트 가이드

## 현재 동작 상태 (2026-06-18 기준)

| 항목 | 상태 |
|------|------|
| LLM Provider | OpenAI `gpt-4.1-mini` |
| RAG 인덱스 | BM25, 24개 청크 |
| Intent 분류 | 키워드 기반, 14종 테스트 PASS |
| Spring Boot 연동 | JWT 개인화 정상 동작 확인 |

---

## 사전 준비

### 1. .env 파일
```env
SPRING_BASE_URL=http://localhost:8080
LLM_PROVIDER=openai
OPENAI_API_KEY=sk-proj-...      # 실제 키
OPENAI_MODEL=gpt-4.1-mini

LOCAL_LLM_URL=http://169.213.5.157:3000/api/chat   # timeout 이슈로 선택 옵션
LOCAL_LLM_TIMEOUT=120
```

### 2. 실행 순서
```bash
# 1) Spring Boot 먼저 실행 (포트 8080)
java -jar D:\tomy\build\libs\tomy-0.0.1-SNAPSHOT.jar

# 2) RAG 인덱스 빌드 (최초 1회)
cd D:\tomy\chatbot_server
python -m app.rag_index

# 3) FastAPI 챗봇 서버 실행 (포트 8001)
python -m uvicorn app.main:app --port 8001 --env-file .env
```

---

## Test 1: RAG 인덱스 빌드

```bash
python -m app.rag_index --force
```
**예상:** 24개 청크 생성, `data/vectorstore/bm25.pkl` 저장

---

## Test 2: /health

```bash
curl http://localhost:8001/health
```
**예상 응답:**
```json
{
  "status": "ok",
  "rag_index_loaded": true,
  "chunk_count": 24,
  "search_backend": "BM25",
  "llm_provider": "openai"
}
```

---

## Test 3: RAG 검색

```python
import urllib.request, json
with urllib.request.urlopen('http://localhost:8001/debug/search?query=%EC%98%81%EC%88%98%EC%A6%9D+%EC%9D%B8%EC%A6%9D') as r:
    data = json.loads(r.read())
    print('top:', data['results'][0]['source_file'])
```

---

## Test 4: JWT 발급

```python
import urllib.request, json

data = json.dumps({'userId': 'effort98', 'password': 'rmdwjd04@@'}).encode('utf-8')
req = urllib.request.Request(
    'http://localhost:8080/api/auth/login',
    data=data,
    headers={'Content-Type': 'application/json'},
    method='POST',
)
with urllib.request.urlopen(req) as r:
    token = json.loads(r.read())['accessToken']
    print('token:', token[:30] + '...masked')
```

---

## Test 5: Spring Boot Context API 직접 검증

```python
import urllib.request, urllib.parse, json

TOKEN = '...'   # 위에서 발급한 JWT
H = {'Authorization': 'Bearer ' + TOKEN}

def get(path):
    req = urllib.request.Request('http://localhost:8080' + path, headers=H)
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode('utf-8'))

# A. 내 정보
me = get('/api/chatbot/context/me')
assert me['currentPoint'] is not None      # ✓ currentPoint 존재
assert me['pet']['level'] is not None      # ✓ pet.level 존재
assert me['pet']['remainingExpToNextLevel'] is not None

# B. 최근 영수증
rr = get('/api/chatbot/context/receipts/recent?limit=5')
print('receipts:', len(rr['receipts']))
if rr['receipts']:
    r0 = rr['receipts'][0]
    assert 'verificationStatus' in r0
    assert 'failureReason' in r0
    assert 'earnedPoint' in r0           # None 가능 (실패 영수증)

# C. 가게 검색
kw = urllib.parse.quote('카페')
ss = get('/api/chatbot/context/stores/search?keyword=' + kw + '&limit=5')
assert ss['totalResults'] > 0
assert 'rewardEligible' in ss['stores'][0]
```

---

## Test 6: FastAPI /chat JWT 개인화 테스트

```python
import urllib.request, json, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

TOKEN = '...'   # 발급한 JWT

def chat(question, receipt_id=None, token=None):
    payload = {'question': question}
    if receipt_id:
        payload['receiptId'] = receipt_id
    data = json.dumps(payload).encode('utf-8')
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request('http://localhost:8001/chat', data=data, headers=headers, method='POST')
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode('utf-8'))
```

### A. 포인트 현황 (USER_POINT_STATUS)
```python
r = chat('포인트 얼마나 있어요?', token=TOKEN)
assert r['intent'] == 'USER_POINT_STATUS'
assert '2,448' in r['answer']          # 실제 포인트 반영
assert r['actions'][0]['target'] == 'RewardShop'
```
**실제 결과:**
> 현재 포인트는 2,448P입니다. 포인트를 모아 토미를 키우거나 가맹점에서 사용할 수 있어요.

### B. 다음 레벨 (NEXT_LEVEL)
```python
r = chat('다음 레벨까지 얼마나 남았어요?', token=TOKEN)
assert r['intent'] == 'NEXT_LEVEL'
assert r['actions'][0]['target'] == 'PetHome'
```
**실제 결과:**
> 현재 토미 레벨 1단계이고, 다음 레벨까지 150 EXP가 남았어요.

### C. 영수증 실패 이유 (RECEIPT_FAILURE_REASON)
```python
r = chat('제 영수증 왜 실패했어요?', token=TOKEN)
assert r['intent'] == 'RECEIPT_FAILURE_REASON'
assert r['actions'][0]['target'] == 'ReceiptUpload'
```
**실제 결과:**
> 인증 실패 이유는 DB에서 일치하는 가게를 찾지 못했기 때문입니다.

### D. 특정 영수증 (receiptId 지정)
```python
r = chat('이 영수증은 왜 실패했어요?', receipt_id=8, token=TOKEN)
# 본인 영수증 → 가게명 '육통육(비산점' 기반 상세 답변
assert '육통육' in r['answer'] or 'DB' in r['answer']

r2 = chat('이 영수증은 왜 실패했어요?', receipt_id=999, token=TOKEN)
# 타인/없는 영수증 → graceful 폴백 답변 (서버 죽지 않음)
assert r2['intent'] == 'RECEIPT_FAILURE_REASON'
```

### E. 가게 적립 여부 (STORE_ELIGIBILITY)
```python
r = chat('맘스터치 적립돼요?', token=TOKEN)
assert r['intent'] == 'STORE_ELIGIBILITY'
assert r['actions'][0]['target'] == 'StoreSearch'
```
**실제 결과:**
> 네, 맘스터치 구미시 내 여러 지점은 모두 포인트 적립이 가능합니다.

---

## Test 7: 실패 케이스 검증

### F-A. JWT 없이 포인트 질문
```python
r = chat('포인트 얼마나 있어요?')   # token 없음
# 서버 안 죽고, 포인트 확인 불가 안내
assert r['intent'] == 'USER_POINT_STATUS'
assert '포인트' in r['answer']
```

### F-B. 잘못된 JWT
```python
r = chat('포인트 얼마나 있어요?', token='invalid.token.here')
# 로그인 필요 안내
assert '로그인' in r['answer']
```

### F-C. Spring Boot 서버 꺼진 상태
```python
# Spring Boot를 종료한 뒤 테스트
r = chat('포인트 얼마나 있어요?', token=TOKEN)
assert '서버 정보를 불러오지 못했습니다' in r['answer']   # graceful degradation
```

---

## Test A: 로컬 LLM 단독 연결 (선택, timeout 이슈 있음)

```python
import requests
res = requests.post(
    'http://169.213.5.157:3000/api/chat',
    headers={'Content-Type': 'application/json', 'User-Agent': 'Mozilla/5.0', 'Referer': 'http://169.213.5.157:3000/'},
    json={'message': '안녕'},
    timeout=120,
)
print(res.json()['message']['content'])
```

---

## Intent 분류 단위 테스트 (서버 없이)

```python
import sys, io, importlib
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
from app.intent_classifier import classify

tests = [
    ('앱 어떻게 사용해요?',              'APP_USAGE'),
    ('영수증 인증은 어떻게 하나요?',      'RECEIPT_UPLOAD_GUIDE'),
    ('영수증 왜 실패했나요?',             'RECEIPT_FAILURE_REASON'),
    ('같은 영수증 또 제출했어요',         'RECEIPT_DUPLICATE'),
    ('맘스터치 적립돼요?',               'STORE_ELIGIBILITY'),
    ('맘스터치 적립 되나요?',             'STORE_ELIGIBILITY'),
    ('가맹점 목록 알려줘',               'STORE_RECOMMENDATION'),
    ('포인트 어떻게 쌓이나요?',           'POINT_RULE'),
    ('포인트 사용 어떻게 해요?',          'POINT_RULE'),
    ('포인트 얼마나 있어요?',             'USER_POINT_STATUS'),
    ('토미 어떻게 키워요?',              'CHARACTER_GROWTH'),
    ('다음 레벨까지 얼마나요?',           'NEXT_LEVEL'),
]
for q, expected in tests:
    result = classify(q)
    print(('PASS' if result == expected else 'FAIL') + ' [' + result + '] ' + q)
```

---

## 문제 해결 가이드

| 증상 | 원인 | 해결 |
|------|------|------|
| `rag_index_loaded: false` | 인덱스 파일 없음 | `python -m app.rag_index` 실행 |
| `intent: UNKNOWN` | 서버 재시작 전 코드 변경 | 서버 재시작 (`--reload` 또는 수동) |
| `답변을 생성하는 중 오류` | OpenAI API 키 문제 | `.env`의 `OPENAI_API_KEY` 확인 |
| context API 404 | Spring Boot 구버전 실행 중 | `gradlew bootJar -x test` 후 재시작 |
| 포인트가 답변에 없음 | JWT 미전달 또는 만료 | 토큰 재발급 후 재시도 |
| `로그인이 필요합니다` | 잘못된 JWT | Spring Boot 로그인 후 새 토큰 사용 |
| `서버 정보를 불러오지 못했습니다` | Spring Boot 미실행 | Spring Boot 서버 실행 확인 |
| Windows curl 인코딩 오류 | cp949 한글 문제 | Python `urllib.request` 사용 |
