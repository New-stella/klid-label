-- R9 — 외부 비식별 솔루션 위탁 요청(POST /project) 마스킹 옵션 3종 설정 키 시드.
--
-- 벤더 확인 결과 실제로 의미 있게 조정 가능한 값은 아래 셋뿐이다. 규격상 필수 필드인
-- exp_quality / exp_format 은 벤더가 미지원이라고 회신했으므로 설정 키로 열지 않고
-- 요청에는 기존 규격 기본값을 계속 싣는다(화면 미노출).
--
-- kpst.deid.masking-type  : 마스킹 방식.
--   - STNG_TYPE_CD = NUMBER (정수)
--   - 허용값 {0, 2, 3} = 0 색상 · 2 모자이크 · 3 블러 (ConfigKeys.NUMBER_ALLOWED_VALUES)
--   - ★ 1 은 벤더 미할당이라 "범위"가 아니라 "허용값 목록"이다. [0,3] 범위로 두면 1 이 통과한다.
--   - 기본값 0(색상) = KpstProjectRequest.DEFAULT_MASKING_TYPE
--
-- kpst.deid.masking-range : 마스킹 영역 배율.
--   - STNG_TYPE_CD = DECIMAL (실수)
--   - 범위 0.5 ~ 2.0 (ConfigKeys.DECIMAL_RANGE)
--   - 기본값 1.0 = KpstProjectRequest.DEFAULT_MASKING_RANGE
--
-- kpst.deid.db-save      : 외부 솔루션의 프레임 DB 저장 여부.
--   - STNG_TYPE_CD = NUMBER (정수)
--   - 허용값 {0, 1} (ConfigKeys.NUMBER_ALLOWED_VALUES)
--   - 기본값 0(저장 안 함) = KpstProjectRequest.DEFAULT_DB_SAVE
--
-- 조회 경로: SystemConfigService.getInt / getDouble → KpstDeidentService.buildProjectRequest.
--   조회 실패(키 없음·타입 불일치·파싱 실패)·허용값 이탈 시 KpstProjectRequest.DEFAULT_* 로 폴백(fail-safe)
--   — 시드가 없어도 비식별 위탁은 종전과 동일하게 동작한다.
-- 시드 기본값이 코드 상수와 동일하므로 이 마이그레이션만으로 위탁 동작이 바뀌지 않는다.
-- 반영 지연: 설정 캐시 TTL 60초 + 2노드 Active-Active → 다른 노드는 최대 60초 뒤 반영된다.
--
-- 컬럼명 STNG_* 은 V61(용어 표준 rename: CONFIG_KEY→STNG_KEY 등) 이후 물리명 정합.
-- 멱등(idempotent): ON CONFLICT DO NOTHING — 이미 존재하면 NOOP (PostgreSQL).
-- LS_* 접두사 저작도구 전용 테이블 변경이므로 관제서버팀 협의 면제.
-- 신규 컬럼·테이블이 없으므로 표준용어·표준도메인 신규 등록 대상 아님(설정 행 INSERT 뿐).

INSERT INTO LS_SYSTEM_CONFIG (STNG_KEY, STNG_VALUE, STNG_TYPE_CD, EXPLN, MDFR_ID) VALUES
    ('kpst.deid.masking-type', '0', 'NUMBER',
     '비식별 마스킹 방식 (0 색상 / 2 모자이크 / 3 블러)', 'SYSTEM'),
    ('kpst.deid.masking-range', '1.0', 'DECIMAL',
     '비식별 마스킹 영역 배율 (0.5~2.0)', 'SYSTEM'),
    ('kpst.deid.db-save', '0', 'NUMBER',
     '비식별 처리 프레임 저장 여부 (0 저장 안 함 / 1 저장)', 'SYSTEM')
ON CONFLICT (STNG_KEY) DO NOTHING;
