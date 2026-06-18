-- V13: 챗봇(토미도우미) 답변용 컬럼 추가
-- receipt 테이블: 인증 상태, 실패 사유, 매칭 점수/방법, OCR 신뢰도
-- store 테이블: 별칭, 포인트 적립 가능 여부

ALTER TABLE `receipt`
    ADD COLUMN `verification_status` VARCHAR(20) NOT NULL DEFAULT 'SUCCESS'
        COMMENT 'SUCCESS | FAILED | DUPLICATE | PENDING',
    ADD COLUMN `failure_reason` VARCHAR(50) NULL
        COMMENT 'NO_CANDIDATE | LOW_CONFIDENCE | STORE_NAME_MISSING | DUPLICATE_RECEIPT | OCR_FAILED | NOT_REWARD_ELIGIBLE | UNKNOWN',
    ADD COLUMN `match_score` DECIMAL(5,3) NULL
        COMMENT '0.000 ~ 1.000 가게 매칭 점수',
    ADD COLUMN `match_method` VARCHAR(30) NULL
        COMMENT 'RULE_SCORE | RULE_NAME_ONLY | VECTOR_SEARCH | LLM_REVIEW',
    ADD COLUMN `ocr_confidence` DECIMAL(5,3) NULL
        COMMENT 'OCR 결과 신뢰도 (0.000 ~ 1.000), 없으면 NULL';

ALTER TABLE `store`
    ADD COLUMN `aliases` TEXT NULL
        COMMENT 'JSON array 문자열. 예: ["메가커피","메가엠지씨커피"]',
    ADD COLUMN `reward_eligible` BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT '포인트 적립 가능 여부';

-- 기존 receipt 데이터에 기본값 적용 (이미 성공한 영수증은 SUCCESS)
UPDATE `receipt` SET `verification_status` = 'SUCCESS' WHERE `store_id` IS NOT NULL;
UPDATE `receipt` SET `verification_status` = 'FAILED', `failure_reason` = 'NO_CANDIDATE'
    WHERE `store_id` IS NULL AND `verification_status` = 'SUCCESS';
