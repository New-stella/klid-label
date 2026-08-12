-- =============================================================================
-- V180: LS_LABEL_VERSION.VER_NO 를 <영상 단위 산출 버전 번호>로 재정의 (DDL 전용)
--
-- 신규 컬럼 없음 — 기존 컬럼의 <의미>를 바꾼다. 옛 의미로 적재된 값의 무효화(전 행 UPDATE)는
-- <V181> 이 담당한다. 아래 "왜 두 파일인가" 참조.
-- (표준용어 검증 대상 아님: VER_NO 는 V90 에서 이미 표준명으로 개명된 기존 컬럼이다.)
--
-- ── 구 의미와 그 결함 ────────────────────────────────────────────────────────
-- 지금까지 VER_NO 는 VersionService.saveActiveVersion 이
--   countByDataRawSnAndDataSrcSn(rawSn, srcSn) + 1
-- 로 매기는 <프레임별 순번>이었다. 그런데
--   ① 검수 승인은 <영상 단위>로 일어나고
--   ② 내용이 안 바뀐 프레임은 (DATA_SRC_SN, VERSION_HASH) UNIQUE 때문에 스냅샷이 생기지 않는다
-- 따라서 같은 승인 회차인데도 프레임마다 번호가 다르게 밀린다. 이 값으로는 "이 영상의 N번째
-- 승인본"을 지목할 수 없다.
--
-- ── 새 의미 ──────────────────────────────────────────────────────────────────
-- VER_NO = 영상 단위 산출 버전 번호. LS_DATASET_EXPORT.OUTPUT_VER_NO 와 <같은 번호>이며,
-- 관제가 픽업하는 산출 폴더 v1 · v2 와 일치한다. NULL = 버전 번호를 알 수 없음.
--
-- ── 왜 두 파일인가 (V180 DDL · V181 DML) ─────────────────────────────────────
-- 이 프로젝트의 Flyway 설정에는 mixed 가 없어 한 스크립트가 <한 트랜잭션>으로 실행되고,
-- PostgreSQL 은 락을 <커밋 시점>에 해제한다. 따라서 아래 ALTER 가 잡는 ACCESS EXCLUSIVE 락을
-- 같은 파일에 둔 전 행 UPDATE 가 끝날 때까지 붙들게 되어, 그 구간 동안 LS_LABEL_VERSION 에 대한
-- <조회까지> 전부 대기한다(버전 목록 · diff · 롤백 조회 포함). 파일을 나누면 이 스크립트는
-- 카탈로그 변경만 하고 즉시 커밋되어 락을 놓는다.
--
-- 안전성: 이 컬럼을 <읽는> 프로덕션 코드는 0건이다(엔티티 매핑 · saveActiveVersion 채번 ·
-- 롤백 로그 출력 1곳뿐이며 판정에 쓰이지 않는다). 리포지토리의 OrderByVersionNoDesc 파생 쿼리는
-- 프로덕션 호출부가 없다. 따라서 의미 재정의가 기존 판정을 바꾸지 않는다.
--
-- ⚠ (DATA_SRC_SN, VERSION_HASH) UNIQUE 와 멱등 skip 로직은 건드리지 않는다 — 정상 동작이며,
--   P3 의 조회 규칙(VER_NO <= N 중 최대)이 그 위에서 성립한다.
--
-- 채번 배선은 P3 이다. 그때까지 신규 승인 스냅샷도 NULL(= 아직 모름)로 저장된다
-- (VersionService.saveActiveVersion — 구 count+1 채번 중단).
--
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 표준 문법.
--
-- 롤백 SQL (V162 규약):
--   -- 제약만 되돌린다. NULL 인 행이 남아 있으면 SET NOT NULL 이 실패하므로 값을 먼저 채워야 한다:
--   -- UPDATE LS_LABEL_VERSION SET VER_NO = 0 WHERE VER_NO IS NULL;
--   -- ALTER TABLE LS_LABEL_VERSION ALTER COLUMN VER_NO SET NOT NULL;
--   ※ V181 이 무효화한 옛 값은 복원할 수 없다(소실됨) — V181 의 롤백 주석도 함께 참조할 것.
--   ※ 애플리케이션 롤백도 함께 필요하다(LsLabelVersion.versionNo 가 Integer 이므로).
-- =============================================================================

-- NULL 허용 전환 — "산출 버전 번호를 아직 모른다"가 정상 상태가 된다.
-- (카탈로그 변경뿐이라 테이블 rewrite 없이 즉시 끝난다.)
ALTER TABLE LS_LABEL_VERSION ALTER COLUMN VER_NO DROP NOT NULL;

COMMENT ON COLUMN LS_LABEL_VERSION.VER_NO IS
    '영상 단위 산출 버전 번호 — LS_DATASET_EXPORT.OUTPUT_VER_NO 와 같은 번호이며 관제가 픽업하는 '
    '산출 폴더 v1·v2 와 일치한다. NULL=버전 번호를 알 수 없음. '
    '구 의미(프레임별 승인 순번)는 폐기됐다 — 승인은 영상 단위인데 내용 무변경 프레임은 스냅샷이 '
    '생기지 않아 프레임마다 번호가 밀려 회차를 지목할 수 없었다(V181 에서 기존 값 전량 무효화).';
