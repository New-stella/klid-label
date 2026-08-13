-- V96: LS_MARKING FPS 컬럼 추가 — 마킹 시점 실 fps 고정(pin)으로 TOCTOU 제거 (M-3 후속 근본 수정).
-- 배경: 자동마킹과 프레임추출이 각자 다른 시점에 VideoFpsResolver.resolveFps 로 fps 를 재조회했다.
--       Phase 2 의 video.fps 메타 적재(ingest AFTER_COMMIT @Async)가 마킹과 추출 사이에 완료되면
--       마킹은 30 폴백으로 frameIndex 를, 추출은 실 fps 로 seekMillis 를 계산해 프레임이 어긋난다(TOCTOU).
-- 수정: 마킹 생성 시 해석한 실 프레임레이트를 이 컬럼에 저장(pin)하고, 프레임추출은 재조회 대신
--       마킹 레코드의 pin 값을 읽어 seekMillis 를 계산한다 → 마킹↔추출이 구조적으로 동일 값 사용.
-- NULL 허용: 이 컬럼 이전에 생성된 기존 행은 값이 없으므로, 추출이 resolveFps 로 폴백한다(하위호환).
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 표준.
ALTER TABLE LS_MARKING ADD COLUMN IF NOT EXISTS FPS DOUBLE PRECISION; -- 마킹 시점 고정 프레임레이트(pin)
