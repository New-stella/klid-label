# 14. 데이터 증강 · 해상도 변경

> 출처: R1 RQ-SFR-07-01~03·06-03, R2 KLID-AT-UC-001/002/003/010, CLAUDE.md(증강=새 영상), 코드(`augment/`, `webhook/AugmentResultController`)
> 관련: [12 검수](12-review-assignment.md) · [19 외부 시스템](19-external-security-cvat.md)

화면: `KLID-AT-SC-022`(증강 요청 `/augment`, REVIEWER), `SC-023`(증강 결과 `/augment/result/:jobId`, REVIEWER). 코드: `augment/`(16 파일).

## 14.1 외부 증강 (WINTER/NIGHT/RAIN)

- **증강(생성) 본체는 외부 시스템 책임** — 저작도구는 위탁·결과 검수만
- 위탁 유형 3종: **WINTER / NIGHT / RAIN** (날씨·계절·시간). 해상도 변경(RESOLUTION)은 §14.3 내부 수행
- **증강 요청 화면(SCR-AUG-001)은 통합 단일 선택 UI** — 처리 종류 카드 4개(겨울/야간/우천/해상도 변경)를 `radiogroup` 으로 **하나만** 선택하고, 대상 영상도 검수 완료(승인) 1건만 단일 선택한다(§14.6). BE 증강 요청 API 는 `types` enum allowlist(WINTER/NIGHT/RAIN)로 강제하며, RESOLUTION 은 증강 잡 경로가 아니라 저작도구 직접 수행 경로(§14.3)로 분기된다.

### 콜백 충실 플로우 (요청 → 키 발급 → 콜백 → 새 영상 적재)

요청 시점에 **콜백이 성립하도록 선행 상태를 먼저 만든다**. 요청 응답만 주고 끝내지 않고, 외부(또는 dev 시뮬)가 결과를 콜백으로 push 하면 그 콜백이 실제 새 영상을 적재하는 끝까지 닫힌 흐름이다.

```
[요청] REVIEWER → 대상 영상(검수완료) + 증강 유형 선택
  요청 tx: distinct(영상×종류) 건별로
    - idempotencyKey(AUG-<uuid>) + externalJobId(JOB-<uuid>) 선발급
    - LS_DATA_AUG 를 키와 함께 PENDING 단일 INSERT (originAugSn)
    - AugmentRequestedItemEvent 발행
  ── 요청 tx COMMIT ──
  AugmentRequestBridge(@TransactionalEventListener AFTER_COMMIT):
    - ledger.recordIssued(키 allowlist 등록)   ← 고아 키 방지(커밋 후에만)
    - ExternalAugmentClient.requestAugment(콜백 컨텍스트 전달)
        ↓ 비동기
[콜백] POST /api/v1/augments/result (HMAC 서명 + idempotencyKey)
  수신: {멱등키, 외부 작업 ID, 처리 상태(SUCCESS/FAILED/PARTIAL), originAugSn, 증강 유형, 결과 경로}
  HmacWebhookFilter 검증 → AugmentResultController → AugmentResultService.handle()
    - allowlist 미발급 키 401 · 멱등(처리됨) 200 스킵 · augType 불일치 409 · resultFilePath SSRF 검증
    - SUCCESS → §14.2 새 영상(ORGNL_RAW_SN/PENDING) 생성 + 프레임/라벨/메타 복사
```

- **콜백 경로 단일 진실원**: `/v1/augments/result` 는 `HmacWebhookFilter.PATH_AUGMENT` 한 곳에서만 정의하고, 요청측(`AugmentRequestService.CALLBACK_PATH`)·dev 시뮬(`DevAugmentCallbackSimulator.CALLBACK_PATH`)이 이 상수를 참조한다(경로 드리프트로 인한 401 회귀 차단).
- **고아 키 방지**: ledger 등록·외부 콜백 전달은 요청 트랜잭션 안이 아니라 **AFTER_COMMIT** 에서만 수행 — 요청 롤백 시 aug 행도 멱등 키도 남지 않는다.

## 14.2 증강 = 새 영상

- 성공 시 **새 영상**(`RAW_SN`, `ORGNL_RAW_SN`=원본) 을 **PENDING** 으로 생성
- 원본 라벨/메타를 새 영상에 **매핑/복사** (해상도 동일 → 좌표 그대로, 라벨 무결성 RQ-SFR-07-02)
- 라벨 무결성 검증: `LabelIntegrityCalculator` (원본 대비 라벨 수·좌표·속성 보존)
- 코드: `augment/AugmentResultService`, `LS_DATA_AUG`/`LS_DATA_AUG_RVW`/`LS_DATA_AUG_LBL_MAP`

## 14.3 해상도 변경 (RQ-SFR-06-03, 내부 수행)

- **저작도구가 직접 수행** (외부 위탁 아님, FFmpeg Java 래퍼)
- **원본보다 낮은 표준 하위 해상도로 다운스케일만** 허용 (RES_1080P/720P/480P), 업스케일 400 거부
- **결과는 다운스케일 이미지셋(프레임)만** — 영상(비디오) 재생성 없음
- **라벨 좌표 미제공** (해상도 변경본)
- 새 영상(RAW_SN) 미생성 — `LS_RESOLUTION_EXPORT` 1행만 기록 (UK: DATA_RAW_SN+TARGET_RES_CD, 중복 409)
- **화면: 증강 요청 화면(SCR-AUG-001)의 통합 단일 선택 UI에 흡수** — '해상도 변경' 카드 선택 시 타겟 해상도(1080P/720P/480P) 선택 UI가 노출되고, 실행하면 `POST /v1/videos/{rawSn}/resolution` 으로 직접 호출되어 결과(exportSn·원본→타겟 해상도·프레임수)가 화면에 inline 표시된다(네비게이션 없음). 증강 3종 실행은 잡 등록 후 결과화면(SC-023)으로 이동한다. (구 '영상 상세 화면 독립 해상도 export 섹션'은 폐지 — 컴포넌트 정리됨)
- 코드: `LS_RESOLUTION_EXPORT`(V55), FE `pages/AugmentRequestPage`(submit 분기) + `features/video/hooks/useResolutionExport`

## 14.4 활용 여부 검수 (RQ-SFR-07-03, UC-010)

```
PENDING 증강 영상 (SCR-AUG-002)
  → REVIEWER 확인 → accept(PENDING→ACCEPTED) 또는 reject(사유 필수, PENDING→REJECTED)
  → ACCEPTED 만 기존 배정·검수 흐름으로 학습데이터 편입
```

- `POST /v1/augments/{id}/accept` · `/reject`, PENDING 외 상태 전이는 409
- `LS_DATA_AUG.AUG_PROC_STTS_CD`: PENDING / ACCEPTED / REJECTED

## 14.5 관련 데이터 (DB)

`LS_DATA_AUG`(증강·상태), `LS_DATA_AUG_RVW`(검수·`LBL_INTGRT_PCT`·`REJECT_RSN`), `LS_DATA_AUG_LBL_MAP`(원본-증강 라벨 매핑), `LS_RESOLUTION_EXPORT`(해상도 변경). → [18](18-database.md).

## 14.6 통합 단일 선택 UX (SCR-AUG-001)

증강 요청 화면(`/augment`, REVIEWER)은 처리 종류와 대상 영상을 **각각 1건만** 고르는 단일 선택 흐름이다.

1. **처리 종류 선택** — 카드 4개(겨울/야간/우천/해상도 변경)를 `radiogroup`(로빙 tabindex·화살표 탐색, WCAG 4.1.2)으로 하나만 선택. '해상도 변경' 선택 시에만 타겟 해상도(1080P/720P/480P) 선택 UI 노출. 종류를 바꾸면 타겟 해상도·해상도 결과가 초기화된다.
2. **대상 영상 선택** — 검수 완료(`DATA_STTS_CD=COMPLETED` + `RVW_STTS_CD=APPROVED`) 영상만 라디오로 1건 선택(검색·이벤트 필터·페이징, 페이지 이동 후에도 선택 보존).
3. **실행(submit) 시나리오 분기**:
   - 증강 3종(`isAugmentKind`) → `POST /v1/augments/request`(videoIds·types 길이 1 배열) → 성공 시 토스트 + 결과화면(`/augment/result/{jobId}`) 네비게이션.
   - 해상도 변경 → `POST /v1/videos/{rawSn}/resolution`(preset 전달) → 성공 시 결과(exportSn·원본→타겟 해상도·프레임수) inline 표시. 업스케일/미검수/증강본/중복은 BE 400/409 → 에러 메시지 노출.
4. **실행 버튼 비활성 조건**: 종류 미선택 · 영상 미선택 · (해상도 종류인데 타겟 해상도 미선택) · 처리 중(`isPending`).
5. **보안**: kind/preset 은 allowlist 상수(`PROCESS_KINDS`/`RESOLUTION_PRESETS`)로만 좁혀 임의 문자열 분기 차단, videoId 는 number, 라우트는 REVIEWER 가드.

## 14.7 dev 콜백 시뮬레이터 · 운영/로컬 차이

외부 0(자족) 로컬 환경에서 외부 SFR-07 증강 시스템 없이도 콜백 충실 플로우 전체를 검증하기 위해 dev 시뮬레이터를 둔다.

| 구분 | 운영(prd) | 로컬(dev 시뮬) |
|------|----------|---------------|
| `ExternalAugmentClient` 구현 | 실제 외부 호출 클라이언트 | `DevAugmentCallbackSimulator` |
| 빈 활성 조건 | — | `@Profile("!prd")` + `authoring.augment.external.mode=dev` (기본/noop 시 `NoopExternalAugmentClient` `@Primary`) |
| 콜백 주체 | 외부 증강 시스템 | 시뮬레이터가 자기 자신(저작도구) 콜백 URL 로 POST |
| 콜백 상태 | SUCCESS/FAILED/PARTIAL | 항상 SUCCESS 가정 |

- **HMAC 서명 무결성**: 시뮬레이터는 검증 필터와 동일한 `HmacSigner.hex(secret, "{timestamp}.{body}")` 규칙을 쓰며, **JSON 직렬화를 1회만** 수행해 서명 대상 문자열과 전송 본문을 바이트 동일로 유지한다(서명 불일치 401 회귀 차단). 시크릿은 `webhook.hmac.secret.augment`(32B 이상, 미설정 시 해당 경로 fail-closed 401).
- **풀 점유 방지**: 콜백 호출은 `@Async("batchAsyncExecutor")` 비동기 + `augmentCallbackWebClient` 에 connect timeout(5s)·response timeout(10s) 적용 — 로컬 콜백 서버 미기동 시 TCP 연결 단계 무한 대기로 배치 풀이 점유되는 것을 막는다.
- **best-effort**: 콜백 HTTP/직렬화 실패는 삼키고 WARN 만 남겨 요청 흐름에 영향 0.

> 통합 검증: `AugmentCallbackFlowIntegrationTest` — 서명 콜백이 필터→컨트롤러→handle 을 통과해 새 영상(ORGNL_RAW_SN/PENDING) 생성·프레임/라벨 좌표 복사·리스트 PENDING 노출·멱등 중복 차단·잘못된 서명 401·콜백 경로 단일 출처를 단언한다.
