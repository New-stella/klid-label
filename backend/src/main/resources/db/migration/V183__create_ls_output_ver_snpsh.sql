-- =============================================================================
-- V183: 산출 회차 ↔ 라벨 버전 스냅샷 매핑 LS_OUTPUT_VER_SNPSH 신설
--
-- ── 왜 컬럼 하나로는 안 되나 (이 테이블의 존재 이유) ─────────────────────────
-- V180 이 LS_LABEL_VERSION.VER_NO 를 <영상 단위 산출 버전 번호>로 재정의하면서, 「시작 버전 선택」
-- (R6)은 "VER_NO <= N 중 최대"로 그 회차의 프레임 내용을 골랐다. 그런데 <하나의 스냅샷이 여러
-- 회차의 내용>일 수 있어(내용 무변경 회차는 (DATA_SRC_SN, VERSION_HASH) UNIQUE 때문에 스냅샷이
-- 생기지 않는다) 회차↔스냅샷은 1:N 이다. 단일 컬럼은 이 관계를 담지 못한다.
--
-- 실제로 아래 순서에서 <조용한 오복원>이 난다(예외도 없고 미해결 집계에도 안 잡힌다):
--   ① v1 승인 — 프레임 F 의 내용 A 가 스냅샷 rA(VER_NO=1)로 확정
--   ② v2 승인 — 내용 B 가 스냅샷 rB(VER_NO=2)로 확정
--   ③ 프레임 단위 롤백으로 rA 재활성 — rB 는 비활성이지만 VER_NO=2 를 단 채 잔존
--   ④ v3 승인·산출 — F 의 내용은 A 그대로라 새 스냅샷도, rA 에 새 번호도 생기지 않는다
--      (채번은 미채번 행만 대상)
--   ⑤ 시작 버전 3 선택 → "VER_NO <= 3 중 최대" 가 <비활성 rB(2)> 를 골라
--      v3 에 존재한 적 없는 내용 B 로 되돌린다.
--
-- ── 이 테이블이 담는 것 ──────────────────────────────────────────────────────
-- "산출 회차 N 의 프레임 F 내용은 스냅샷 S 였다" 를 회차마다 <ACTIVE 전량>에 대해 기록한다.
-- 기록 시점은 산출 마감 트랜잭션(DatasetExportTxService.finalizeUnlessUnderDeidentReport →
-- OutputVersionStamper.stamp)이다 — 그 시점에만 "이번 회차의 내용이 되는 스냅샷 집합"이 확정되고,
-- 마감과 같은 트랜잭션이라 "매핑이 있는 회차 ⇔ 실재하는 산출 폴더"가 원자적으로 유지된다.
--
-- ── VER_NO 는 <제거하지 않는다> ──────────────────────────────────────────────
-- VER_NO 는 "그 내용이 처음 산출 내용이 된 회차"로서 조회·표시(영상 단위 버전 목록·존재 대조)에
-- 계속 쓰인다. 다만 <어느 스냅샷으로 되돌릴지의 판정 원천은 이 테이블 하나>다.
-- (판정을 두 곳에서 유도하면 갈라진다 — 이 저장소의 반복 결함 패턴.)
--
-- ── 기존 데이터 백필 범위와 한계 ─────────────────────────────────────────────
-- VER_NO 가 채워진 행에서 (DATA_RAW_SN, DATA_SRC_SN, VER_NO) 조합을 그대로 옮긴다. 이 백필의
-- 정확도는 <현재 동작과 동일>하며 더 나빠지지 않는다. 다만 위 ③④ 처럼 "번호가 찍히지 않은 채
-- 그 회차의 내용이었던" 대응은 정보 자체가 남아 있지 않아 <복원할 수 없다> — 앞으로 마감되는
-- 회차부터 정확해진다. 값을 지어내지 않는다(V181 의 "모른다를 정직하게 남긴다"와 같은 방침).
-- ※ V181 이 레거시 VER_NO 를 전량 무효화했고 실채번 배선(P3)은 아직 배포 전이므로, 운영 DB 에서
--   이 백필의 대상 행은 사실상 0건이다(그래도 순서 의존을 만들지 않으려 조건부로 넣는다).
--
-- ── 표준용어 준거 (정본: docs/LogiCraft-공공표준용어-* / docs/LogiCraft-사업용어-* CSV) ──
--   단어) 산출물=OUTPUT(공통표준단어 '산출물', Output) · 버전=VER(공통표준단어 '버전', Version)
--         스냅샷=SNPSH(사업표준단어 '스냅샷', snapshot — 공공 사전 미등록이라 사업 사전 채택)
--         라벨=LBL(공통·사업 공통) · 번호=NO(공통, 형식단어) · 일련번호=SN(공통, 형식단어)
--         ※ 우선순위는 행안부 공통표준 1순위 → 사업표준 보충. SNPSH 만 2순위에서 조달했다.
--   기존 물리명 재사용) DATA_RAW_SN · DATA_SRC_SN(선존 LS_LABEL_VERSION 동일) ·
--         OUTPUT_VER_NO(선존 LS_DATASET_EXPORT, V173 표준화분과 동일 번호·동일 물리명) · REG_DT
--   신규 컬럼) LBL_VER_SN — 대상 스냅샷 PK(LS_LABEL_VERSION.LBL_VERSION_SN) 참조.
--         ※ 참조 대상 컬럼명은 VERSION 을 <풀어 쓴> 선존 드리프트다. 신규 컬럼은 이를 미러하지
--           않고 표준 약어 VER 을 쓴다 — 선례는 V144(LS_MON_NOTI_ACML)가 "선존
--           LS_CONTROL_NOTIFY_FALLBACK 의 CONTROL/NOTIFY 드리프트는 미러하지 않는다"며 표준 약어
--           MON/NOTI 를 채택한 것이고, 그 V144 도 V116(LS_BAT_RTY_WTNG)을 같은 근거로 인용한다.
--           (선례의 대상 단어는 다르지만 <신규 컬럼은 선존 드리프트를 답습하지 않는다>는 방침이
--            그대로 적용된다.)
--   도메인) 일련번호B20(사업표준도메인, BIGINT) : OUTPUT_VER_SNPSH_SN / DATA_RAW_SN / DATA_SRC_SN / LBL_VER_SN
--           수I11(사업표준도메인, INTEGER)      : OUTPUT_VER_NO
--                                                (선존 LS_DATASET_EXPORT.OUTPUT_VER_NO 와 동일 타입)
--           연월일시분초D                        : REG_DT (PostgreSQL TIMESTAMP — 선존 전 테이블 관례)
--
-- ── FK — LS_DATA_RAW 자식 전역 정책(V146)을 그대로 따른다 ────────────────────
-- V146 이 LS_DATA_RAW 를 참조하는 자식 전량에 ON DELETE CASCADE 를 걸었고 거기에는 선존
-- LS_LABEL_VERSION.DATA_RAW_SN(fk_ls_label_version_raw) · LS_DATASET_EXPORT.DATA_RAW_SN
-- (fk_ls_dataset_export_raw) · LS_DATA_SRC.RAW_SN(fk_ls_data_src_raw) 이 모두 포함된다.
-- 그래서 실삭제 경로 — AugmentDiscardPurgeTxService(폐기 파생 실삭제, DELETE_ORDER ⑧ 주석이
-- "V146 FK CASCADE 가 자식을 정리한다"를 전제한다) · ResolutionPersistService
-- .deleteFailedDerivativeRaw · TusUploadService 완료 경합 롤백 — 은 자식 정리를 <FK 에 위임>한다.
-- 이 테이블만 FK 를 빼면 영상이 사라져도 매핑이 남아 <존재하지 않는 영상·스냅샷을 가리키는 행>이
-- 영구 잔존한다. FK 위반으로 시끄럽게 실패하는 것이 아니라 <조용히> 남으므로 발견이 늦다
-- (CLAUDE.md: "FK 가 없거나 방향이 달라 CASCADE 로 안 지워지면 조용히 고아가 남는다").
--   ※ 이 파일의 초안 주석은 "선존 LS_LABEL_VERSION·LS_DATASET_EXPORT 도 RAW_SN 에 FK 가 없다
--     (같은 방침)" 이라며 FK 를 생략했는데 그 서술은 <사실이 아니었다>(V146 실측). 확인하지 않은
--     전제로 무결성을 빼면 같은 오판이 다음 테이블에서 반복되므로 근거를 여기 남긴다.
--
-- LBL_VER_SN 에도 건다. LS_LABEL_VERSION 행만 <개별로> 지우는 경로는 현재 없지만(애플리케이션 ·
-- 마이그레이션 전수 확인 — 그 DELETE 가 한 곳도 없고 유일한 소멸 경로가 위 RAW CASCADE 다),
-- 매핑이 사라진 스냅샷을 가리키면 「시작 버전 선택」이 그 프레임을 조용히 unresolved 로 건너뛴다
-- (원인을 추적할 수 없는 부분 복원). 삭제 규칙은 V146 과 같은 CASCADE 다 — 그 마이그레이션이
-- "정책을 CASCADE 로 확정한 이상 예외를 두지 않는다"고 못박았고, RESTRICT 를 섞으면 RAW 삭제 시
-- 자식 정리 순서에 따라 정상 경로가 예고 없이 막힌다.
--
-- ── 등록자(REG_ID)를 두지 않는다 ─────────────────────────────────────────────
-- 이 행을 쓰는 유일한 지점이 @Async 산출 마감 트랜잭션이라 <actor 자체가 없다>. 지어내지 않는다.
-- 사람의 행위(시작 버전 선택)는 별도로 LS_TASK_EVENT_LOG 에 감사된다.
--
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 표준 문법. 멱등(IF NOT EXISTS).
--
-- 롤백 SQL (V162 규약):
--   DROP TABLE IF EXISTS LS_OUTPUT_VER_SNPSH;
--   ※ 애플리케이션 롤백도 함께 필요하다(StartVersionService 의 해석이 이 테이블을 읽는다).
-- =============================================================================

CREATE TABLE IF NOT EXISTS LS_OUTPUT_VER_SNPSH (
    OUTPUT_VER_SNPSH_SN BIGINT    NOT NULL GENERATED BY DEFAULT AS IDENTITY,
    DATA_RAW_SN         BIGINT    NOT NULL,                    -- 영상(LS_DATA_RAW.RAW_SN)
    DATA_SRC_SN         BIGINT    NOT NULL,                    -- 프레임(LS_DATA_SRC.SRC_SN)
    OUTPUT_VER_NO       INT       NOT NULL,                    -- 산출 회차(LS_DATASET_EXPORT.OUTPUT_VER_NO)
    LBL_VER_SN          BIGINT    NOT NULL,                    -- 그 회차의 내용이 된 스냅샷(LS_LABEL_VERSION.LBL_VERSION_SN)
    REG_DT              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (OUTPUT_VER_SNPSH_SN),
    -- 회차 × 프레임 = 스냅샷 1건. 산출 마감이 재시도·재실행돼도 적층되지 않는다 — 재시도 사이에
    -- 내용이 바뀌었으면 이 키로 <갱신>된다(ON CONFLICT … DO UPDATE. 첫 시도 값에 고정되면 매핑과
    -- 실제 산출 폴더 내용이 어긋난다). 값이 같으면 UPDATE 조건에서 걸러져 쓰기 자체가 없다.
    CONSTRAINT UK_LS_OUTPUT_VER_SNPSH UNIQUE (DATA_RAW_SN, DATA_SRC_SN, OUTPUT_VER_NO),
    CONSTRAINT FK_LS_OUTPUT_VER_SNPSH_RAW FOREIGN KEY (DATA_RAW_SN)
        REFERENCES LS_DATA_RAW (RAW_SN) ON DELETE CASCADE,
    CONSTRAINT FK_LS_OUTPUT_VER_SNPSH_LBL_VER FOREIGN KEY (LBL_VER_SN)
        REFERENCES LS_LABEL_VERSION (LBL_VERSION_SN) ON DELETE CASCADE
);

COMMENT ON TABLE LS_OUTPUT_VER_SNPSH IS
    '산출 회차 ↔ 라벨 버전 스냅샷 매핑 — 「산출 회차 N 의 프레임 F 내용은 이 스냅샷이었다」. '
    '「시작 버전 선택」(R6)이 되돌릴 스냅샷을 고르는 판정의 단일 원천이다. '
    'LS_LABEL_VERSION.VER_NO 는 조회·표시용으로 남되 판정 원천이 아니다 — 한 스냅샷이 여러 회차의 '
    '내용일 수 있어(내용 무변경 회차는 스냅샷이 생기지 않는다) 컬럼 하나로는 1:N 을 담을 수 없다.';
COMMENT ON COLUMN LS_OUTPUT_VER_SNPSH.OUTPUT_VER_NO IS
    '산출 회차 번호 — LS_DATASET_EXPORT.OUTPUT_VER_NO 및 관제가 픽업하는 산출 폴더 v1·v2 와 같다.';
COMMENT ON COLUMN LS_OUTPUT_VER_SNPSH.LBL_VER_SN IS
    '그 회차의 내용이 된 승인 스냅샷 PK(LS_LABEL_VERSION.LBL_VERSION_SN). 컬럼명은 표준 약어 VER 을 '
    '쓴다 — 참조 대상의 VERSION 표기는 선존 드리프트이며 미러하지 않는다.';

-- 프레임별 "회차 <= N 중 최대" 해석용 조회 인덱스는 <추가하지 않는다> — UK 선두가 DATA_RAW_SN 이라
-- 그 인덱스가 그대로 쓰인다(중복 인덱스는 쓰기 비용만 늘린다). DATA_RAW_SN FK 검사도 이 인덱스를 탄다.
--
-- 반면 LBL_VER_SN 은 어느 인덱스의 선두도 아니라 <FK 검사 전용>으로 하나 둔다: 부모 삭제 시
-- PostgreSQL 이 참조 자식을 찾는데, 인덱스가 없으면 삭제되는 스냅샷 행마다 이 테이블을 순차 스캔한다
-- (영상 1건이 프레임 수 × 회차 수 만큼의 행을 갖는다 — 파생 실삭제가 거기서 오래 잠근다).
CREATE INDEX IF NOT EXISTS IDX_LS_OUTPUT_VER_SNPSH_LBL_VER ON LS_OUTPUT_VER_SNPSH (LBL_VER_SN);

-- ── 기존 데이터 백필 (위 "백필 범위와 한계" 참조) ────────────────────────────
INSERT INTO LS_OUTPUT_VER_SNPSH (DATA_RAW_SN, DATA_SRC_SN, OUTPUT_VER_NO, LBL_VER_SN, REG_DT)
SELECT v.DATA_RAW_SN, v.DATA_SRC_SN, v.VER_NO, v.LBL_VERSION_SN,
       COALESCE(v.REG_DT, CURRENT_TIMESTAMP)
  FROM LS_LABEL_VERSION v
 WHERE v.VER_NO IS NOT NULL
   AND v.DATA_SRC_SN IS NOT NULL
ON CONFLICT DO NOTHING;
