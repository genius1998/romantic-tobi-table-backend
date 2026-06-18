# 🐾 토미 (Tomy) - 소상공인 상생 기반 캐릭터 키우기 서비스

> **"착한 소비로 나의 반려 동물을 성장시키세요!"**  
> 토미는 소상공인 가게에서의 소비 영수증을 인증하여 포인트를 얻고, 이를 통해 캐릭터를 키우는 보상형 앱 서비스의 백엔드 시스템입니다.

---

## 🌟 주요 기능

### 1. 지능형 영수증 인증 시스템 (OCR)
- **복합 OCR 기술:** Google Cloud Vision API와 OpenAI GPT-4o-mini를 결합하여 높은 인식률을 자랑합니다.
- **영수증 데이터 추출:** 가게명, 주소, 총 결제 금액, 결제 일시를 자동으로 추출합니다.
- **소상공인 상생:** 사전에 등록된 소상공인 가게 데이터베이스와 대조하여, 소상공인 가게 이용 시에만 리워드를 지급합니다.

### 2. 리워드 및 포인트 시스템
- **리워드 지급:** 인증된 영수증 금액의 일정 비율(예: 1%)을 포인트로 지급합니다.
- **중복 방지:** 동일 영수증 재사용 방지 로직이 구현되어 있습니다.

### 3. 캐릭터(Pet) 성장 시스템
- **성장 요소:** 획득한 포인트로 먹이를 주어 경험치를 올리고 레벨업을 할 수 있습니다.
- **커스터마이징:** 상점에서 옷을 구매하고 캐릭터에게 입힐 수 있습니다.
- **업적 시스템:** 영수증 인증 횟수, 레벨 달성 등에 따른 업적 및 보상을 제공합니다.

### 4. 보안 및 인프라
- **JWT 인증:** 안전한 사용자 인증 및 권한 관리를 위해 JWT(JSON Web Token)를 사용합니다.
- **DB 마이그레이션:** Flyway를 이용한 체계적인 데이터베이스 형상 관리를 수행합니다.

---

## 🛠 기술 스택

- **Language:** Java 17
- **Framework:** Spring Boot 3.1.5
- **Database:** MySQL
- **ORM:** Spring Data JPA
- **Migration:** Flyway
- **Security:** Spring Security, JWT
- **External API:** Google Cloud Vision API, OpenAI API (gpt-5-nano, text-embedding-3-small)

### 벡터 DB 기반 가게 매칭

가게 매칭은 Qdrant 벡터 DB를 우선 조회하고, 검색된 후보를 이름/주소 점수와 LLM 보조 판정으로 검증합니다.
가게 기준 데이터는 `src/main/resources/db/migration/V11__replace_store_with_gumi_restaurants_csv.sql`에 반영된 구미시 일반음식점 CSV 기반 데이터입니다.

```bash
docker compose -f docker-compose.qdrant.yml up -d
```

`.env` 예시:

```properties
OPENAI_API_KEY=sk-...
OPENAI_CHAT_MODEL=gpt-5-nano
OPENAI_CHAT_REASONING_EFFORT=minimal
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_EMBEDDING_DIMENSIONS=1536
QDRANT_URL=http://localhost:6333
QDRANT_COLLECTION=tomy_stores
STORE_VECTOR_ENABLED=true
STORE_VECTOR_ADMIN_TOKEN=change-this-token
```

로컬 MySQL에서 `Access denied for user 'tomy_app'@'localhost'`가 발생하면 MySQL 관리자 계정으로 아래 스크립트를 실행합니다.

```bash
mysql -u root -p < scripts/mysql-dev-setup.sql
```

가게 DB를 Qdrant에 색인:

```bash
curl -X POST http://localhost:8080/api/stores/vector/reindex \
  -H "X-Admin-Token: change-this-token"
```

임의 입력값으로 매칭 후보와 판정 결과 확인:

```bash
curl -X POST http://localhost:8080/api/stores/match-preview \
  -H "Content-Type: application/json" \
  -d '{"storeName":"메가커피 금오공대점","city":"구미시","address":"경북 구미시 대학로"}'
```

`LEXICAL_FALLBACK_VECTOR_UNAVAILABLE`가 보이면 Qdrant가 실행 중이 아니므로 Docker Desktop을 시작한 뒤 `docker compose -f docker-compose.qdrant.yml up -d`와 벡터 색인을 다시 실행합니다.
OpenAI 호출은 `.env`의 `OPENAI_API_KEY`를 우선 사용하며, 키는 `sk-`로 시작해야 합니다.
- **Documentation:** Swagger (SpringDoc OpenAPI 2.2.0)
- **Build Tool:** Gradle

---

## 🏗 아키텍처 (Architecture)

본 프로젝트는 **계층형 아키텍처(Layered Architecture)**를 따르고 있습니다.

- **Controller:** 클라이언트의 요청을 받고 응답을 반환합니다. (REST API)
- **Service:** 핵심 비즈니스 로직(OCR 처리, 포인트 계산, 레벨업 등)을 수행합니다.
- **Repository:** DB에 접근하여 데이터를 영속화합니다. (JPA 활용)
- **Domain:** 엔티티(User, Pet, Receipt, Store 등) 및 도메인 모델을 정의합니다.
- **DTO:** 계층 간 데이터 전송을 위한 객체들을 정의합니다.

---

## 🚀 시작하기

### 환경 설정
1. `src/main/resources/application.yml` 파일에서 다음 항목을 설정해야 합니다.
   - DB 연결 정보 (MySQL)
   - Google Cloud Vision API 자격증명 (JSON 키 경로)
   - OpenAI API 키
   - JWT Secret Key

### 빌드 및 실행
```bash
./gradlew build
./gradlew bootRun
```

---

## 🔗 API 문서
서버 실행 후 브라우저에서 아래 주소로 접속하여 확인할 수 있습니다.
`http://localhost:8080/swagger-ui/index.html`
