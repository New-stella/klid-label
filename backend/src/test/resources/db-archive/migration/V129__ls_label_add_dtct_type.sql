-- V129: LS_LABEL 에 검출유형코드(DTCT_TYPE_CD) 추가 — AI(COCO) 검출 클래스 매핑.
-- 배경: '라벨 관리'(LS_LABEL 마스터)와 라벨링 화면 'AI 탐지' 팝업의 라벨 목록이 분리돼 있었다.
--       팝업은 FE 하드코딩 COCO 6종을 노출하고 마스터는 사용자 CRUD 한글 라벨을 노출했다.
--       마스터 라벨에 COCO 검출 클래스를 매핑해, AI 탐지 후보를 '마스터 라벨 + 매핑 여부'로
--       일원화하고 매핑된 라벨만 실제 검출(ai-server 로 COCO 클래스 전송)한다.
-- 표준용어/표준도메인: 검출=DTCT(공통표준단어 Detection), 유형=TYPE, 코드=CD → 물리명 DTCT_TYPE_CD.
--       타입/크기는 코드값 표준도메인 VARCHAR(20)(EVNT_TYPE_CD 와 동일). COCO 는 고유명사라 물리명에
--       담을 수 없어 값(COCO 80 클래스명, 예: person/car/bus)은 comment 로 명시한다.
-- NULL 허용: 미매핑 라벨은 NULL(표시하되 검출 대상 아님). 기존 행은 NULL 로 시작한다 —
--            임의 추정 매핑 자동 백필은 하지 않는다(오매핑 방지). 운영자가 라벨 관리 UI 에서 지정한다.
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 표준.
ALTER TABLE LS_LABEL ADD COLUMN IF NOT EXISTS DTCT_TYPE_CD VARCHAR(20); -- 검출유형코드(AI COCO 검출 클래스명 매핑)

COMMENT ON COLUMN LS_LABEL.DTCT_TYPE_CD IS 'AI(COCO) 검출 클래스 매핑 — COCO 80 클래스명 저장(예: person/car/bus). NULL=미매핑(표시하되 검출 제외).';

-- 활성(USE_YN='Y') 라벨 중 1 COCO 클래스 = 1 라벨 강제 — 부분 유니크 인덱스(HIGH#2).
-- soft-delete(USE_YN='N') 또는 미매핑(NULL) 행은 제약 대상에서 제외한다.
CREATE UNIQUE INDEX IF NOT EXISTS UK_LS_LABEL_DTCT_TYPE
    ON LS_LABEL (DTCT_TYPE_CD)
    WHERE USE_YN = 'Y' AND DTCT_TYPE_CD IS NOT NULL;
