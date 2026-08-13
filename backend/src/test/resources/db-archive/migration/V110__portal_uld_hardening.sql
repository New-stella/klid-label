-- =============================================================================
-- V110: 포털 업로드 3테이블(V109) 보강 델타
--
-- 배경: V109 가 LS_PORTAL_ULD / _FRME / _LBL 3테이블을 생성한다. 본 델타는 포털 업로드
--       전체 기능(프레임 일괄 INSERT · 소유자 스코프 삭제 · 무결성/성능) 요건에 맞춰
--       V109 스키마를 다음과 같이 보강한다. V109 와 중복되는 3테이블 생성/시드는 다루지 않는다.
--
--   1) LS_PORTAL_ULD_FRME.ULD_FRME_SN : IDENTITY → SEQUENCE 채번 전환
--      - 영상 1건당 최대 N행 프레임을 일괄 INSERT 할 때 JDBC batching 을 살리기 위함
--        (IDENTITY 는 INSERT 마다 생성키 반환이 필요해 Hibernate batching 이 무효화된다).
--      - Hibernate @SequenceGenerator(allocationSize=50, sequenceName=LS_PORTAL_ULD_FRME_SEQ)
--        와 정합하도록 INCREMENT BY 50 시퀀스를 만들고 컬럼 DEFAULT 를 nextval 로 교체한다.
--   2) LS_PORTAL_ULD_LBL : ULD_SN 반정규화 컬럼에 FK(ON DELETE CASCADE) 추가
--      - V109 는 프레임 FK(FK_LS_PORTAL_ULD_LBL_FRME)만 둔다. 반정규화된 ULD_SN 의
--        참조 무결성을 DB 로 보장하고 업로드 삭제 시 직접 연쇄 정리도 보강한다.
--   3) 성능 인덱스 보강:
--      - IDX_LS_PORTAL_ULD_STTS_MDFCN (ULD_STTS_CD, MDFCN_DT) : 고착 자산 스캔
--        (findStuck: 상태 IN(...) AND MDFCN_DT < cutoff) 풀스캔 방지.
--      - IDX_LS_PORTAL_ULD_LBL_FRME : V109 의 (ULD_FRME_SN) 단일 인덱스를
--        (ULD_FRME_SN, PORTAL_USER_NO) 복합으로 교체 — 소유자 스코프 replace-all
--        삭제/조회(deleteByUldFrmeSnAndPortalUserNo 등)를 완전 커버한다.
--
-- 멱등: 모든 문장은 IF EXISTS / IF NOT EXISTS / DO-guard 로 재실행 안전. 신규 설치에서는
--       대상 테이블에 데이터가 없어 안전하며, 기존 DB 에서도 데이터 손실 없이 적용된다.
--
-- PostgreSQL 표준 문법 (MariaDB 고유 문법 미사용).
-- =============================================================================

-- ------------------------------------------------------------------------------
-- 1) LS_PORTAL_ULD_FRME.ULD_FRME_SN : IDENTITY → SEQUENCE 전환
-- ------------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS LS_PORTAL_ULD_FRME_SEQ INCREMENT BY 50;

-- V109 가 부여한 IDENTITY(및 그 암묵 시퀀스/DEFAULT) 제거 후 명시 시퀀스로 교체.
ALTER TABLE LS_PORTAL_ULD_FRME ALTER COLUMN ULD_FRME_SN DROP IDENTITY IF EXISTS;
ALTER TABLE LS_PORTAL_ULD_FRME
    ALTER COLUMN ULD_FRME_SN SET DEFAULT nextval('LS_PORTAL_ULD_FRME_SEQ');

-- 기존 데이터가 있으면 시퀀스를 최댓값 다음으로 정렬(신규 설치는 1부터). is_called=false 로
-- 다음 nextval 이 설정값을 그대로 반환하도록 한다.
SELECT setval(
    'LS_PORTAL_ULD_FRME_SEQ',
    GREATEST(COALESCE((SELECT MAX(ULD_FRME_SN) FROM LS_PORTAL_ULD_FRME), 0) + 1, 1),
    false
);

-- ------------------------------------------------------------------------------
-- 2) LS_PORTAL_ULD_LBL.ULD_SN → LS_PORTAL_ULD FK (ON DELETE CASCADE) 추가 (멱등)
-- ------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_ls_portal_uld_lbl_uld'
    ) THEN
        ALTER TABLE LS_PORTAL_ULD_LBL
            ADD CONSTRAINT FK_LS_PORTAL_ULD_LBL_ULD
            FOREIGN KEY (ULD_SN) REFERENCES LS_PORTAL_ULD (ULD_SN) ON DELETE CASCADE;
    END IF;
END $$;

-- ------------------------------------------------------------------------------
-- 3) 성능 인덱스 보강
-- ------------------------------------------------------------------------------
-- 고착 자산 스캔용(상태 + 갱신시각).
CREATE INDEX IF NOT EXISTS IDX_LS_PORTAL_ULD_STTS_MDFCN
    ON LS_PORTAL_ULD (ULD_STTS_CD, MDFCN_DT);

-- V109 의 단일 컬럼 인덱스를 소유자 스코프까지 커버하는 복합 인덱스로 교체.
DROP INDEX IF EXISTS IDX_LS_PORTAL_ULD_LBL_FRME;
CREATE INDEX IF NOT EXISTS IDX_LS_PORTAL_ULD_LBL_FRME
    ON LS_PORTAL_ULD_LBL (ULD_FRME_SN, PORTAL_USER_NO);
