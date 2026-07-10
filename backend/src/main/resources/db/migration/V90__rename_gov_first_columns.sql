-- =============================================================================
-- V90: 공공(행안부) 표준용어 우선 정합 — 비파괴 컬럼 rename 11건 (2026-07-10 감사).
--
-- 내부 비표준 물리 컬럼명을 사업 표준약어로 rename한다. Java 필드명/게터/세터/
-- 파생쿼리 메서드명/JSON 키는 불변 — @Column(name=...) 물리명만 본 마이그레이션과 정합.
-- (FE camelCase JSON·관제 계약 보존)
--
-- 표준단어 근거: 만료=EXPRY · 응답=RSPNS · 반려=RJCT · 모델=MDL · 버전=VER ·
--   신고/선언=DCLR · 댓글=CMNT · 첨부파일=ATCH_FILE · 저장=STRG
--
-- 인덱스/UNIQUE 제약은 PostgreSQL RENAME COLUMN 이 컬럼 참조를 자동 follow하므로
-- 재생성 불필요(제약/인덱스명 무변경). 비파괴 rename — 데이터 손실 0.
-- =============================================================================

ALTER TABLE LS_AUTH_WORK_LOCK   RENAME COLUMN EXPD_DT TO EXPRY_DT;
ALTER TABLE LS_TUS_UPLOAD       RENAME COLUMN EXPD_DT TO EXPRY_DT;
ALTER TABLE LS_DEIDENT_PROC_LOG RENAME COLUMN RESP_DT TO RSPNS_DT;
ALTER TABLE LS_DATA_AUG_RVW     RENAME COLUMN REJECT_RSN TO RJCT_RSN;
ALTER TABLE LS_DATA_META_REVIEW RENAME COLUMN REJECT_RSN TO RJCT_RSN;
ALTER TABLE LS_DATA_LBL_AI_INFO RENAME COLUMN MODEL_NM TO MDL_NM;
ALTER TABLE LS_LABEL_VERSION    RENAME COLUMN VERSION_NO TO VER_NO;
ALTER TABLE LS_DEIDENT_REPORT   RENAME COLUMN REPORT_DT TO DCLR_DT;
ALTER TABLE LS_ISSUE_COMMENT    RENAME COLUMN ISSUE_COMMENT_SN TO CMNT_SN;
ALTER TABLE LS_NOTICE_ATTACH    RENAME COLUMN ATTACH_SN TO ATCH_FILE_SN;
ALTER TABLE LS_NOTICE_ATTACH    RENAME COLUMN STORE_FILE_NM TO STRG_FILE_NM;
