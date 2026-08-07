# LogiCraft 잔여 오염 정리 — 처리 기록

대상: SCREEN-011, SCREEN-030, UC-005, UC-009, UC-021, UC-022, UC-023, UC-024, TEST-002, TEST-003, TEST-005 (11건)

판정 기준: "이 문장이 이 저장소를 모르는 제3자에게도 참인가?" — 아니오면 본문에서 제거.
API 경로·DB 물리명·LogiCraft ITEM ID·정책 서술은 전부 보존.

모든 항목은 `data_mode: merge` 또는 `patch`로 해당 필드만 수정했고, 쓰기 후 `get_item` 재조회로
① 의도한 부분만 빠졌는지 ② 다른 내용 유실 여부(글자 수) ③ 한글 오타 여부를 확인했다.

---

## SCREEN-011 (`static_renders[0].description`)

**Before** (203자):
> DashboardPage.tsx 기반 와이어프레임 — 헤더/역할별 KPI/데이터개수/최근완료영상/내작업(WORKER)/에러. API-055·API-042·API-072.

**After** (192자):
> 대시보드 와이어프레임 — 헤더/역할별 KPI/데이터개수/최근완료영상/내작업(WORKER)/에러. API-055·API-042·API-072.

제거: `DashboardPage.tsx 기반 ` (코드 파일명).

---

## SCREEN-030 (`purpose`)

**Before**:
> ...WORKER 는 PUBLISHED 만 열람. R1 요구사항 외 추가 결정 (커밋 9ca1d33).

**After**:
> ...WORKER 는 PUBLISHED 만 열람. R1 요구사항 외 추가 결정.

제거: ` (커밋 9ca1d33)` (커밋 해시).

---

## UC-005 (`description`)

**Before**:
> ...MASK↔RLE↔Polygon 변환(CVAT 포팅)을 사용한다. 진실원: 코드(Sam2SegmentService, Sam2SegmentRequest, 커밋 6441fb9). (R2 UC-005, RQ-SFR-08-02)

**After**:
> ...MASK↔RLE↔Polygon 변환(CVAT 포팅)을 사용한다. (R2 UC-005, RQ-SFR-08-02)

제거: `진실원: 코드(Sam2SegmentService, Sam2SegmentRequest, 커밋 6441fb9).` 문장 전체.

---

## UC-009 (`description`)

**Before**:
> ...평면(flat) snake_case 페이로드로 전면 교체됐다(커밋 e2bc7c09, Phase 5B). 양방향 M2M 미운영...

**After**:
> ...평면(flat) snake_case 페이로드로 전면 교체됐다. 양방향 M2M 미운영...

제거: `(커밋 e2bc7c09, Phase 5B)` (커밋 해시 + 내부 Phase 라벨).

---

## UC-021 (`description`)

**Before** (끝부분):
> ...APPROVED 영상 수정 시 TASK_MODIFIED 통지(UC-009 연계). 진실원: 코드(LabelService.bulkUpsert — full-replace 시맨틱, LabelAttrValueService, LabelAccessGuard).

**After**:
> ...APPROVED 영상 수정 시 TASK_MODIFIED 통지(UC-009 연계).

제거: 트레일링 `진실원: 코드(...)` 문장 전체.

---

## UC-022 (`description`) — 2건

**제거 1** (콜백 경로는 본문 첫 문장에 이미 `POST /v1/vlm/callback` 로 명시돼 있어 이 문장이 순수 편집 이력만 반복):
> 삭제된 문장: `⚠ 구 서술의 콜백 경로 'POST /v1/vlm/result' 는 코드에 존재하지 않는 경로라 폐기했다(진실원: VlmResultController · WebhookProtectedPaths.PATH_VLM · SecurityConfig).`

**제거 2** (끝부분):
- Before: `...이 배선이 없으면 시계열 메타가 영구 결손된다. 진실원: 코드(VlmResultService, MetaService, VlmTimeseriesStep).`
- After: `...이 배선이 없으면 시계열 메타가 영구 결손된다.`

EVT-007 등 ITEM ID, POST /v1/videovlm/verify 등 API 경로, metaKey·framerate·event_type 정책 서술은 전부 보존.

---

## UC-023 (`description`)

**Before** (끝부분):
> ...제출/승인/반려 이벤트는 LS_TASK_EVENT_LOG 기록(ReviewService submit/approve/reject). 진실원: 코드(ReviewService, IssueThreadService).

**After**:
> ...제출/승인/반려 이벤트는 LS_TASK_EVENT_LOG 기록(ReviewService submit/approve/reject).

제거: 트레일링 `진실원: 코드(ReviewService, IssueThreadService).` 문장.

---

## UC-024 (`description`) — 2건

**제거 1**:
> 삭제된 문장: `회귀 가드 PortalSam2RemovedTest 가 포털 경로에 sam2 매핑이 다시 등록되면 실패하도록 고정한다.`

**제거 2** (끝부분):
- Before: `...(ITEM 자체는 유효). 진실원: 코드(PortalLabelService).`
- After: `...(ITEM 자체는 유효).`

API-130·API-131·DFEAT-044·DFEAT-018·API-093·UC-027·SCREEN-029 등 ITEM ID/경로는 전부 보존.
본문 앞부분의 `PortalLabelService` 언급(코드 실측 설명 문맥)은 이번 지정 범위 밖이라 유지.

---

## TEST-002 (`notes`)

**Before** (517자):
> ...핵심 테이블 LS_DATA_META·LS_DATA_META_REVIEW·LS_WEBHOOK_IDEMPOTENCY(상관키 원장)·LS_MARKING. **B4: D4 II-004 콜백 명세에 idempotencyKey·rawSn·status 필수필드 미반영(확인 필요).** 정상 흐름(happy path)만 수록. **구 표기 'POST /v1/vlm/result' 는 코드에 존재하지 않는 경로라 폐기했다(진실원: VlmResultController).** 구 전제 '동기 호출(45초 타임아웃) 내 수락 응답' 도 폐기했다 — 위탁은 논블로킹 제출이며 수락(ACK) 여부는 완료 핸들러가 원장에 비동기로 기록한다**(진실원: VlmTimeseriesStep · VlmSubmitOutcomeRecorder)**.

**After** (322자):
> ...핵심 테이블 LS_DATA_META·LS_DATA_META_REVIEW·LS_WEBHOOK_IDEMPOTENCY(상관키 원장)·LS_MARKING. 정상 흐름(happy path)만 수록. 위탁은 논블로킹 제출이며 수락(ACK) 여부는 완료 핸들러가 원장에 비동기로 기록한다.

제거: ① `B4:` 조사 항목 번호 문장 전체 ② 콜백 경로 편집 이력 문장 전체(경로는 상단에 이미 명시) ③ 2건의 `진실원:` 코드 심볼 태그. "위탁은 논블로킹 제출이며..." 현재 정책 서술은 "구 전제 …도 폐기했다 —" 편집사 프레이밍만 걷어내고 보존.

---

## TEST-003 (`notes`)

**Before**:
> ...실제 콜백은 authoring.webhook.callback-base-url + /v1/genai/callback 이다**(진실원: GenAiCallbackController · WebhookProtectedPaths.PATH_GENAI_CALLBACK)**. 구 물리명 'PARENT_RAW_SN' 은 V82 에서 'ORGNL_RAW_SN' 으로 개명됐다**(진실원: schema.sql 의 ls_data_raw.orgnl_raw_sn)**. ...

**After**:
> ...실제 콜백은 authoring.webhook.callback-base-url + /v1/genai/callback 이다. 구 물리명 'PARENT_RAW_SN' 은 V82 에서 'ORGNL_RAW_SN' 으로 개명됐다. ...

제거: `진실원:` 코드 심볼/파일 참조 2건만 제거(각 문장 내 정책·물리명 서술은 그대로 보존). B3/B4 finding 코드는 이 항목의 지정 범위에 없어 손대지 않음(보류 아님 — 범위 밖으로 판단, 아래 "보류" 참고).

---

## TEST-005 (`notes`)

**Before** (약 780자) — `★관련 활성 UC 정정(STALE-H02)` 라벨 + "구 메모가 잘못됐었다"는 장문의 자기 서술 편집사, `B5:` 라벨, 내부 문서 코드 `D2` 포함.

**After** (약 400자):
> ...핵심 테이블 LS_PORTAL_USER_LABEL. 본 시험이 검증하는 포털 DB 읽기 Load 흐름은 UC-024(포털 라벨 작업, 현재 approved)의 본문(관제→데이터마트→포털 DB 적재 후 저작도구가 Load)과 직접 대응되어 covers_use_cases 에 UC-024 를 포함한다(단 UC-024 자신이 R1 기능요구사항 대응 없음 — R2 유스케이스명세서 문서 범위 제외를 명시하므로 이 시나리오의 검증 대상 자체가 적절한지는 관제 협의와 별개 판단 사항이다). 포털 UC/SFR 근거 불명(결번 UC-024)인데 PortalLabelService 구현 존재·포털 전용 조회 화면 미할당(SC-005 공용 재사용). 정상 흐름(happy path)만 수록.

제거: `STALE-H02` finding 코드, `B5:` 라벨, 내부 문서코드 `D2`, "구 메모는 …이전 상태를 그대로 인용하고 있었다. 폐기된 것은 UC-024 가 아니라 이 서술 자체다" 류 장문 편집 이력. covers_use_cases에 UC-024가 포함되는 근거(UC-024 본문과의 대응 관계)와 범위 관련 유보(관제 협의 필요)는 제3자에게도 유효한 설계 사실이라 보존.

---

## 판단 보류 / 범위 밖으로 남긴 것

- **TEST-003의 B3/B4 finding 코드** — 사용자 지정 대상 표에 TEST-003 행은 "진실원: GenAiCallbackController..." · "진실원: schema.sql..." 2건만 명시했고 B3/B4는 포함되지 않아 손대지 않았다. 필요하면 별도 라운드로 처리.
- **UC-024 본문 앞부분의 `PortalLabelService`, `LabelController` 언급** — "코드 실측상 …" 설명 문맥에서 코드 사실을 서술하는 부분으로, 지정된 "2건" 제거 대상(PortalSam2RemovedTest 문장 + 트레일링 진실원 문장)에는 포함되지 않아 유지했다. 이 잔존이 오염으로 재규정되면 후속 라운드 필요.
- **brownfield 하위 필드의 커밋 해시** (`legacy_source` 등) — 지시대로 건드리지 않음.
- **`unresolved_links` 경고** — SCREEN-011/UC-005/UC-009/UC-021/UC-023/UC-024 갱신 응답에 `unresolved: 1`(또는 SCREEN-011 `removed: 1`) 링크 카운트가 표시됐다. 이번 편집은 ID 토큰을 추가/제거하지 않았으므로(코드 심볼·커밋 해시만 제거) 이 값은 편집 이전부터 존재하던 자동 링크 추출 상태로 보이며, 별도 조사 없이 그대로 남겼다.

---

## 처리 요약

| ITEM | 필드 | 제거 항목 수 | 상태 |
|---|---|---|---|
| SCREEN-011 | static_renders[0].description | 1 (파일명) | 완료 |
| SCREEN-030 | purpose | 1 (커밋 해시) | 완료 |
| UC-005 | description | 1 (진실원 문장) | 완료 |
| UC-009 | description | 1 (커밋 해시) | 완료 |
| UC-021 | description | 1 (진실원 문장) | 완료 |
| UC-022 | description | 2 (편집이력 문장 1 + 진실원 문장 1) | 완료 |
| UC-023 | description | 1 (진실원 문장) | 완료 |
| UC-024 | description | 2 (코드심볼 문장 1 + 진실원 문장 1) | 완료 |
| TEST-002 | notes | 4 (B4 문장 + 편집이력 문장 + 진실원 2건) | 완료 |
| TEST-003 | notes | 2 (진실원 태그) | 완료 |
| TEST-005 | notes | 4 (STALE-H02 라벨 + B5 라벨 + D2 참조 + 장문 편집이력) | 완료 |
