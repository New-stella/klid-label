# 13. 버전관리

> 출처: R1 RQ-SFR-08-04/05, R2 KLID-AT-UC-007/008, CLAUDE.md(라벨링·버전관리), 코드(`version/`)
> 관련: [12 검수](12-review-assignment.md) · [15 관제서버 통지](15-control-notify.md) · [24 학습데이터 파일 산출](24-dataset-export.md)

> **레이어 구분**: 본 페이지의 `LS_LABEL_VERSION`(DB 라벨 스냅샷)과 별개로, 검수 승인 시 확정 라벨을 **디스크 물리 파일**(프레임 이미지 + NIA COCO JSON)로 산출하는 기능은 [24 학습데이터 파일 산출](24-dataset-export.md) 참고.

화면: **SC-005 라벨링 캔버스(`/label/:id`)의 히스토리 인라인 패널**(`HistoryPanel` — '변경 이력' / '버전' 탭 + diff + 롤백). 코드: `version/`.
> 구 전용 화면 **SC-010 라벨 이력(`/history/:videoId`, `HistoryPage`)은 2026-08-03 제거**됐다(유일 진입점이던 영상 상세의 '버전관리로 이동' 버튼 제거 + 인라인 패널이 동일 기능을 모두 제공) → [04 화면 IA](04-screens-ia.md).

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
- **프레임 스코프 강제**: `DATA_SRC_SN` 이 NULL 인 버전(구 비식별 신고 영상 스코프 스냅샷 — 신규 적재 중단, 기존 행만 잔존)은 프레임 단위 비교 대상이 아니므로 **400 으로 명시 거부**한다. 구현에 가드가 없어 `findById(null)` 에서 미처리 500 이 나던 결함을 수정했다(D-ISSUE-26) → [08](08-deidentification.md) 8.4

## 13.4 rollback (복구)

- `POST /v1/versions/{version}/rollback` (body `srcSn`) — **대상 스냅샷 행을 다시 active 로 전환**한다(새 버전 행 적층 없음). 구 `SAVE_REASON='ROLLBACK'` 코드는 폐기: 롤백 결과 페이로드는 대상 스냅샷 그 자체라 재계산 해시가 대상 행과 같고 `(DATA_SRC_SN, VERSION_HASH)` UNIQUE 로 적층이 불가능하다(도달 불가 분기)
- **라벨 본문을 작업본(`LS_DATA_LBL`)으로 실제 복원**한다 — `LBL_SN`·AI 메타(`AUTO_LBL_YN`/신뢰도/출처)·`TRCK_ID` 까지 **보존 복원**(PK 를 재발급하면 이후 diff 가 "전량 교체"로 오분류되므로 점유된 PK 만 신규 발급 폴백). 라벨링 캔버스(`GET /v1/frames/{srcSn}/labels`)가 롤백 결과를 즉시 반영
- **롤백 행위는 `LS_DATA_LBL_HSTRY` 에 기록**: 누가(actor)·언제(시각)·어느 버전으로(대상 `VERSION_HASH`)
- **멱등 롤백은 no-op**: 현재 active 가 이미 대상 스냅샷이면 라벨을 재작성하지 않고 이력·통지도 발행하지 않는다
- IDOR·비관적 잠금으로 소유 검증·동시성 제어. 작업락(비식별 재처리 중) 영상은 롤백 거부(409)
- 복구로 라벨 변경 시 `TASK_MODIFIED` 통지 트리거 → [15](15-control-notify.md)

## 13.5 권한

- REVIEWER 전체 / WORKER 본인 배정 프레임 접근

## 13.6 관련 데이터 (DB)

`LS_LABEL_VERSION`(스냅샷·해시·active), `LS_DATA_LBL_HSTRY`(변경 이력), `LS_GITEA_FALLBACK_QUEUE`(통신 실패 재시도). → [18](18-database.md).
