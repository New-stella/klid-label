-- ===========================================================================
-- verify-queries.sql — 배포 확인 · 장애 진단 쿼리 모음
--
--   쓰는 법
--     현장(온프렘)  : psql -U klid -d klid -f verify-queries.sql
--     개발서버(246) : docker exec -i postgis-klid psql -U postgres -d klid -f - < verify-queries.sql
--   한 케이스만 보려면 그 블록만 복사해 쓴다. 블록마다 <무엇이 정상인지> 를 적어 두었다.
--
--   ★★ 반드시 <앱이 붙은 DB> 에서 돌린다
--     246 에는 postgres 가 둘이고 한쪽은 앱이 안 쓰는 잔존 DB 다. 모르고 조회하면
--     "마이그레이션이 안 돌았다"고 오판한다(실제로 그런 적이 있다).
--     앱이 붙은 DB 는 기동 로그의 `Database: jdbc:...` 줄이 정본이다.
--     스키마는 public 이 아니라 klid_at 이다.
--
--   ⚠ 컬럼명은 실 DB 에서 실측한 것만 썼다(2026-09-16). 확인하지 못한 표는 구체 쿼리 대신
--     \d 로 스키마를 먼저 보게 두었다. 추측해서 쓰면 오타를 없애려다 <조용히 틀린 쿼리> 가 된다.
-- ===========================================================================

\pset pager off
\set SCHEMA klid_at


-- ###########################################################################
-- 1. 먼저 보는 것 — 배포가 실제로 반영됐나
-- ###########################################################################

-- 1-1. 적용된 마이그레이션 최근 10건
--   정상: 이번 회차의 신규 버전이 맨 위에 있고 success = t
SELECT installed_rank, version, description, success, installed_on
  FROM klid_at.flyway_schema_history
 ORDER BY installed_rank DESC LIMIT 10;

-- 1-2. 실패한 마이그레이션  ★ 0 이 아니면 그 자체가 장애다
SELECT count(*) AS 실패_마이그레이션
  FROM klid_at.flyway_schema_history WHERE success = false;

-- 1-3. 표·뷰 개수
--   ⚠ ddl-auto=validate 는 이 앱에서 동작하지 않는다. 스키마가 비어도 앱은 기동에 성공하므로
--     "떴으니 됐다"로 판단하면 빈 스키마인 채 운영에 넘어간다. 개수를 세는 것이 유일한 판정이다.
SELECT
  (SELECT count(*) FROM information_schema.tables
    WHERE table_schema='klid_at' AND table_name LIKE 'ls\_%')   AS ls_표,
  (SELECT count(*) FROM information_schema.tables
    WHERE table_schema='klid_at' AND table_name LIKE 'qrtz\_%') AS qrtz_표,
  (SELECT count(*) FROM information_schema.views
    WHERE table_schema='klid_at')                               AS 뷰;


-- ###########################################################################
-- 2. 연동 서버 주소 — 「토큰 갱신이 안 된다」류 장애의 1순위
-- ###########################################################################

-- 2-1. 설정 화면에서 넣은 주소
--   ★ 이 키들은 일부러 시드하지 않는다 — <행이 없는 것이 정상>이고 그때는 배포 기본값이 쓰인다.
--     따라서 "행이 없다"만으로 장애라 하지 말 것. 환경변수까지 함께 봐야 한다.
SELECT stng_key, stng_value, stng_type_cd, mdfr_id, mdfcn_dt
  FROM klid_at.ls_system_config
 WHERE stng_key IN ('kpst.deid.base-url',
                    'authoring.integration.ai-server.base-url',
                    'vlm.client.url',
                    'authoring.control-notify.url',
                    'authoring.control-account.url',
                    'authoring.augment.external.base-url')
 ORDER BY stng_key;

-- 2-2. ★★ 관제 통지 수신처와 관제 계정 창구가 <같은 값>이면 세션 연장이 깨진다
--   관제는 계정 창구(/api/account/)와 데이터셋 창구(/api/data-set/)를 다른 WAS 에 둔다.
--   같은 주소를 쓰면 세션 연장이 404 로 전부 실패하고, 누적 실패로 서킷이 열려
--   로그아웃 중계까지 막힌다. 폴백은 없다(일부러).
--   → 아래가 한 행이라도 나오면 그것이 원인이다.
SELECT a.stng_value AS 계정창구, n.stng_value AS 통지수신처, '★ 같은 주소 — 세션 연장 깨짐' AS 진단
  FROM klid_at.ls_system_config a
  JOIN klid_at.ls_system_config n ON n.stng_key = 'authoring.control-notify.url'
 WHERE a.stng_key = 'authoring.control-account.url'
   AND a.stng_value = n.stng_value;

-- 2-3. 설정 전체 (누가 언제 무엇을 바꿨나 — 감사)
SELECT stng_key, stng_value, mdfr_id, mdfcn_dt
  FROM klid_at.ls_system_config
 ORDER BY mdfcn_dt DESC;


-- ###########################################################################
-- 3. 사용자 표시 이름 수정
--    표: klid_at.ls_acnt_user (실측 8칸)
-- ###########################################################################

-- 3-1. 최근에 고쳐진 사용자 — 화면에서 고친 뒤 user_nm 과 mdfcn_dt 를 본다
SELECT user_no, user_id, user_nm, use_yn, reg_dt, mdfcn_dt, last_lgn_dt
  FROM klid_at.ls_acnt_user
 ORDER BY mdfcn_dt DESC NULLS LAST, user_no DESC LIMIT 20;

-- 3-2. 특정 사용자 (user_no 를 바꿔 쓴다)
SELECT user_no, user_id, user_nm, mdfcn_dt FROM klid_at.ls_acnt_user WHERE user_no = 9001;

-- 3-3. 멱등 확인 — 같은 이름을 다시 저장하면 mdfcn_dt 가 <밀리지 않아야> 한다.
--   저장 전에 이 값을 적어 두고 같은 값으로 저장한 뒤 다시 돌려 비교한다.
SELECT user_no, user_nm, mdfcn_dt AS 저장전_수정일시
  FROM klid_at.ls_acnt_user WHERE user_no = 9001;

-- 3-4. 길이 초과 — user_nm 은 varchar(100). 초과는 절단이 아니라 거부(400)다. ★ 0 이 정상
SELECT count(*) AS 길이초과_행 FROM klid_at.ls_acnt_user WHERE length(user_nm) > 100;

-- 3-5. 빈 이름 / 비가시 문자만 든 이름  ★ 둘 다 0 이 정상
--   ⚠ NBSP(U+00A0)·ZWSP(U+200B)·U+2007·U+202F 는 btrim 으로 안 털린다.
--     자바 isWhitespace 도 NBSP 를 공백으로 보지 않는다 — 그래서 따로 본다.
SELECT count(*) AS 빈_또는_공백만 FROM klid_at.ls_acnt_user WHERE btrim(user_nm) = '';

SELECT user_no, user_id, user_nm, length(user_nm) AS 길이
  FROM klid_at.ls_acnt_user
 WHERE user_nm ~ '[ ​  ﻿  ]' LIMIT 20;


-- ###########################################################################
-- 4. 영상 목록 제외(숨김)  —  ls_data_raw.excl_yn  char(1) NOT NULL DEFAULT 'N'
-- ###########################################################################

-- 4-1. 컬럼이 실제로 생겼는지
SELECT column_name, data_type, is_nullable, column_default
  FROM information_schema.columns
 WHERE table_schema='klid_at' AND table_name='ls_data_raw' AND column_name='excl_yn';

-- 4-2. 분포 — 화면의 「제외됨 N건」과 대조한다
SELECT excl_yn, count(*) AS 건수 FROM klid_at.ls_data_raw GROUP BY excl_yn ORDER BY excl_yn;

-- 4-3. 제외된 영상 목록
SELECT raw_sn, vms_clip_id, data_stts_cd, excl_yn, src_type, mdfcn_dt
  FROM klid_at.ls_data_raw WHERE excl_yn = 'Y'
 ORDER BY mdfcn_dt DESC NULLS LAST LIMIT 20;

-- 4-4. ⚠ 제외는 <화면 시야>일 뿐이다 — 배치·통지·데이터마트에 영향이 없어야 한다.
--   제외된 영상이 데이터마트 뷰에 그대로 보이는 것이 <정상>이다(결함이 아니다).
SELECT count(*) AS 제외인데_마트에_보임_정상
  FROM klid_at.v_completed_video v JOIN klid_at.ls_data_raw r ON r.raw_sn = v.raw_sn
 WHERE r.excl_yn = 'Y';

-- 4-5. 배정된 영상이 제외됐는지 (고아 배정 확인)
SELECT r.raw_sn, r.excl_yn, count(a.*) AS 배정수
  FROM klid_at.ls_data_raw r JOIN klid_at.ls_task_altmnt a ON a.raw_data_id = r.raw_sn
 WHERE r.excl_yn = 'Y' GROUP BY r.raw_sn, r.excl_yn ORDER BY r.raw_sn DESC LIMIT 20;


-- ###########################################################################
-- 5. 작업 이력 행위자 역할 — ls_task_evnt_log.actor_role_cd
--    CHECK (ADMIN|REVIEWER|WORKER)
-- ###########################################################################

SELECT evnt_id, raw_data_id, evnt_type_cd, actor_user_no, actor_role_cd,
       subject_user_no, prev_user_no, ocrn_dt
  FROM klid_at.ls_task_evnt_log ORDER BY ocrn_dt DESC LIMIT 20;

-- 역할 분포 — 신규 컬럼이라 <과거 행은 NULL 이 정상>이다(소급 채움 없음)
SELECT coalesce(actor_role_cd, '(과거행 NULL)') AS 역할, count(*) AS 건수
  FROM klid_at.ls_task_evnt_log GROUP BY 1 ORDER BY 2 DESC;


-- ###########################################################################
-- 6. 장애 진단 — 「영상이 안 넘어간다 / 배치가 멈췄다」
-- ###########################################################################

-- 6-1. 배치 단계 상태 분포
--   PENDING 이 계속 쌓이면 비식별 선두 단계가 안 도는 것,
--   MARKING_READY 가 쌓이면 마킹을 기다리는 것이다.
SELECT data_stts_cd, count(*) AS 건수, min(reg_dt) AS 가장오래된, max(reg_dt) AS 최근
  FROM klid_at.ls_data_raw GROUP BY 1 ORDER BY 2 DESC;

-- 6-2. 오래 고착된 영상 (24시간 넘게 PENDING/PROCESSING)
SELECT raw_sn, vms_clip_id, data_stts_cd, de_ident_yn, reg_dt,
       now() - reg_dt AS 경과
  FROM klid_at.ls_data_raw
 WHERE data_stts_cd IN ('PENDING','PROCESSING')
   AND reg_dt < now() - interval '24 hours'
 ORDER BY reg_dt LIMIT 20;

-- 6-3. 배치 처리 로그 — 최근 실패
\d klid_at.ls_batch_proc_log
SELECT * FROM klid_at.ls_batch_proc_log ORDER BY 1 DESC LIMIT 20;

-- 6-4. 재시도 대기 큐가 쌓여 있나
SELECT count(*) AS 재시도_대기 FROM klid_at.ls_bat_rty_wtng;
SELECT * FROM klid_at.ls_bat_rty_wtng ORDER BY 1 DESC LIMIT 10;

-- 6-5. Quartz — 잡이 실제로 발화하고 있나
--   ⚠ qrtz_fired_triggers 가 계속 비어 있으면 스케줄러가 안 도는 것이다.
--     instance_name 이 양쪽 다 'NON_CLUSTERED' 면 클러스터링이 꺼진 것이고,
--     같은 DB 에 두 앱이 붙어 있으면 서로를 구분하지 못한다(실제로 그 사고가 있었다).
SELECT trigger_name, instance_name, fired_time, state FROM klid_at.qrtz_fired_triggers LIMIT 20;
SELECT trigger_name, trigger_state, next_fire_time FROM klid_at.qrtz_triggers ORDER BY next_fire_time LIMIT 20;


-- ###########################################################################
-- 7. 장애 진단 — 비식별
-- ###########################################################################

-- 7-1. 플래그 분포   'Y' 완료 · 'N' 미수행 · 'F' 실패 또는 누락 신고
SELECT de_ident_yn, count(*) AS 건수 FROM klid_at.ls_data_raw GROUP BY 1 ORDER BY 1;

-- 7-2. 출처유형별 — GENERATED 는 비식별 제외 대상(설정 DEIDENTIFY_EXCLUDED_SRC_TYPES)
--   제외 영상도 최종적으로는 de_ident_yn='Y' 가 되는 것이 정상이다(위탁 대신 복사로 끝낸다).
SELECT coalesce(src_type,'(없음)') AS 출처유형, de_ident_yn, count(*) AS 건수
  FROM klid_at.ls_data_raw GROUP BY 1,2 ORDER BY 1,2;

-- 7-3. 비식별 이력 — 최근 실패
\d klid_at.ls_deident_proc_log
SELECT * FROM klid_at.ls_deident_proc_log ORDER BY 1 DESC LIMIT 20;

-- 7-4. ⚠ 비식별 산출물 파일명을 <조합·추측하지 말 것>
--   mock 은 deidentified.mp4, 실연동은 {원본stem}-mask.{확장자} 로 영상마다 다르다.
--   반드시 이력에 적힌 경로 값을 읽는다.

-- 7-5. 비식별 누락 신고
SELECT count(*) AS 신고_건수 FROM klid_at.ls_deident_report;
SELECT * FROM klid_at.ls_deident_report ORDER BY 1 DESC LIMIT 10;


-- ###########################################################################
-- 8. 장애 진단 — 외부 위탁(시계열·증강)이 응답을 안 준다
-- ###########################################################################

-- 8-1. 시계열 메타가 실제로 들어왔나
SELECT meta_key, count(*) AS 건수 FROM klid_at.ls_data_meta GROUP BY 1 ORDER BY 2 DESC LIMIT 20;

-- 8-2. 마킹 — 위탁 상태
\d klid_at.ls_marking
SELECT * FROM klid_at.ls_marking ORDER BY 1 DESC LIMIT 10;

-- 8-3. 증강 요청
\d klid_at.ls_data_aug
SELECT * FROM klid_at.ls_data_aug ORDER BY 1 DESC LIMIT 10;

-- 8-4. 웹훅 멱등 기록 — 콜백이 실제로 도착했는지
SELECT count(*) AS 웹훅_수신 FROM klid_at.ls_webhook_idempotency;
SELECT * FROM klid_at.ls_webhook_idempotency ORDER BY 1 DESC LIMIT 10;


-- ###########################################################################
-- 9. 장애 진단 — 관제 통지가 안 나간다
-- ###########################################################################

-- 9-1. 폴백 큐가 쌓여 있으면 통지가 실패하고 있다는 뜻
SELECT count(*) AS 통지_폴백_대기 FROM klid_at.ls_control_notify_fallback;
SELECT * FROM klid_at.ls_control_notify_fallback ORDER BY 1 DESC LIMIT 10;

-- 9-2. 통지 축적 — 재검토 표시 때문에 flush 가 보류되고 있을 수 있다
--   ⚠ 「수정 후 아무도 재승인하지 않은 영상」은 축적된 채로 남는다(설계상 알려진 한계).
SELECT count(*) AS 통지_축적 FROM klid_at.ls_mon_noti_acml;

-- 9-3. 재검토 표시가 선 영상 — 이 값이 'Y' 인 동안 통지가 보류된다
SELECT count(*) AS 재검토_대기 FROM klid_at.ls_raw_data_status WHERE revlt_yn = 'Y';
SELECT raw_data_id, data_stts_cd, revlt_yn FROM klid_at.ls_raw_data_status
 WHERE revlt_yn = 'Y' ORDER BY raw_data_id DESC LIMIT 20;


-- ###########################################################################
-- 10. 검수 / 배정 / 산출물
-- ###########################################################################

\d klid_at.ls_raw_data_status
\d klid_at.ls_task_altmnt

-- 10-1. 검수 워크플로 상태 분포
SELECT data_stts_cd, count(*) AS 건수 FROM klid_at.ls_raw_data_status GROUP BY 1 ORDER BY 2 DESC;
--   ⚠ 이 축과 6-1(배치 단계)은 <다른 축>이다. 같은 COMPLETED 라도 뜻이 다르므로 섞지 말 것.

-- 10-2. 배정 현황
SELECT count(*) AS 배정_건수 FROM klid_at.ls_task_altmnt;
SELECT * FROM klid_at.ls_task_altmnt ORDER BY 1 DESC LIMIT 10;

-- 10-3. 산출물(export) 최근 결과
\d klid_at.ls_dataset_export
SELECT * FROM klid_at.ls_dataset_export ORDER BY 1 DESC LIMIT 10;


-- ###########################################################################
-- 11. 데이터마트 뷰 — 관제가 SELECT 하는 계약면
-- ###########################################################################

-- 11-1. 뷰 4종이 실재하는지
SELECT table_name FROM information_schema.views WHERE table_schema='klid_at' ORDER BY table_name;

-- 11-2. 관제가 실제로 읽는 것은 v_completed_video 하나다
--   ⚠ 출력 컬럼 이름·순서가 계약이다. 임의로 바꾸면 관제가 깨진다.
SELECT count(*) AS 검수완료_영상 FROM klid_at.v_completed_video;

-- 11-3. 영상 1건 = 1행 불변  ★ 결과가 없어야 정상 (나오면 LATERAL 조인이 깨진 것)
SELECT raw_sn, count(*) AS 행수 FROM klid_at.v_completed_video GROUP BY raw_sn HAVING count(*) > 1;


-- ###########################################################################
-- 12. 고아 데이터 — 연쇄 삭제가 못 미치는 표
-- ###########################################################################
--   ⚠ 아래 표들은 부모 외래키가 없거나 방향이 달라 연쇄로 정리되지 않는다.
--     오류 없이 <조용히> 고아가 남으므로 주기적으로 본다.

-- ★★ 두 표의 SRC_SN 은 <프레임>(ls_data_src.src_sn)이다 — 영상(raw_sn)이 아니다.
--   2026-09-16 실측: 프레임 매칭 20/20 · 12/12 인데 영상 매칭은 15/20 · 3/12 였다.
--   ⚠ 영상으로 조인하면 <오류 없이 조용히 틀린 답>이 나온다 — ID 범위가 겹쳐 일부가 우연히
--     맞기 때문이다. 실제로 이 파일의 초판이 그 상태였고, 돌려 보고서야 잡혔다.
--   ⇒ 규칙: 컬럼 이름이 SRC_SN 이면 프레임으로 읽는다. 영상이려면 RAW_SN 이라야 한다.
--   (코드로는 확정하지 못했고 데이터로 판정했다.)
SELECT 'ls_data_lbl_hstry' AS 표, count(*) AS 고아
  FROM klid_at.ls_data_lbl_hstry h
 WHERE NOT EXISTS (SELECT 1 FROM klid_at.ls_data_src s WHERE s.src_sn = h.src_sn);

SELECT 'ls_data_aug' AS 표, count(*) AS 고아
  FROM klid_at.ls_data_aug a
 WHERE NOT EXISTS (SELECT 1 FROM klid_at.ls_data_src s WHERE s.src_sn = a.src_sn);

-- 파생 영상은 별도 컬럼이다 — new_raw_sn 이 <만들어진 파생 영상>을 가리킨다(이쪽은 영상 축).
SELECT 'ls_data_aug.new_raw_sn' AS 표, count(*) AS 고아
  FROM klid_at.ls_data_aug a
 WHERE a.new_raw_sn IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM klid_at.ls_data_raw r WHERE r.raw_sn = a.new_raw_sn);

-- 컬럼이 의심스러우면 먼저 스키마를 본다. 추측해서 쓰지 말 것.
\d klid_at.ls_data_lbl_hstry
\d klid_at.ls_data_aug


-- ###########################################################################
-- 13. 잠금·동시성
-- ###########################################################################

-- 13-1. 작업 잠금이 걸린 채 남아 있나
SELECT count(*) AS 작업잠금 FROM klid_at.ls_auth_work_lock;
SELECT * FROM klid_at.ls_auth_work_lock ORDER BY 1 DESC LIMIT 10;

-- 13-2. DB 쪽 장기 실행 쿼리·대기 (앱이 멈춘 것처럼 보일 때)
SELECT pid, state, wait_event_type, wait_event,
       now() - query_start AS 실행시간, left(query, 120) AS 쿼리
  FROM pg_stat_activity
 WHERE datname = current_database() AND state <> 'idle'
 ORDER BY query_start LIMIT 20;


-- ###########################################################################
-- 14. 비식별 실패 영상 재시작 — 「재시작을 눌렀는데 안 된다 / 계속 409 다」
--     (CO-20260916-선두비식별-실패-수동재시도 · API-167 건별 · API-199 일괄)
-- ###########################################################################
--   동작 요약
--     · 재시작 대상 <형상> = ls_data_raw.de_ident_yn = 'F' 이고 data_stts_cd = 'PENDING'.
--       이 형상이면 기존 배치 재시작(건별·일괄)이 마킹 이후 단계 대신 <선두 비식별>을 다시 돈다.
--       형상이 아니면 재시작은 예전 그대로다(이 블록과 무관).
--     · 형상 안의 거부 순서: 파생영상(400) → 승인 이력(409) → 열린 비식별 누락 신고(409)
--       → 진행 중 위탁(409) → 이미 잠김(409).
--     · 수락하면 ls_auth_work_lock 에 잠금 1행이 생긴다 —
--       lck_target_cd='RAW' · lck_stts_cd='LOCKED' · lck_id 가 'DEIDRETRY-' 로 시작 · 만료 6시간.
--       재수행이 성공하든 실패로 끝나든 그 종결에서 풀려야 한다(lck_stts_cd='RELEASED').
--     · 재시작 1회 = 비식별 이력(ls_deident_proc_log) 1행 추가.
--       req_knd_cd 는 NULL(배치 경로)이고, 출처유형 제외 영상이면 'EXCLUDED' 다.
--       ⚠ 'REDEIDENT' 가 찍히면 검수완료 재비식별 경로를 탄 것이다 — 이 기능의 행이 아니다.
--     · 재시작은 배치 단계(data_stts_cd)를 PROCESSING 으로 바꾸지 않고, de_ident_yn 을 'N' 으로
--       되돌리지도 않는다. 진행 중에도 'F'·'PENDING' 그대로인 것이 <정상>이다.
--       성공하면 'Y' + 'MARKING_READY' 로 넘어간다.
--   ★ 승인 이력 판정은 <현재 상태가 아니라 이력>이다 — 셋 중 하나라도 있으면 이력 있음:
--       ls_raw_data_status.data_stts_cd='APPROVED' · ls_dataset_video_meta 행 · ls_task_evnt_log 의 APPROVE.
--   ★ 영상 키는 raw_sn(ls_data_raw) = data_raw_sn(잠금·이력·신고) = raw_data_id(검수 상태·작업 이력)
--     = raw_sn(동결 메타)이다. src_sn 은 <프레임>이라 여기서 쓰지 않는다(12절 참고).

-- 14-1. 형상 건수 — 화면에서 「비식별 실패」로 보이는 영상 중 재시작 형상에 드는 것
--   de_ident_yn='F' 인데 PENDING 이 아니면 형상 밖이다(마킹 뒤에 난 누락 신고 표식일 가능성이 크다).
--   ⚠ 재시작_형상 에는 <누락 신고 표식 'F'> 도 섞인다 — 'F' 는 실패와 신고 두 뜻이다.
--     그런 영상은 14-2 에서 열린신고=t 로 거부된다(246 실측 2026-09-17: 형상 1건이 이관 영상의 열린 신고였다).
SELECT data_stts_cd, count(*) AS 비식별F_건수,
       count(*) FILTER (WHERE data_stts_cd = 'PENDING') AS 재시작_형상
  FROM klid_at.ls_data_raw
 WHERE de_ident_yn = 'F'
 GROUP BY data_stts_cd ORDER BY 2 DESC;

-- 14-2. 재시작 대상 후보와 거부 사유 — 「눌렀는데 왜 안 되나」 진단의 본체
--   사유 칸(파생·승인이력·열린신고·진행중위탁·잠김)이 모두 f 이면 재시작이 수락돼야 한다.
--   사유 칸이 t 인 첫 번째가 화면에 뜬 거부 사유다(위 순서대로 판정한다).
--   ⚠ 잠김 은 <이 기능의 잠금에 한하지 않는다> — 트랙 병합·검수완료 재비식별이 잡은 RAW 잠금도
--     같은 유일 인덱스를 쓰므로 재시작을 막는다. 누가 잡았는지는 잠금_식별자 로 본다.
SELECT r.raw_sn, r.vms_clip_id, r.src_type, r.reg_dt,
       (r.orgnl_raw_sn IS NOT NULL)                                         AS 파생,
       (EXISTS (SELECT 1 FROM klid_at.ls_raw_data_status s
                 WHERE s.raw_data_id = r.raw_sn AND s.data_stts_cd = 'APPROVED')
        OR EXISTS (SELECT 1 FROM klid_at.ls_dataset_video_meta m WHERE m.raw_sn = r.raw_sn)
        OR EXISTS (SELECT 1 FROM klid_at.ls_task_evnt_log e
                    WHERE e.raw_data_id = r.raw_sn AND e.evnt_type_cd = 'APPROVE')) AS 승인이력,
       EXISTS (SELECT 1 FROM klid_at.ls_deident_report d
                WHERE d.data_raw_sn = r.raw_sn AND d.report_stts_cd = 'OPEN')    AS 열린신고,
       EXISTS (SELECT 1 FROM klid_at.ls_deident_proc_log p
                WHERE p.data_raw_sn = r.raw_sn
                  AND p.poll_stts_cd IN ('WAITING','POLLING'))                   AS 진행중위탁,
       (lk.lck_id IS NOT NULL)                                              AS 잠김,
       lk.lck_id                                                            AS 잠금_식별자,
       lk.expry_dt                                                          AS 잠금_만료
  FROM klid_at.ls_data_raw r
  LEFT JOIN LATERAL (
        SELECT w.lck_id, w.expry_dt FROM klid_at.ls_auth_work_lock w
         WHERE w.data_raw_sn = r.raw_sn AND w.lck_target_cd = 'RAW' AND w.lck_stts_cd = 'LOCKED'
         ORDER BY w.lck_dt DESC LIMIT 1) lk ON true
 WHERE r.de_ident_yn = 'F' AND r.data_stts_cd = 'PENDING'
 ORDER BY r.raw_sn DESC LIMIT 50;

-- 14-3. 남아 있는 재시작 잠금 + 경과 + 그 영상의 최신 비식별 회차
--   정상: <진행 중 위탁이 있는 영상에만> 존재한다(최신 회차 poll_stts_cd 가 WAITING/POLLING,
--         또는 막 접수돼 이력 행이 아직 없거나 REQUESTED).
--   ★ 결함 신호: 최신 회차가 종결(SUCCEEDED/FAILED · DOWNLOADED/FAILED)인데 잠금이 남아 있다
--     → 종결 지점 하나가 잠금을 안 풀었다. 그 영상은 만료(6시간)까지 재시작이 계속 409 다.
--     진단 칸이 '★ 종결인데 잠금 잔존' 이면 그 행이다.
--   ⚠ 만료가 지난 LOCKED 는 만료 회수 스윕이 거둔다(안전망). 계속 남아 있으면 스윕이 안 도는 것이다.
SELECT w.data_raw_sn AS raw_sn, w.lck_id, w.lock_owner_id, w.lck_dt, w.expry_dt,
       now() - w.lck_dt                        AS 경과,
       (w.expry_dt < now())                    AS 만료지남,
       r.de_ident_yn, r.data_stts_cd,
       p.proc_log_sn, p.proc_stts_cd, p.poll_stts_cd, p.req_knd_cd, p.req_dt, p.err_cd,
       CASE
         WHEN p.proc_log_sn IS NULL                          THEN '접수 직후(이력 없음) — 잠시 뒤 다시 본다'
         WHEN p.poll_stts_cd IN ('WAITING','POLLING')        THEN '정상 — 위탁 진행 중'
         WHEN p.req_dt < w.lck_dt                            THEN '잠금 뒤 새 회차 없음 — 실행기가 안 돌았을 수 있다'
         WHEN p.proc_stts_cd = 'REQUESTED'
              AND p.poll_stts_cd IS NULL                     THEN '정상 — 제출 중'
         ELSE '★ 종결인데 잠금 잔존'
       END AS 진단
  FROM klid_at.ls_auth_work_lock w
  JOIN klid_at.ls_data_raw r ON r.raw_sn = w.data_raw_sn
  LEFT JOIN LATERAL (
        SELECT * FROM klid_at.ls_deident_proc_log x
         WHERE x.data_raw_sn = w.data_raw_sn
         ORDER BY x.proc_log_sn DESC LIMIT 1) p ON true
 WHERE w.lck_target_cd = 'RAW' AND w.lck_stts_cd = 'LOCKED'
   AND w.lck_id LIKE 'DEIDRETRY-%'
 ORDER BY w.lck_dt;

-- 14-4. 최근에 풀린 재시작 잠금 — 어떤 종결이 풀었나(rmv_rsn)
--   정상: 재시작마다 한 번씩 RELEASED 로 바뀐다. 해제 사유가 만료 회수(rmv_rsn='EXPIRED_SWEEP',
--   mdfcn_id='SYSTEM_SWEEP')만 계속 나오면
--   정상 종결 지점이 잠금을 안 풀고 있다는 뜻이다(6시간마다 풀리니 겉으로는 「가끔 된다」로 보인다).
SELECT data_raw_sn AS raw_sn, lck_id, lck_stts_cd, lck_dt, rmv_dt, rmv_dt - lck_dt AS 보유시간,
       rmv_rsn, mdfcn_id
  FROM klid_at.ls_auth_work_lock
 WHERE lck_target_cd = 'RAW' AND lck_id LIKE 'DEIDRETRY-%' AND lck_stts_cd <> 'LOCKED'
 ORDER BY rmv_dt DESC NULLS LAST LIMIT 20;

-- 14-5. 영상별 최근 비식별 회차 (영상당 최신 5행) — 재시작 뒤 <새 회차가 쌓였는지> 본다
--   대상: 지금 형상에 있는 영상 + 재시작 잠금을 한 번이라도 잡았던 영상.
--   정상: 재시작 1회마다 행이 하나 늘고, 그 행의 req_knd_cd 는 NULL(제외 영상이면 'EXCLUDED').
--   성공 회차 뒤 영상이 de_ident_yn='Y' · data_stts_cd='MARKING_READY' 로 바뀌어 있어야 한다.
--   ⚠ 산출물 경로(de_idntf_file_path_nm)는 이 값을 그대로 읽는다 — 파일명을 조합하지 말 것(7-4).
SELECT t.data_raw_sn AS raw_sn, t.회차순번, t.proc_log_sn, t.req_knd_cd, t.proc_stts_cd, t.poll_stts_cd,
       t.req_dt, t.rspns_dt, t.err_cd, left(t.err_msg_cn, 80) AS 오류요약,
       r.de_ident_yn AS 현재_비식별, r.data_stts_cd AS 현재_단계
  FROM (SELECT p.*, row_number() OVER (PARTITION BY p.data_raw_sn ORDER BY p.proc_log_sn DESC) AS 회차순번
          FROM klid_at.ls_deident_proc_log p
         WHERE p.data_raw_sn IN (
                 SELECT raw_sn FROM klid_at.ls_data_raw
                  WHERE de_ident_yn = 'F' AND data_stts_cd = 'PENDING'
                 UNION
                 SELECT data_raw_sn FROM klid_at.ls_auth_work_lock
                  WHERE lck_target_cd = 'RAW' AND lck_id LIKE 'DEIDRETRY-%')) t
  JOIN klid_at.ls_data_raw r ON r.raw_sn = t.data_raw_sn
 WHERE t.회차순번 <= 5
 ORDER BY t.data_raw_sn DESC, t.회차순번
 LIMIT 100;

-- 14-6. 특정 영상 하나 (raw_sn 을 바꿔 쓴다) — 재시작 전에 한 번, 누른 뒤에 한 번 돌려 비교한다
--   재시작 직후: 잠금 1행(LOCKED) · 이력 행 +1 · 영상은 아직 'F'·'PENDING'
--   끝난 뒤   : 잠금 RELEASED · 성공이면 영상 'Y'·'MARKING_READY'
SELECT raw_sn, de_ident_yn, data_stts_cd, orgnl_raw_sn, src_type, mdfcn_dt
  FROM klid_at.ls_data_raw WHERE raw_sn = 9001;
SELECT lck_id, lck_stts_cd, lck_dt, expry_dt, rmv_dt, rmv_rsn
  FROM klid_at.ls_auth_work_lock WHERE data_raw_sn = 9001 ORDER BY lck_dt DESC LIMIT 5;
SELECT proc_log_sn, req_knd_cd, proc_stts_cd, poll_stts_cd, req_dt, rspns_dt, err_cd
  FROM klid_at.ls_deident_proc_log WHERE data_raw_sn = 9001 ORDER BY proc_log_sn DESC LIMIT 5;
SELECT deident_report_sn, report_stts_cd, dclr_stp_cd, dclr_dt, resolved_dt
  FROM klid_at.ls_deident_report WHERE data_raw_sn = 9001 ORDER BY deident_report_sn DESC;
