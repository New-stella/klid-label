-- =============================================================================
-- V88: 표준용어 정합 — LS_TUS_UPLOAD 비표준 컬럼 rename + LS_DATA_AUG 레거시 컬럼 DROP.
--
-- LS_TUS_UPLOAD 는 유지(dev 오토라벨 테스트 사용). 내부 비표준 컬럼만 사업표준 약어로 rename한다.
-- Java 필드명은 불변(JPQL 필드 참조 보호) — @Column(name=...) 물리명만 본 마이그레이션과 정합.
--
-- 표준단어 근거:
--   업로드=ULD · 상태=STTS · 명=NM · 이벤트=EVNT · 촬영=SHT · 만료=EXPD ·
--   버전=VER · 지자체=LCLGV · 오프셋=OFFSET · 길이=LEN
--
-- 인덱스(IDX_LTU_USER_STATUS, IDX_LTU_EXPIRES)는 RENAME COLUMN 시 PostgreSQL 이
-- 컬럼 참조를 자동 갱신하므로 재생성 불필요. 인덱스 이름은 그대로 둔다(선택).
-- =============================================================================

ALTER TABLE LS_TUS_UPLOAD RENAME COLUMN UPLOAD_ID     TO ULD_ID;
ALTER TABLE LS_TUS_UPLOAD RENAME COLUMN UPLOAD_LENGTH TO ULD_LEN;
ALTER TABLE LS_TUS_UPLOAD RENAME COLUMN UPLOAD_OFFSET TO ULD_OFFSET;
ALTER TABLE LS_TUS_UPLOAD RENAME COLUMN STATUS        TO STTS_CD;
ALTER TABLE LS_TUS_UPLOAD RENAME COLUMN FILE_NAME     TO FILE_NM;
ALTER TABLE LS_TUS_UPLOAD RENAME COLUMN EVENT_TYPE_CD TO EVNT_TYPE_CD;
ALTER TABLE LS_TUS_UPLOAD RENAME COLUMN CAPTURED_AT   TO SHT_DT;
ALTER TABLE LS_TUS_UPLOAD RENAME COLUMN EXPIRES_AT    TO EXPD_DT;
ALTER TABLE LS_TUS_UPLOAD RENAME COLUMN VERSION       TO VER;
ALTER TABLE LS_TUS_UPLOAD RENAME COLUMN LOCAL_GOV_CD  TO LCLGV_CD;

-- VMS_CLIP_ID 폭 정합: LS_DATA_RAW.VMS_CLIP_ID(128) 와 일치 — 합류 시 truncation 위험 제거.
ALTER TABLE LS_TUS_UPLOAD ALTER COLUMN VMS_CLIP_ID TYPE VARCHAR(128);

-- 레거시 DROP: LS_DATA_AUG.REJECT_RSN 은 @Transient(미매핑)·미사용.
-- 반려사유 영속은 LS_DATA_AUG_RVW.REJECT_RSN 이 담당한다. V87 의 (500→1000) 확대는
-- 본 DROP 으로 무의미해짐 — V87 은 이력 보존을 위해 유지하고 여기서 컬럼을 제거한다.
ALTER TABLE LS_DATA_AUG DROP COLUMN REJECT_RSN;
