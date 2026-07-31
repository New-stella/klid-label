-- =============================================================================
-- V148: LS_DATA_AUG.AUG_PROC_STTS_CD 에 'CANCELED' 코드값 신설을 <문서화>한다.
--
-- 【스키마 변경 없음 — 왜 그런가】
--   AUG_PROC_STTS_CD 는 V7 에서 `VARCHAR(20) NOT NULL DEFAULT 'PENDING'` 로 정의됐고
--   <CHECK 제약이 없다>(전수 확인: 이 컬럼을 언급하는 마이그레이션은 V7/V140/V143 뿐이며 어느 것도
--   CHECK/ENUM 을 걸지 않는다). 'CANCELED'(8자)는 VARCHAR(20) 안에 들어가므로 <DDL 변경이 불필요>하다.
--   또한 이 컬럼을 참조하던 유일한 제약이었던 부분 유니크 UK_LS_DATA_AUG_ACTVTN(V143, 술어
--   `AUG_PROC_STTS_CD IN ('PENDING','ACCEPTED')`)은 V147 에서 DROP 됐으므로 새 코드값이 그 술어와
--   충돌할 여지도 없다. UK_LS_DATA_AUG_RESL(V125)의 술어는 AUG_TYPE_CD 기준이라 무관하다.
--
--   그럼에도 이 파일을 두는 이유는 <코드값 공간이 늘었다는 사실을 스키마 정본에 남기기 위해서>다.
--   COMMENT 가 'PENDING/ACCEPTED/REJECTED' 로만 남아 있으면 다음 사람이 DB 만 보고 3값으로 오해해
--   CHECK 제약이나 데이터마트 매핑을 3값 기준으로 만든다(같은 유형의 드리프트가 이 리포지토리에
--   반복된 적이 있다).
--
-- 【왜 CANCELED 가 필요한가】
--   「생성형 AI API 연동명세서 v1.1」 §4.6 취소는 <웹훅을 발사하지 않는다> — 동기 취소 응답이 유일한
--   통보다. 그 시점에 상태를 확정하지 않으면 그 증강은 영원히 PENDING 이고, 고아 회수기
--   (LsDataAugRepository.findOrphanPendingAugSns)는 "job 행 0건" 만 집으므로(취소된 증강은 job 이
--   1건 이상 존재한다) 만료 스윕도 건지지 못한다.
--   REJECTED 재사용은 불가하다 — 그 값에는 이미 REVIEWER 정상 반려와 외부 처리 실패 롤업 두 의미가
--   겹쳐 있어(E-06 계열) 세 번째 의미를 얹으면 어느 축으로도 구분할 수 없다.
--   취소는 실패가 아니므로 DEAD_LETTER_AT(처리 실패 전용 마커)은 찍지 않는다.
--
-- 【표준용어 준거】
--   신규 컬럼·테이블이 없다(코드 <값> 추가). 컬럼 물리명·타입·크기는 V7 정의를 그대로 쓴다
--   (코드값 표준도메인 VARCHAR(20) 준수). 코드값 'CANCELED' 는 LS_DATA_AUG_JOB.JOB_STTS_CD 가
--   외부 계약 §3.2 상태머신으로 이미 쓰고 있는 값과 <같은 철자>를 채택해 코드 공간 간 표기 드리프트를
--   만들지 않는다(두 컬럼은 서로 다른 축이지만 "취소" 의 표기는 하나로 통일한다).
--
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. COMMENT 만 갱신하므로 멱등이고 뷰 재생성 불요.
-- =============================================================================

COMMENT ON COLUMN LS_DATA_AUG.AUG_PROC_STTS_CD IS
    '증강 처리/검수 상태 코드. PENDING(요청·처리중) / ACCEPTED(검수 승인 또는 해상도 파생 생성 완료) / '
    'REJECTED(검수 반려 또는 처리 실패 롤업) / CANCELED(사용자 취소 종결, V148 신설). '
    '외부 처리 축(LS_DATA_AUG_JOB.JOB_STTS_CD = RECEIVED/RUNNING/SUCCEEDED/FAILED/CANCELED)과는 '
    '다른 코드 공간이므로 두 축을 섞어 판정하지 말 것. '
    '처리 실패 판정은 이 컬럼이 아니라 DEAD_LETTER_AT(처리 실패 전용 마커)로 한다.';
