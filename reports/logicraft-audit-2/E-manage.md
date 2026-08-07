# 배치 E — 관리 축 (SCREEN-024/025/026/032/035)

## 요약
- 발견: ERR 4 / STALE 3 / GAP 6 / CONFLICT 4 (총 17건, 중복 성격 항목은 주 분류 1개로 계상)

## 입력 검증 명세 갭 표

| 화면 | 입력 컴포넌트 | 실제 필요한 검증 | 정의서 명세 |
|---|---|---|---|
| SCREEN-024 | 검색(keyword) | 서버: 없음(길이 제한 無, USER_ID/USER_NM/USER_EMAIL 부분일치) | 없음(placeholder만) |
| SCREEN-024 | 역할/상태 select(필터) | 클라이언트 전용 필터, **현재 페이지(20건) rows만** 대상 — 서버 파라미터 아님 | "클라이언트 사이드 필터"라고만 서술, "현재 페이지 한정"의 함의(전체 결과 아님)는 명시 안 됨 |
| SCREEN-024 | 수정 모달 역할 select | 저장 버튼은 `role===원래값 \|\| role 미선택` 이면 disabled. BE `UserUpdateRequest.role` 은 `^(REVIEWER\|WORKER\|PORTAL_USER)$` 화이트리스트, null 허용(미변경) | 버튼 비활성 조건 없음. 화이트리스트 언급 없음 |
| SCREEN-025 | BATCH_INTERVAL_SEC | zod: 정수, 10~3600 (UI 슬라이더는 10~300로 시각적 제한, **스키마 상한과 불일치**) | "10~300"만 서술 |
| SCREEN-025 | BATCH_CONCURRENCY | zod: 정수, 1~10 (슬라이더 1~8) | "1~8"만 서술 |
| SCREEN-025 | YOLO_CONF_THRESHOLD / IOU | zod: 정수, 25~80, 에러 메시지 존재 | 범위만 라벨에 표기, 정수 제약·에러 문구 없음 |
| SCREEN-025 | YOLO_IMGSZ | zod: 정수, 320~1920, **32의 배수만 허용**(refine) | "step32"만 서술, 32배수 강제·거부 메시지 없음 |
| SCREEN-025 | POLYGON_SIMPLIFY_TOLERANCE | zod: 0~50 (소수 허용, step 0.5 UI) | "0.0~50.0px"만 서술 |
| SCREEN-026 | 프리셋 이름 | zod: 필수, **1~64자** | "필수"만, 길이 제한 없음 |
| SCREEN-026 | 설명 | zod: **0~500자**(optional) | 없음 |
| SCREEN-026 | 라벨 항목(labelIds) | zod: 정수·양수 배열, **최소 1개~최대 20개** | 개수 제한 전무(모달 섹션에), static_renders 스냅샷엔 "최대 6종"이라는 **틀린 숫자**가 남아있음 |
| SCREEN-026 | 매핑 이벤트 타입 | zod: `^[A-Za-z0-9_]{1,32}$` 정규식(빈값 허용), BE 최종 판정 | 형식 규칙 없음 |
| SCREEN-032 | 신고 목록 필터 | status 값 OPEN/RESOLVED 만 유효(BE enum) | 일치, 이슈 없음 |
| SCREEN-035 | 라벨명 | FE validate(): **최대 50자** / BE `@Size(max=64)` — **FE·BE 상한 불일치** | 길이 제한 언급 전무 |
| SCREEN-035 | 색상 | BE: `^#[0-9A-F]{6}$` **대문자 전용**(소문자 400) / FE validate(): 대소문자 모두 통과 → 제출 시 서버 400 가능 | HEX 형식·대소문자 제약 언급 전무 |
| SCREEN-035 | 정렬순 | 0 이상 정수(FE·BE 공통) | 언급 전무 |
| SCREEN-035 | dtctTypeCd(COCO 매핑) | BE: COCO 80 클래스 allowlist 아니면 400(자유텍스트 금지, CWE-20), `@Size(max=20)` | "선택"만, allowlist·거부 규칙 전무 |
| SCREEN-035 | 속성 정의 폼 전체(name/inputType/values/defaultVal/mutable/sortNo) | 아래 GAP-E13 참조 — **폼 필드 자체가 스펙에 없음** | 없음 |

---

## SCREEN-024 (사용자 관리)

### [ERR-E01] "활성/비활성 전환 확인 다이얼로그" 섹션이 폐기된 기능을 서술
- **위치**: sections[3] "활성/비활성 전환 확인 다이얼로그" (role=modal)
- **정의서 서술**: "행의 활성화/비활성화 버튼 클릭 시 오픈. '{이름}({loginId})을(를) 활성화/비활성화하시겠습니까?' 확인. 확인 시 PATCH /v1/users/{userNo} 로 useYn(Y|N) 만 전송."
- **실제**: 현재 코드에는 이 확인 다이얼로그·행별 활성화/비활성화 버튼 자체가 **존재하지 않는다**. `UserUpdateRequest`(BE DTO)에 `useYn` 필드가 **아예 없다**(role 단일 필드). 두 FE 구현(테스트베드 `UserManagePage.tsx`, 납품 FE `UserEditForm.tsx`) 모두 상태를 배지로만 표시하고 "계정 활성 여부는 저작도구에서 변경하지 않습니다" 문구를 명시한다. 활성/비활성 쓰기 권한은 관제서버로 이관됐다.
- **근거**: `UserManagePage.tsx` (컬럼 정의에 활성화 토글 버튼 없음, 파일 상단 주석 "활성/비활성(useYn)은 관제서버 책임으로 이관") · `UserUpdateRequest.java`(필드가 `role` 하나뿐, 클래스 주석 "활성/비활성(useYn) 쓰기는 관제 소유 컬럼이라 본 도구에서 제거") · `UserEditForm.tsx`(상태를 Badge 읽기 전용으로만 표시)
- **조치 제안**: 섹션 전체를 삭제하거나 "폐기됨 — 관제서버 이관" 이력 섹션으로 재분류. `implementation.status`/`consumes_apis`에서 API-004의 useYn 관련 서술도 함께 정정.
- **확신도**: high

### [ERR-E02] "사용자 정보 수정 모달"에 상태(활성/비활성) select 필드가 실재하지 않음
- **위치**: sections[4] "사용자 정보 수정 모달" > components (상태 Select: 활성/비활성)
- **정의서 서술**: "역할 select(검수자/작업자/포털) + 상태 select(활성/비활성) 수정... 변경된 필드만 PATCH /v1/users/{userNo} 로 전송(role·useYn)."
- **실제**: 모달에는 역할(role) select 단 하나만 존재. 상태는 편집 불가 배지로만 노출.
- **근거**: `UserManagePage.tsx`(Modal 내부에 Select 1개, `editRole` state만 존재) · `UserEditForm.tsx`(Badge로만 표시, `updateUser.mutate({userNo, role})` — payload에 useYn 없음)
- **조치 제안**: ERR-E01과 같은 원인 — 상태 select 컴포넌트 제거, description에서 "role·useYn" → "role만" 정정.
- **확신도**: high

### [GAP-E03] required_roles 비어있음 — 구조화 필드에 REVIEWER 게이트 서술 없음
- **위치**: `data.required_roles` (최상위)
- **정의서 서술**: `[]` (빈 배열). `purpose` 산문에는 "REVIEWER가 사용자 목록·상세를 조회하고 수정하는 관리 화면... 접근: REVIEWER."라고 명시되어 있음.
- **실제**: 라우터에서 `/manage/users`는 `RequireRole allow={[Role.REVIEWER]}`(납품 FE) / `InternalRoute allow={internalReviewerOnly}`(테스트베드, `internalReviewerOnly=[Role.REVIEWER]`)로 이중 감싸져 있고 BE도 `@PreAuthorize("hasRole('REVIEWER')")`로 감싼다. 같은 프로젝트의 SCREEN-032/035는 `required_roles: ["ROLE-001"]`(=REVIEWER, `permission_role` ITEM 확인됨)로 정확히 채워져 있어 일관성이 깨진다.
- **근거**: `routes/index.tsx`(klid-label-frontend, line 65) · `router/index.tsx`(테스트베드, `internalReviewerOnly = [Role.REVIEWER]`) · `UserController.java`(`@PreAuthorize("hasRole('REVIEWER')")`) · ROLE-001 ITEM(`get_item` 결과, `name: REVIEWER`)
- **조치 제안**: `required_roles: ["ROLE-001"]` 로 채움. 산문에만 있고 구조화 필드가 비어있으면 자동 생성기·권한 매트릭스 도구가 이 화면을 "역할 제한 없음"으로 오인해 전원에게 노출되는 화면을 만들 위험이 있다(CWE-862).
- **확신도**: high

### [GAP-E04] 폼 상태·필터 관련 세부 규칙 누락
- **위치**: sections[1] "검색·역할·상태 필터", sections[4] "사용자 정보 수정 모달"
- **정의서 서술**: 검증·비활성화 조건 서술 없음.
- **실제**: 수정 모달 저장 버튼은 `role === editUser.role`(변경 없음) 또는 미선택 시 비활성/변경 안 함 처리(`납품 FE`: `disabled={... || !role || role === user.role}`). 검색 keyword는 서버 측 길이 제한 없음(다만 화면 표시 목적상 이슈는 낮음).
- **근거**: `UserManagePage.tsx`(handleEditSave — `if (!payload.role) { setEditUser(null); return; }`) · `UserEditForm.tsx`(`disabled={updateUser.isPending || !role || role === user.role}`)
- **조치 제안**: "저장 버튼은 역할 변경이 없으면 비활성/모달만 닫힘" 규칙을 description에 추가.
- **확신도**: medium

### [CONFLICT-E05] 역할·상태 필터가 "현재 페이지"에만 적용 — 프로젝트 목록화면 정책과 배치
- **위치**: sections[1]·sections[2] description ("클라이언트 사이드 필터(useMemo)로 현재 페이지 rows 만 거른다")
- **정의서 서술**: 정의서가 이 동작(현재 페이지 한정 클라이언트 필터)을 정확히 서술하고 있다는 점에서 "오류"는 아니나, 프로젝트 CLAUDE.md의 확정 정책 "필터·집계는 BE 에서 전체 기준으로 처리하며 FE 클라이언트 필터로 대체하지 않는다 — 현재 페이지 단위 집계는 오답이다"(목록 화면 정렬·필터 정책, 2026-07-29 확정)와 정면으로 배치된다. BE `GET /v1/users`는 실제로 `role` 쿼리 파라미터를 지원하는데(컨트롤러 확인) FE가 이를 쓰지 않고 클라이언트 필터로 대체하고 있다.
- **실제**: `UserController.list()`가 `@RequestParam role`(Pattern 화이트리스트)을 받아 서버 필터링이 가능한데도 `useUsers(params)`의 `params`에는 role/status가 포함되지 않는다.
- **근거**: `UserManagePage.tsx`(`params` useMemo에 keyword만 포함, roleFilter/statusFilter는 `filteredRows` useMemo로만 적용) · `UserController.java`(`@RequestParam(required=false) @Pattern(...) String role`) · CLAUDE.md "목록 화면 정렬·필터 정책" 절
- **조치 제안**: 이 정의서만으로는 3자가 "정확히" 재현 가능하지만, 재현된 결과물이 프로젝트 자체 구속 정책을 위반한다는 점을 주석으로 남기거나(추후 FE 리팩터링 대상), 최소한 "페이지 20건 한정 — 전체 결과 기준 아님"을 명시해 이 화면을 참고 구현하는 사람이 동일 안티패턴을 다른 목록 화면에 복제하지 않도록 경고.
- **확신도**: medium

---

## SCREEN-025 (시스템 설정)

### [STALE-E06] "YOLO 추론 파라미터 카드" — 사용자 노출 문구에 기술 모델명(YOLO) 잔존
- **위치**: sections[1] > components "YOLO 추론 파라미터 카드"(Card label), description "② YOLO 추론: ..."
- **정의서 서술**: Card의 `label`이 문자 그대로 "YOLO 추론 파라미터 카드"로 되어 있고, `purpose`/description 전반에 "YOLO"가 반복 노출된다.
- **실제**: 실제 컴포넌트(`YoloConfigCard.tsx`)의 화면 표시 제목은 **"AI 탐지 추론 파라미터"**다. `YoloConfigCard`라는 내부 컴포넌트/파일명과 BE 설정 키(`YOLO_CONF_THRESHOLD` 등)는 기술 용어를 유지하지만, **사용자에게 보이는 텍스트에서는 "YOLO"가 등장하지 않는다.**
- **근거**: `YoloConfigCard.tsx`(`<h3>AI 탐지 추론 파라미터</h3>`) · CLAUDE.md 도메인 규칙 "★라벨링 캔버스는..." 절 인접의 FE 표기 원칙(YOLO/SAM2 등 기술 모델명 미노출)
- **조치 제안**: Card `label`을 "AI 탐지 추론 파라미터 카드"로 정정. `binds_to`(YOLO_CONF_THRESHOLD 등) 같은 내부 키 값은 유지해도 무방하나, 사용자 노출 `label`/`purpose` 문구에서 "YOLO"를 제거.
- **확신도**: high

### [GAP-E07] 설정 카드 3종의 zod 검증 규칙(정수 제약·32배수·에러 메시지) 전무
- 위 "입력 검증 명세 갭 표" 참조. 특히 `YOLO_IMGSZ`의 **32 배수 강제**(`.refine((v) => v % 32 === 0)`)는 정의서에 "step32"라고만 되어 있어, 서버 측 400 판정 근거(어떤 값이 왜 거부되는지)를 3자가 재현할 수 없다.
- **확신도**: high

### [GAP-E03-2] required_roles 비어있음 (SCREEN-024와 동일 패턴)
- `purpose`엔 "접근: REVIEWER" 산문 있으나 `required_roles: []`.
- **근거**: `router/index.tsx`(`internalReviewerOnly`) · `routes/index.tsx`(klid-label-frontend)
- **확신도**: high

---

## SCREEN-026 (프리셋 관리)

### [CONFLICT-E08] static_renders 스냅샷이 폐기된 UI를 서술 — 최신 data.sections와 내부 모순
- **위치**: `static_renders[0].sections[3]` "프리셋 편집 모달" (description)
- **정의서 서술(static_renders 스냅샷)**: "라벨 항목(코드 칩+Enter 추가+빠른추가 10종). 각 칩 BBOX/POLYGON 체크박스(둘 다 off면 저장 disabled). zod 검증(라벨 최대 6종)."
- **정의서 서술(현재 data.sections, 동일 문서 내)**: "★라벨은 라벨 마스터(LS_LABEL) 단일 진실원에서 고른다 — useLabelMasters 로 활성 마스터를 불러와 체크박스 멀티셀렉트... 형태는 마스터 LBL_TYPE_CD 가 소유해 읽기 전용... 구 코드 칩별 BBOX/POLYGON 체크박스... 구 '라벨 코드 직접 입력(Enter)'·'빠른 추가 10종' UI 도 함께 폐기됐다."
- **실제**: 코드는 `data.sections`가 맞다 — `PresetEditModal.tsx`는 `useLabelMasters()` 기반 체크박스 멀티셀렉트만 존재하고 Enter 입력·빠른추가·BBOX/POLYGON 토글은 없다. labelIds 개수 제한은 **1~20개**(`presetSchema.labelIds.min(1).max(20)`)이며 "최대 6종"이 아니다.
- **근거**: `PresetEditModal.tsx`(체크박스 멀티셀렉트만 존재) · `schemas.ts`(`labelIds: ... .min(1).max(20)`) · SCREEN-026 JSON 자체의 `data.sections[3].description`(최신, 정확) vs `static_renders[0].sections[3].description`(구, 부정확)
- **조치 제안**: `static_renders[0]`의 sections 텍스트를 `data.sections`와 일치시키거나(가장 확실), static_renders를 렌더 산출물 스냅샷 전용으로 취급해 텍스트 설명은 `data.sections`만 신뢰하도록 문서 소비 규칙을 명시. 3자가 static_renders만 읽으면 이미 삭제된 UI를 다시 구현하게 된다.
- **확신도**: high

### [STALE-E09] "매핑 이벤트 타입" select 옵션에 하드코딩된 "EVENT_TYPE_OPTIONS 6종"
- **위치**: sections[3] > components "매핑 이벤트 타입"(Select), `options: ["선택 안 함 (-)", "EVENT_TYPE_OPTIONS 6종"]`
- **정의서 서술**: 옵션이 고정 6종인 것처럼 명시.
- **실제**: 옵션은 `useEventTypes()` → `GET /v1/event-types` 런타임 조회이며, CLAUDE.md의 "★이벤트유형 필터는 표시명 그룹 축이다" 정책에 따라 표시명이 같은 유형코드들을 그룹으로 접어 개수가 가변적이다(관제 인입 상황·운영자 설정에 따라 6개보다 많거나 적을 수 있음). 같은 섹션의 description은 이를 정확히 "useEventTypes(GET /v1/event-types) 서버 조회"로 서술하고 있어 **컴포넌트 메타(options)와 description이 서로 모순**된다.
- **근거**: `PresetEditModal.tsx`(`eventOptions = [{value:'',...}, ...(eventTypes??[]).map(...)]`) · `features/eventType/api.ts`(`getEventTypes()` → `GET /event-types`) · CLAUDE.md "★이벤트유형 필터는 '표시명 그룹' 축이다" 절
- **조치 제안**: `options` 필드를 "동적(useEventTypes API 조회, 개수 가변)"으로 정정하거나 제거.
- **확신도**: medium

### [GAP-E10] 프리셋 이름/설명/라벨개수/이벤트코드 형식 검증 전무
- 위 검증 갭 표 참조.
- **확신도**: high

### [GAP-E03-3] required_roles 비어있음 (동일 패턴)
- `purpose`에 "접근: REVIEWER" 있으나 `required_roles: []`. header 섹션 description도 "REVIEWER일 때만 우측에 '프리셋 추가' 버튼... 비-REVIEWER는 버튼 미노출"이라고 조건부 UI까지 서술하면서 정작 화면 자체의 REVIEWER 게이트(라우트 레벨, 목록 GET 자체는 인증만 있으면 조회 가능한지 여부 포함)를 구조화 필드로 남기지 않음.
- **근거**: `router/index.tsx` REVIEWER 라우트 게이트
- **확신도**: high

---

## SCREEN-032 (비식별 신고 관리)

### [ERR-E11] "신고 단계를 화면상 구분 표시하지 않는다" 서술이 최신 코드(자신이 인용한 커밋)와 모순
- **위치**: `purpose` 및 sections[2] "신고 목록 테이블" description, `columns: ["신고 번호","영상","신고자","사유","신고일시","처리"]`
- **정의서 서술**: "★목록은 코드 실측 기준 '영상 #{rawSn}' 단위로만 표시된다... 마킹 단계... 와 라벨링 단계... 에서 접수된 신고를 화면상 구분 표시하지 않는다(2026-07-30 확인, 있는 것처럼 서술하지 않음). 단 서버는 신고 단계(DCLR_STP_CD, V171)를 보유하며 해소 후 재개 지점이 그 단계로 갈린다."
- **실제**: 실제 테이블에는 **"신고 단계"라는 별도 컬럼이 존재**하며(`신고 번호 / 영상 / 신고 단계 / 신고자 / 사유 / 신고일시 / 처리` — 7컬럼), `StageCell` 컴포넌트가 "마킹"/"라벨링"/"미상" 배지를 툴팁과 함께 렌더링한다. 이 기능은 2026-08-05 커밋(`c6d776b3 feat(deident): 비식별 신고 단계 구분 + 해소 후 재개 지점 분기 (V171)`)에서 추가됐다 — 즉 이 화면정의서가 스스로 change_summary에서 인용하는 **바로 그 V171 작업**이 실제로는 화면에 단계 컬럼을 노출시켰는데, 정의서 텍스트는 "구분 표시하지 않는다"는 구 결론을 그대로 유지한 채 change_summary만 "2026-08-06 확정"으로 갱신됐다.
- **근거**: `DeidentReportListPage.tsx`(`StageCell` 컴포넌트, `<th>신고 단계</th>`, `STAGE_DISPLAY`/`STAGE_UNKNOWN` 매핑) · git log `c6d776b3`(2026-08-05, "비식별 신고 단계 구분 + 해소 후 재개 지점 분기 (V171)")
- **조치 제안**: `purpose`와 sections[2] description에서 "구분 표시하지 않는다" 문장을 삭제하고, "신고 단계 컬럼(마킹/라벨링/미상 배지, 각 배지에 해소 시 재개 동작을 알리는 툴팁)이 존재한다"로 정정. `columns` 배열에 "신고 단계" 추가.
- **확신도**: high

### required_roles = ["ROLE-001"] — 정확함(참고, 결함 아님)
- REVIEWER 단일 게이트로 코드와 일치(`GET /v1/deident-reports` `@PreAuthorize("hasRole('REVIEWER')")`). 단, "해소 처리" 버튼이 호출하는 `POST /v1/deident-reports/{rprtSn}/resolve`는 BE에서 `hasAnyRole('WORKER','REVIEWER')`로 더 넓게 열려 있으나, 이 화면 자체가 REVIEWER 전용 라우트라 이 화면에서는 항상 REVIEWER가 호출한다 — 스펙 서술과 실제가 일치하므로 결함 아님(다른 화면에 WORKER용 해소 진입점이 있을 가능성은 SCREEN-032 범위 밖).

---

## SCREEN-035 (라벨 관리)

### [GAP-E13] "속성 정의 패널" 섹션 — 실제 폼 필드가 스펙에 전혀 없음 (최대 규모 GAP)
- **위치**: sections[4] "속성 정의 패널 (LabelAttrDefPanel)"
- **정의서 서술**: 컴포넌트가 List/Button(속성 추가)/IconButton(수정·삭제) 4개뿐이고, description은 "해당 labelId의 속성 목록 조회(API-028) + 추가(API-029)/수정(API-030)/삭제(API-031)"라고만 되어 있다. **폼 자체의 필드 구성은 전혀 서술되지 않는다.**
- **실제**: "속성 추가/수정" 모달(`LabelAttrFormModal.tsx`)은 다음 6개 필드로 구성된 완전한 폼이다.
  1. 속성명(name) — 필수, 최대 64자
  2. 입력 형식(inputType) — SELECT(선택 드롭다운)/CHECKBOX(체크박스 다중)/RADIO(라디오 단일)/NUMBER(숫자)/TEXT(텍스트) 5종 select
  3. 선택 항목(values) — inputType이 SELECT/CHECKBOX/RADIO일 때만 노출되는 동적 리스트(추가/삭제), **최소 1개 이상 공백 아닌 값 필수**
  4. 기본값(defaultVal) — 선택 입력
  5. 작업 중 값 수정 가능 여부(mutable) — "가능(Y)"/"고정(N)" select
  6. 정렬 순서(sortNo) — 0 이상 정수
- 이 필드 구성·조건부 노출 규칙(입력형식에 따라 선택항목 UI 등장)·검증 규칙이 정의서에 단 한 줄도 없어, 3자는 이 섹션만 보고 "속성 추가" 모달을 절대 재현할 수 없다(어떤 필드가 있는지조차 알 수 없음).
- **근거**: `LabelAttrFormModal.tsx`(`AttrForm` 인터페이스, `validate()`, `INPUT_TYPE_OPTIONS`, `MUTABLE_OPTIONS`) · `LABEL_ATTR_INPUT_TYPES` 상수(`features/label/api/labelAttr.ts`)
- **조치 제안**: sections[4].components에 Input(속성명)/Select(입력형식, 5옵션)/동적리스트(선택항목, 조건부)/Input(기본값)/Select(수정가능여부)/Input(정렬순) 를 추가하고 description에 조건부 렌더링·검증 규칙을 기술.
- **확신도**: high

### [CONFLICT-E12] implementation.status="planned"/progress=0 이 brownfield의 기 구현 근거와 모순
- **위치**: `data.implementation` (`{status: "planned", progress: 0}`) vs `data.brownfield`
- **정의서 서술**: `implementation.status: "planned"`, `progress: 0`로 "아직 구현 안 됨"을 시사. 그런데 같은 문서의 `brownfield.diff_summary`는 "1차 관리자매뉴얼 §4.1.3(라벨 클래스 관리)·§4.1.4(속성 정의 관리) 갭 해소 **신규 화면(PR#24, 커밋 cdd649d1)**"이라고 이미 구현·병합된 커밋을 명시하고 있다.
- **실제**: 커밋 `cdd649d1`(2026-07-20, "feat(label): 라벨 클래스·속성 정의 관리 화면(/manage/labels) 추가")은 실재하며, `LabelMasterManagePage.tsx`/`LabelMasterFormModal.tsx`/`LabelAttrDefPanel.tsx`/`LabelAttrFormModal.tsx` 전부 완전히 동작하는 상태로 존재한다(라우트 배선까지 완료). 화면은 명백히 구현 완료 상태다.
- **근거**: `LabelMasterManagePage.tsx`(전체 CRUD 동작) · git log `cdd649d1`(2026-07-20, 실재 커밋, diff에 페이지·모달·훅·라우트 전부 포함) · `router/index.tsx`(`/manage/labels` 라우트 배선)
- **조치 제안**: `implementation.status`를 "implemented", `progress`를 100으로 정정(다른 4개 관리 화면과 동일 패턴). `static_renders`가 비어있는 것(와이어프레임 미등록)과 "구현 여부"는 별개 축이므로 혼동하지 말 것 — 구현은 됐고 렌더 스냅샷만 미등록인 상태.
- **확신도**: high

### [GAP-E14] 라벨 마스터 폼 필드 검증 규칙 + FE/BE 상한 불일치
- 위 "입력 검증 명세 갭 표" 참조. 특히 라벨명 길이(FE 50자 vs BE 64자)는 **정의서에 값 자체가 없어 이 불일치를 재현 시 발견할 방법이 없다** — 3자가 BE `@Size(max=64)` 스펙만 보고 만들면 FE 50자 제한과 어긋난 채 구현될 것이고, 반대로 FE 코드를 참고하지 않고 정의서만 보면 둘 다 알 수 없다.
- **확신도**: high(FE/BE 값 자체는 코드에서 직접 확인) / 정의서 갭 여부는 high

### static_renders가 비어있음 — 참고(낮은 우선순위)
- `static_renders: []`. 사용자가 "와이어프레임 없음"으로 미리 인지하고 있던 부분과 일치. 시각적 목업이 없어도 `data.sections`의 description 밀도가 (속성 패널 제외) 상대적으로 높아 텍스트만으로 재현 가능한 편이나, 위 GAP-E13(속성 패널)은 예외.
- **확신도**: high(사실 확인) — 결함 여부는 별도 판단 불필요(사용자가 이미 인지)

---

## 최중대 3건 (요약)

1. **[ERR-E01] SCREEN-024** — 활성/비활성 전환 다이얼로그 섹션 전체가 폐기된 기능(관제서버로 이관된 useYn 쓰기)을 서술. BE DTO에 필드 자체가 없음에도 정의서는 PATCH payload에 `useYn`을 명시.
2. **[ERR-E11] SCREEN-032** — "신고 단계를 화면상 구분 표시하지 않는다"는 서술이, 정의서 스스로 인용한 2026-08-05 V171 커밋에 의해 이미 사실이 아니게 됨(실제로는 "신고 단계" 컬럼 존재). 자기 인용 근거와 결론이 모순.
3. **[GAP-E13] SCREEN-035** — "속성 정의 패널"의 실제 폼(속성명/입력형식 5종/선택항목/기본값/수정가능여부/정렬순, 조건부 렌더링 포함)이 정의서에 전혀 서술되지 않아 이 섹션만으로는 재현이 원천적으로 불가능.
