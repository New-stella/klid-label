# 시계열 일괄 축 3버튼 + 승인 영상 재수행 분기 — 화면 스펙

> 대상: `SCREEN-008` 영상 처리 현황 · `SCREEN-009` 영상 상세
> 키트: `docs/screen-design/klid-authoring-screens/` (session 22 · 469건 전부 최신)
> 근거는 전부 키트 원문이다. 이 문서가 새로 정하는 사양은 없다 — 키트를 코드로 옮기는 계약서다.

---

## 1. 무엇이 없어서 만드나

백엔드 3 API 와 설계·시안은 이미 있는데 **화면이 없다.**

| 축 | 상태 |
|---|---|
| BE `POST/DELETE /v1/videos/batch/stages/{stage}/skip` · `POST …/rerun` | 구현됨 (`API-212`·`API-213`·`API-214`) |
| 설계 `SCREEN-008` v38 · `SCREEN-009` v50 | 확정 |
| 고충실 시안 `SD-013` v5 · `SD-004` v15 | 확정 (게시본 `source_hash` 일치) |
| **프론트엔드** | **없음** — 이 세 API 를 부르는 코드가 0줄 |

`SCREEN-009` 의 승인 영상 분기도 `everApproved` 필드만 타입에 있고 **화면이 읽지 않는다.**

---

## 2. SCREEN-008 — 일괄 액션바에 3버튼

**근거**: `screens/SCREEN-008/SCREEN-008.md` §일괄 액션바 components[3][4][5] · `_shared/api/API-212|213|214.md`
**시안**: `screens/SCREEN-008/design/design.html` (`.bulk-bar`, `#modal-skip-vlm`)

### 2.1 배치

기존 액션바(`pages/VideoListPage.tsx`)에 **같은 바**로 더한다. 시안의 구분자(`bulk-sep`)로
**배치 조작 그룹 / 배정 / 선택 해제** 세 덩어리로 읽히게 한다.

```
선택 3건 │ …안내… │ [일괄 재시작] [시계열 건너뛰기] [시계열 건너뛰기 해제] [시계열 재수행] │ [일괄 배정] 선택 해제
```

버튼 종류·크기는 기존과 같다(`btn-secondary btn-sm`). **배정만 primary 를 유지**해 위계가 흔들리지 않게 한다.

### 2.2 계약 (요구 R1~R6)

| # | 요구 | 근거 |
|---|---|---|
| **R1** | 세 버튼은 `stage=VLM` 하나만 부른다. **오토라벨은 이 바에 두지 않는다** | SCREEN-008 §description ★ — 산출물이 라벨이라 대량 건너뛰기를 열면 품질 축이 조용히 느슨해진다 |
| **R2** | 건너뛰기는 **사유 1개**를 받아 대상 전건에 같은 값으로 남긴다 | components[3] note — 영상마다 다른 사유를 받으면 일괄로 처리할 이유가 사라진다 |
| **R3** | 사유가 비었거나 **보이지 않는 문자만**이면 건별 실패가 아니라 **요청 전체 400** | components[3] note · BE `BatchStageSkipBulkRequest.@NotBlank` + 순회 전 1회 검증 |
| **R4** | 상한 **100건** · 중복 1건 취급 · 요청 순서 보존 · **부분 성공**(0건 접수여도 200) | `BulkRawSns.MAX_SIZE=100` · `BatchStageBulkResponse` |
| **R5** | 결과는 **기존 `BulkRetryResultModal` 재사용** — 성공·실패 건수 + 거부 건 사유 | SCREEN-008 §description "결과 표시는 일괄 재시작과 같다" |
| **R6** | 응답 스키마가 `BatchBulkRetryResult` 와 **동일**하므로 타입을 새로 만들지 않는다 | BE 실측 — `successCount`/`failureCount`/`results[]` |

### 2.3 사유 모달

시안 `#modal-skip-vlm` 을 따른다.

- 대상 영상 칩 목록 + "건너뛴 영상의 서술은 사람이 직접 쓴 전문으로 대신합니다. 나중에 해제할 수 있습니다."
- **글자수는 라벨 줄 오른쪽**(`.field-head`) — 도움말이 두 줄로 접혀도 숫자가 문단을 끊지 않는다.
- ⚠ **글자수를 `<label>` 안에 넣지 않는다** — 스크린리더가 필드 이름을
  「건너뛰는 사유 필수 31 / 500」으로 읽고 **타이핑할 때마다 이름이 바뀐다.** 라벨 밖 형제로 둔다.
- 사유 상한 **500자** (`ManualStageSkip.REASON_MAX_LENGTH`).

### 2.4 명시적 비목표

- 「건너뛰기 해제」는 **표식만 뗀다** — 확인 창을 두지 않는다(파괴적이지 않다).
- 「재수행」도 시계열은 확인 창 없이 접수한다 — 확정된 라벨을 건드리지 않기 때문이다.
  (오토라벨 재수행의 확인 창은 **상세 화면**의 것이며 이 바와 무관하다.)

---

## 3. SCREEN-009 — 승인 영상의 재수행 분기

**근거**: `screens/SCREEN-009/SCREEN-009.md` §BatchFailurePanel note ★
**시안**: `design.html` 참고 변형 ⑤ 「검수가 완료된 영상 — 묶음별로 갈린다」

> ★검수가 완료된 적 있는 영상은 두 묶음이 갈린다 — 시계열 재수행은 그대로 누르고
> 오토라벨 재수행은 비활성 + 사유 툴팁이다(라벨을 다시 만들어 승인 시점 스냅샷과 어긋난다).
> 되돌릴 수 없는 조건이라 파생영상처럼 미리 알린다.

| # | 요구 | 근거 |
|---|---|---|
| **R7** | 판정은 `VideoDetail.everApproved` — BE 가 내려주며 화면이 재유도하지 않는다 | `features/video/types.ts` (이미 존재) |
| **R8** | `everApproved && bundle === 'AUTOLABEL'` 이면 재수행 **비활성 + 사유** | SCREEN-009 note ★ |
| **R9** | 시계열은 승인 이력과 **무관하게 활성** | 〃 |
| **R10** | 사유 표시는 **지금 방식 유지** — `title` + `aria-describedby` 영역 설명. 새 Tooltip 컴포넌트를 들이지 않는다 | 사용자 확정 |

기존 비활성 조건(`busy || processing`)을 **대체하지 않고 더한다**.

---

## 4. 재사용 (새로 만들지 않는다)

| 대상 | 쓰임 |
|---|---|
| `features/video/api.ts` | 단건 축 6함수 패턴 + `assertStageBundle`(CWE-22 경로 세그먼트 검증) |
| `features/video/types.ts` | `BatchBulkRetryResult` · `BULK_RETRY_MAX` |
| `features/video/hooks/useBatchRecovery.ts` | 뮤테이션 훅 배치 |
| `features/video/components/BulkRetryResultModal.tsx` | 부분 성공 결과 표시 |
| `pages/VideoListPage.tsx` | 선택 모델 · 액션바 · 상한 안내 |

**단 하나의 확장**: `BulkRetryResultModal` 의 제목이 `"일괄 재시작 접수 결과"` 로 **하드코딩**돼 있다.
네 조작이 공유하므로 `title` 을 선택 prop 으로 열되 **기본값을 지금 문구로 두어** 기존 호출부는 무변경이다.

---

## 5. 용어 (구속 — 2026-08-20)

| 화면 문구 | 금지 |
|---|---|
| **건너뛰기 해제** | ~~되돌리기~~ |
| **해제됨** (상태 배지) | ~~되돌림~~ |

⚠ 심볼(`RevertedBundles`·`revertedBundles`·`isReverted`·`batch-stage-reverted-*`)은 **내부 이름이라 그대로 둔다.**

---

## 6. 발견 사항 (구현 전 실측)

1. **BE 응답이 기존 일괄 재시작과 완전히 같은 형태다** — `BatchStageBulkResponse` 와
   `BatchBulkRetryResult` 가 필드까지 일치한다. 타입·모달을 새로 만들 이유가 없다.
2. **`everApproved` 는 이미 내려온다** — BE 변경 없이 화면만 읽으면 된다.
3. **AC 파일에 이 축의 수용 기준이 없다** — `SCREEN-008/ac/` 5건·`SCREEN-009/ac/` 4건 어디에도
   일괄 축·승인 분기 항목이 없다. 검증 기준은 **키트 note 원문 + BE 계약**을 근거로 삼고,
   Phase 5 에서 `mc-logi-update` 권고 목록에 "AC 보강 필요"로 적재한다.

---

## 7. 검증

- 컴포넌트 테스트를 함께 쓴다(접근성 이름 조회 관례).
- **동작 카탈로그 `docs/test-cases/H-frontend-e2e.md` 를 같은 커밋에서 갱신**한다(저장소 구속 규칙).
- 마지막에 전체 회귀 1회. ⚠ `cc-build-validator` 는 이 저장소에서 기동 불가(프롬프트 한도) —
  위임하려면 `general-purpose` 에 실행 규칙을 직접 실어 보낸다.
