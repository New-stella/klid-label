-- 용어 표준 약어 정합 2차: LS_* 전용 테이블 물리 컬럼 rename (타입/사이즈/기본값 변경 없음, 데이터 무손실).
-- Java 필드명/API JSON 키/getter 는 불변 — 엔티티 @Column(name) 만 새 물리명으로 매핑.
-- RENAME COLUMN 은 PK/UK/인덱스가 자동 follow 되며(컬럼명 참조), 제약·인덱스 이름은 그대로 둔다.
-- 대상 인덱스: IDX_LDLH_SRC(SRC_SN, REGISTERED_AT DESC) 는 REGISTERED_AT→REG_AT 자동 follow(인덱스명 무변경).
--             LS_DEIDENT_PROC_LOG 인덱스(V74 등)는 rename 대상 4컬럼을 참조하지 않아 무영향.
-- MNG_* 관제 공유 테이블은 미변경 — 아래 전부 LS_* 소유 컬럼.

-- LS_DEIDENT_PROC_LOG: 종류=KND / 시도=ATMPT / KPST→비식별화(DE_IDNTF) / 프로젝트=PJT / 데이터셋=DATST
ALTER TABLE LS_DEIDENT_PROC_LOG RENAME COLUMN REQ_KIND_CD      TO REQ_KND_CD;
ALTER TABLE LS_DEIDENT_PROC_LOG RENAME COLUMN POLL_ATTEMPT_CNT TO POLL_ATMPT_CNT;
ALTER TABLE LS_DEIDENT_PROC_LOG RENAME COLUMN KPST_PRJ_ID      TO DE_IDNTF_PJT_ID;
ALTER TABLE LS_DEIDENT_PROC_LOG RENAME COLUMN KPST_DATASET_ID  TO DE_IDNTF_DATST_ID;

-- LS_DATA_LBL_HSTRY: 등록=REG
ALTER TABLE LS_DATA_LBL_HSTRY   RENAME COLUMN REGISTERED_AT    TO REG_AT;
