# DOMAIN-004 AI 보조 라벨링 — 감사 결과

- 10차원 완주 (SKIP 0)
- 원시 갭 **85건** — P0 12 / P1 43 / P2 30
- 차원별: coverage 8 · links 13 · schema 11 · content 15 · diagram 3 · stale 10 · policy 10 · acceptance 5 · requirement 5 · test_scenario 5

## ★ 지배적 결함 1 — 「YOLOX 단일화(ADR-041)」 cascade 미완

`ADR-041`(2026-07-30)이 RT-DETRv2 를 제거하고 ultralytics(AGPL-3.0)를 배제했다. **ai-server 코드는 제거 완료 + 회귀 가드까지 있는데**(`test_rtdetr_loader_모듈이_삭제됨`, `test_app_routers_yolo에_ultralytics_import가_없음`) **설계 ITEM 과 BE 계약이 안 따라왔다.**

| 대상 | 잔재 | 심각도 |
|---|---|---|
| `API-113` | *"detector_backend 설정에 따라 yolox(기본)/**rtdetr** 로 dispatch"* | P0 |
| `API-119` | 동일 (v1·2026-07-07 등록 후 무갱신 — ADR-041 이후 한 번도 안 고쳐짐) | P0 |
| `API-123` | 응답 스키마 `trackId.description` = *"**ultralytics** 트래커 부여 객체 ID"* (**중첩 필드**라 최상위만 보는 검사기는 구조적으로 놓침) | P0 |
| **BE 구현** | `LabelItemDto.java:73` `@Pattern("YOLO\|SAM2\|**RT-DETR**")` + `LabelService.java:1285` `case "RT-DETR" -> "RT-DETR"` → **제거된 백엔드 이름이 라벨 출처로 DB 영속 가능** | P1·코드 결함 |
| **배포 자산** | `ai-server/README.md:44`·`scripts/download-weights.sh` 가 **`github.com/ultralytics/assets`(AGPL)** 에서 가중치 조달 — ADR-041 의 결정 사유가 정확히 그 회피인데 조달처가 그대로 | P2·**라이선스 축** |

## ★ 지배적 결함 2 — 「VLM `describe` → `verify` 반전」 cascade 미완 (**project-level**)

콜백 규격이 **배열 → 객체 `{accuracy, description}`** 로, 위탁이 **동기 45초 → 논블로킹 제출**로 반전됐다. 여러 도메인·타입에 잔재가 흩어져 있다.

| ITEM | 타입 | 잔재 | 검출 |
|---|---|---|---|
| `API-065` | api_endpoint | request 스키마 `results: array of {start_sec,end_sec,description}`, `maxItems 500`, example 도 배열, 400 사유에 "구간 역전·구간 중복". **`accuracy` 필드가 스키마에 아예 없음.** 같은 ITEM description 은 "객체다·구 배열은 400" → **자기모순** | SCH·CNT·POL 3중 P0 |
| **`INT-002`** | integration_point | **`POST {vlm.client.url}/v1/videovlm/describe`** + **"배치측 block 45s"** | TST |
| **`INT-003`** | integration_point | 콜백 `results[]{start_sec, end_sec, description}` | TST |
| `CDIAG-014` | class_diagram (**DOMAIN-005 소유**) | `VlmResultRequest` = "마킹별 자연어 서술 항목 **배열**"·`vlmMetaItems[]` / `VlmTimeseriesRequest` 에 폐기 필드 `eventName`·`marks` / `VlmClient` = "비동기 위탁(수락만 동기 확인)" | D004 DIAG 가 **범위 밖에서 발견 → D005 로 인계** |
| `CMP-008` | c4_component | `external_dependencies[VlmClient]` = "Resilience4j **45s**". 실측 `application.yml` = `timeout-seconds: 10` + "TimeLimiter 미사용". 본문은 이미 "논블로킹 제출" → 자기모순 | DIAG·STL·POL·CNT 4중 |
| `TEST-002` | test_scenario | steps 입력이 `이벤트명`·`마킹 시점 배열`(현행 스키마에 없음), `status="SUCCESS"`(enum 위반 — 현행 `completed`/`failed`), `metaKey=activity`(현행 화이트리스트는 `vlm.description`/`vlm.accuracy`) | TST |

> **역전 관찰**: `TEST-002` 의 expected 는 *"논블로킹 제출 … 즉시 반환"* 이라 **현행이 맞는데**, 그것이 호출하는 `INT-002` 는 *"block 45s"* 다 — **시험이 계약보다 최신**이다.

## ★ 구조적 공백 — 도메인 책임 R3 에 DFEAT 가 없다

도메인 [구성] 4항목 중 **R3 "SAM2 Track — 박스를 N 프레임에 전파하는 VOS 추적"** 을 담는 DFEAT 가 **프로젝트 전체 0건**이다(`ADR-035` 가 확정한 SFR-08-01=VOS 해석의 핵심 축).
실물은 전부 있다 — `API-020`(sam2-track)·`API-121`·`UC-004`·`AC-004`·`SEQ-005`. **기능 계층만 비어 있다.**

파생 결과:
- `API-020` 을 **어떤 DFEAT 도 implements 하지 않음** (UC-004·SEQ-005 는 본문에서 명시 인용)
- `DFEAT-020`(트랙 모드=선형보간)의 `implemented_by_endpoints` = `[API-125, API-126, API-127]` — **셋 다 `DOMAIN-010` 소속**이고 기능축도 **트랙 편집**(병합·삭제·분할)이라 DFEAT 서술(추적)과 어긋남
- 도메인 API 12건 중 **9건 orphan**

## ★ VLM 축 도메인 귀속 미상 (사용자 결정 필요)

`CMP-008`·`API-065`·`API-122` 가 `DOMAIN-004` 소유(`belongs_to_domain` strong)인데 **도메인 [구성] 4항목 어디에도 VLM 이 없다.** 그 축의 DFEAT·UC·AC 도 0건.
한편 같은 축의 `CDIAG-014` 는 **`DOMAIN-005` 소유**라 **컴포넌트도와 클래스도가 서로 다른 도메인에 속한다.**
→ ①D004 책임 편입 후 DFEAT 신설 ②타 도메인 이관 — 둘 중 확정 필요.

## ★ 연동 ITEM 재분류 cascade 미완

`INT-001`(ai-server 추론 호출)이 **deprecated 인데 `DOMAIN-004` 에 `belongs_to_domain(strong)` 잔존**, `EXTSYS-001`(retired) 참조. **대체 `INFRA-001` 은 도메인 링크 0건.**
정작 `INT-001` 본문: *"연동 자체는 폐기된 것이 아니다 — 라벨링 핵심 기능이라 상시 운용 중이며 enabled 토글조차 없다."*
→ **상시 운용 중인 연동이 그래프상 폐기 ITEM 으로만 표현됨.**
부수: `EXTSYS-001` 은 ITEM `status=deprecated` 인데 **`data.status='active'`** 로 내부 모순.

## P0 (12건)

| gap | 차원 | 대상 | 내용 |
|---|---|---|---|
| D004-POL-001/002/003 | policy | API-113·119·123 | ADR-041 위반 (rtdetr·ultralytics) |
| D004-CNT-002/003 | content | 동일 | 동일 (독립 검출) |
| D004-POL-004 | policy | API-152 | ADR-042 위반 — `MNG_EX_EVNT_TYPE` 현재형 인용. **같은 ITEM 이 옆 필드(cctvId)는 이미 정정해 비대칭** |
| D004-POL-005 / D004-SCH-001 / D004-CNT-001 | policy·schema·content | API-065 | VLM 콜백 스키마가 폐기 `describe` 배열 (3중 검출) |
| D004-COV-001 | coverage | 도메인 R3 | SAM2 Track DFEAT 부재 |
| D004-COV-002 | coverage | API 9건 | orphan |
| D004-LINK-006 | links | DFEAT-019, UC-026, SEQ-001 | deprecated UC 를 활성 DFEAT·approved SEQ 가 참조 (3단 체인) |

## P1 (43건) — 축별 요약

**추적성**: `DFEAT` 3건의 `persists_in_tables`·`invokes_apis`·`triggers`·`related_acceptances` 전건 `[]` / `API` 9건 `implements_features` `[]` / `SEQ` 3건 `invokes_apis`·`participants`·`messages` `[]` / `SCREEN-005` 에 `references_dfeats` 키 자체 부재 / `SCREEN-027.consumes_apis` 7건 중 6건 섹션 미배선

**표준·스키마**:
- `LBL_TYPE_CD` 가 `varchar(16)` — 등록 코드 도메인은 **코드V10·코드V20** 과 코드C1~C12 뿐. 같은 ERD 의 형제 `DTCT_TYPE_CD`·`LBL_SRC_CD` 는 이미 `varchar(20)`. **V107 정합 마이그레이션 대상 목록에서 이 컬럼만 누락**
- `LBL_TYPE_CD` 값집합이 마스터(`{BBOX,POINT,POLYGON}`) vs 데이터라벨(`{BBOX,TRACK,POLYGON,SEGMENT}`) **교집합 2개뿐**인데 `ADR-034` 는 형태 소유를 마스터로 일원화
- `API-177` 응답 enum 에 `SKELETON` 있으나 **ERD code_values 에 없음**
- `TRCK_ID` 타입 3갈래 — ERD `varchar(30)` / `API-123`·`124` `integer` / `API-020` `string`. **변환 지점이 어느 ITEM 에도 없음**
- `API-124` 가 출처 `MANUAL_TRIGGER` 규정 — **ERD code_values·구현 어디에도 없는 사문화 상수**(선언 라인이 유일 매치)
- `API-093` 200 응답이 표준 래퍼와 같은 레벨에 `mock`/`score`/`polygon` **중복 선언** (자기 example 과도 불일치)
- `ERD-019` 가 존재하지 않는 컬럼명 `LABEL_ID` 인용 (실제 `LBL_ID`)

**본문 오염(content 8건·stale 2건)**: `UC-004` 에 폐기설계 경위+FE 코드 표현식 / `UC-005`·`AC-005` 에 `Sam2SegmentService`·`@AssertTrue` / `DOMAIN-004`·`CDIAG-005` 에 내부 클래스·메서드명 / `API-020`·`API-123` 에 *"(구 503 표기 정정)"* 문서 감사 메모 / `API-121` 에 *"D4 Rev1.1 목록에서는 제외, 코드는 구현됨"* / `TEST-002` 에 `VlmSubmitOutcomeRecorder`

**검증 산출물**: `DFEAT-019`(must·구현 100%) **AC 0건** / `REQ-006`(must) 검증 AC 0건 — 도메인 AC 3건 모두 `verifies` 필드 부재(대조군 `AC-024` 는 보유) / UC-004·005·006 **통합시험 0건** / `NFR-009`·`NFR-012` 시스템시험 0건

**brownfield**: `API-020`·`API-065`·`UC-004`·`UC-005` 가 `status=modified` 인데 `legacy_source` 부재 — `diff_summary` 는 "1차 → 2차"를 말하는데 그 1차 식별자가 없음 / `UC-005.decided_by = ADR-001`(작업 단위 전환)로 **주제 무관 오귀속**(ADR-001 스스로 *"deprecated item 의 decided_by 근거"* 용도라 밝힘)

## P2 (30건) — 주요 항목

- `stale=true` 플래그만 (UC-004/005/006 — 원인이 **오늘** 갱신된 `SCREEN-005` 경로 변경이고 본문은 정합)
- 도메인 API 12건 중 **8건에 brownfield 블록 자체가 없음**(67%)
- `implementation.status=planned/0%` 고착 (UC-006·CDIAG-005·CMP-008 — 본문은 운용 중 동작 서술)
- `CDIAG-005.realizes_features` 에 FEAT 가 아닌 **DFEAT ID** 가 들어감 (`depicts_dfeats` 와 중복)
- `AC-006` 만 `derived_domain_ids` 공란 (AC-004/005 와 비대칭 — 서버 자동 계산 미반영)
- `API-177` 이 `PORTAL_USER` 허용 — `ADR-013`(포털 오토라벨링 미제공)과 표면상 충돌하나 **권한 축 진실원은 구현**이라 확인 요청 건
- `SCREEN-027`(영상 업로드, dev 전용)의 `DOMAIN-004` 귀속 타당성
- `RFP-003.related_requirements` 에 deprecated `REQ-011` 잔존

## 반증·정합 확인 (가짜 갭 차단 — 재보고 금지)

| 항목 | 판정 |
|---|---|
| `SEQ-006`(외곽 밀착)이 폐기 '자석 올가미' 축인가 | ❌ **아니다.** 본문에 *"본 흐름은 VOS 해석의 〈분할〉 축이다 — 구 자석 올가미 해석은 2026-06-04 재정정으로 폐기되고 구현물도 제거됐다"* 명시. `SEQ-005`·`SEQ-007` 도 현행 정합 |
| `REQ-006` 에 자석 올가미 잔재 | ❌ 0건. `constraints[5]` 가 이미 ADR-040 정합 |
| 도메인 AC 3건에 폐기 모델 인용 | ❌ 전부 음성 (rtdetr·ultralytics·describe 배열·45초·모달 형태선택·프리셋 토글·라벨명 축 **모두 0건**) |
| `DFEAT-045`(타 도메인)의 `AC-006` verifies | ✅ **의도된 링크.** DFEAT-045 가 `POLYGON_SIMPLIFY_TOLERANCE` 를 소유하고 `AC-006.when` 의 엔드포인트가 그 DFEAT 의 API |
| 활성 ITEM 22건의 retired ID 인용 | ❌ 0건. REST 덤프 후 retired ID **36종** 기계 grep. `ultralytics`·`RT-DETR` 등장은 전부 **"쓰지 않는다/제거했다" 부정문** |
| ADR-024·031·039·019·034·035·040 축 | ✅ 전수 대조 위반 0건 (CMP-008·API-065·API-152·UC-004 등이 각각 정합 인용) |

## 미확인 층

정적 렌더 미러(`source_hash`) · 로컬 키트 스냅샷 · 위키 · 테스트케이스 — 전 차원 미확인. 1차 소스 grep 미수행. `backend/src/test`·`deploy`·`docs` 경로의 RT-DETR/ultralytics 잔재 수 미상.
