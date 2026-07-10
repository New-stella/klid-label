# 13. 버전관리

> 출처: R1 RQ-SFR-08-04/05, R2 KLID-AT-UC-007/008, CLAUDE.md(라벨링·버전관리), 코드(`version/`)
> 관련: [12 검수](12-review-assignment.md) · [15 관제서버 통지](15-control-notify.md)

화면: `KLID-AT-SC-010`(라벨 이력 `/history/:videoId`). 코드: `version/`(11 파일).

## 13.1 DB 스냅샷 기반 (외부 VCS 미사용)

- 라벨 저장 시 라벨 **전체 스냅샷(JSON)** 을 `LS_LABEL_VERSION.LABEL_PAYLOAD`에 저장
- 버전 식별자 = 페이로드 해시 **`VERSION_HASH`(SHA-256)** — 동일 페이로드 재저장 시 동일 해시로 중복 식별(멱등)
- ADR-009: 외부 Git/Gitea 미사용. (단 코드에는 Gitea fallback 큐 `LS_GITEA_FALLBACK_QUEUE`/`GITEA_CMT_HASH` 흔적 존재 — R1 v1.2에서 **DB 스냅샷으로 확정**)

## 13.2 스냅샷 생성 시점

- **검수 승인(APPROVED) 시점에만** 스냅샷 생성 (`SAVE_REASON_CD='APPROVED'`)
- 라벨 임시저장 단계는 스냅샷 미생성 (`LS_DATA_LBL` upsert만)
- 저장 데이터: `{DATA_RAW_SN, DATA_SRC_SN, LABEL_PAYLOAD(JSON), VERSION_HASH, VER_NO(V90 rename, 구 VERSION_NO), SAVE_REASON_CD, ACTVTN_YN, REG_ID/REG_DT}`
- 변경이력은 `LS_DATA_LBL_HSTRY` 병행

## 13.3 diff (비교)

- 두 APPROVED 버전의 DB 스냅샷을 **앱에서 비교**해 라벨 단위 변경 목록 산출
- `GET /v1/frames/{srcSn}/versions` (이력) → `GET /v1/versions/{version}/diff?compareWith={fromHash}`
- 응답 라벨 diff 최대 500건 (OWASP API4)

## 13.4 rollback (복구)

- `POST /v1/versions/{version}/rollback` (body `srcSn`) — 대상 스냅샷을 **새 active 버전**(`SAVE_REASON='ROLLBACK'`)으로 복원
- IDOR·비관적 잠금으로 소유 검증·동시성 제어
- 복구로 라벨 변경 시 `TASK_MODIFIED` 통지 트리거 → [15](15-control-notify.md)

## 13.5 권한

- REVIEWER 전체 / WORKER 본인 배정 프레임 접근

## 13.6 관련 데이터 (DB)

`LS_LABEL_VERSION`(스냅샷·해시·active), `LS_DATA_LBL_HSTRY`(변경 이력), `LS_GITEA_FALLBACK_QUEUE`(통신 실패 재시도). → [18](18-database.md).
