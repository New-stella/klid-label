| 항목 | 값 |
|---|---|
| CO 번호 | CO-005 |
| 제목 | 외부 VLM 연동 대상 API를 verify → describe(CoT) + describe-sub(VQA) 로 교체 (KLID 연동 API v1.1.0 정합) |
| 대상 도메인 | 공유기반(`common/`·`batch/`·`webhook/`) · DOMAIN-010(라벨링/VQA·CoT·export) · DOMAIN-003(영상·이벤트유형) · DOMAIN-011(마킹) · mock-server |
| 구현 상태 | ⚠️ 공유기반 구현 완료 (QA 미실행 · 잔여 있음) |
| LogiCraft 설계반영 | 🎨 완료 — 26건 확정 + 시안 렌더 2건 재게시 (2026-08-24) |
| 생성일 | 2026-08-24 |

> ★ 설계를 먼저 확정하고 코드가 뒤따른다(`klid-dispatch` Phase 3.6). §6 이 구현 전에 🎨 가 된다.

---

## §1 배경

저작도구가 실제로 연동하는 외부 VLM(IntelliVIX Video VLM) API는 **KLID 연동 API v1.1.0**(`docs/연동규격서/video_vlm_klid_api_v1.1.0.pdf`) 기준 아래 **두 개뿐**이다 (사용자 확정, 2026-08-24):

- `POST /v1/videovlm-klid/describe` — **묘사(CoT)**. 지정 이벤트 관점에서 영상 상황(장소·날씨·상황 등)을 서술. → **CoT(Chain-of-Thought)** 축.
- `POST /v1/videovlm-klid/describe-sub` — **추가 질문(VQA)**. 지정 이벤트의 발생 여부와 근거를 서술. → **VQA** 축.

두 결과는 event_annotation(**VQA/CoT**) 축에 대응한다 — 이 축의 페이로드가 정확히 `caption`(+`cot` 단계) / `question`·`answer`·`evidence` 구조다(ADR-036, 위키 §24.3.1).

`POST /v1/videovlm-klid/verify`(이벤트 판정, `detected`/`accuracy` 반환)는 **연동하지 않는다.**

**추가로 반영할 것** (사용자 지시): KLID v1.1.0 §5 **연동 유의사항**(콜백 멱등·2xx 5초·재전송 3회·순서 불일치·중복 수신·description 파싱 의존 금지)과 `GET /v1/videovlm-klid/status`(요청 전 서버 처리 가능 상태 확인)도 반영한다.

그런데 현재 코드·LogiCraft·CLAUDE.md 는 전부 **verify** 를 연동 대상으로 전제한다. 게다가 경로가 KLID 접두어 없는 벤더 원본 `/v1/videovlm/verify` 로 박혀 있어 그 자체도 틀렸다. describe/describe-sub 는 코드에 **0건** 구현돼 있다.

**이력**: 이 코드는 원래 `describe` 였다가 2026-08-06 에 `verify` 로 전환됐고(잘못된 방향), 이번에 다시 `describe`(CoT) + `describe-sub`(VQA, 신설)로 되돌리며 KLID 접두어를 붙인다. verify 전환 자체가 오설계였고 이 CO 가 그것을 바로잡는다.

## §2 변경 요지

1. 외부 위탁을 **verify 단일 → describe(CoT) + describe-sub(VQA) 이중 호출**로 교체. 경로에 `-klid` 접두어(`/v1/videovlm-klid/describe`·`/v1/videovlm-klid/describe-sub`).
2. 콜백 결과 스키마를 **`results={description}` 만**으로 정정(describe/describe-sub 는 `detected`/`accuracy` 를 반환하지 않는다).
3. 적재 축: **describe → CoT**, **describe-sub → VQA** — event_annotation(VQA/CoT) 축으로 적재. (기존 시계열 메타 `vlm.description` 을 이 CoT 서술이 계속 채우는지, 혹은 event_annotation 으로 이관하는지는 §4 미확정 ①.)
4. **R12 폐기** — `vlm.accuracy`(검수큐·export 비대상) 정책은 accuracy 필드가 우리 연동에서 사라지므로 관련 코드·문서를 걷어낸다.
5. **서버 상태 조회(`GET /status`) 연동** + **연동 유의사항 준수**(콜백 멱등·2xx 5초·429 재시도·503/415 처리).
6. mock-server 에 `describe-sub`·`status` 라우터 신설 + 전 엔드포인트 KLID 접두어 정합 + verify 목 제거.
7. LogiCraft(INT-002/003/EXTSYS-002 등)·CLAUDE.md 의 verify 구속 서술을 describe/describe-sub 계약으로 정정.

## §3 도메인별 변경 상세

### 공유기반 선처리 (메인 직접, Phase 3.5)

VLM 위탁·콜백 핵심 코드가 `common/`·`batch/`·`webhook/`(공유기반)에 있어 도메인 에이전트가 아니라 **메인이 먼저** 처리한다.

- **대상 파일·심볼**:
  - `common/client/VlmClient.java` — 현재 `VERIFY_PATH="/v1/videovlm/verify"` 단일 호출(`submitTimeseries`). → `DESCRIBE_PATH="/v1/videovlm-klid/describe"` · `DESCRIBE_SUB_PATH="/v1/videovlm-klid/describe-sub"` 두 위탁 메서드 + **`STATUS_PATH="/v1/videovlm-klid/status"` 상태 조회 메서드**. 동기 응답 형식(`{request_id, status:"accepted"}`)·타임아웃·서킷 로직 유지. 단 **429(동시 처리 한도 32건)는 재시도 대상**으로 분류(현재는 전 4xx 를 비재시도로 묶음 — §4 참조). 415/503 처리 추가.
  - `common/client/dto/VlmTimeseriesRequest.java` — 요청 바디: `request_id`/`event_type`/`media{type,source_type,path,frame_policy{mode,selected_frames}}`/`callback_url`. **`framerate` 필드 제거**(KLID §2.5 — 서버가 간격 관리). **`frame_policy` 는 항상 `frame_selected` mode + `selected_frames`**(사용자 확정): ①**마킹 있으면** 사용자가 마킹한 프레임 인덱스 ②**마킹 없으면** 자동 선택된 프레임 리스트. `selected_frames` 상한 **600**(verify 8 → 600, KLID §2.2). 정렬·중복제거·상한 적용은 호출부.
  - `common/client/dto/VlmTimeseriesResponse.java` — 형식 불변(accepted/submitted/skipped sentinel).
  - `batch/step/VlmTimeseriesStep.java` — verify 1회 위탁 → **describe 위탁 + describe-sub 위탁 각각 1회**(각각 별도 `request_id`, request_id → (rawSn, api_kind) 를 `WebhookIdempotencyLedger` 에 등록). `resolveEventType`·frame_policy 도출·신고구간 보류(SKIPPED, ADR-024)·미결 스위퍼·수동 스킵 게이트(ADR-049/050) 로직은 그대로 두 위탁 모두에 적용. `SKIP_REASON_*` 문자열은 재개 배선 키라 **변경 금지**(주석만).
  - `webhook/dto/VlmResultRequest.java` — 콜백 `results` 를 **`{description}` 단일**로 정정(`accuracy` 필드·관련 검증 제거). `description` 필수·≤2000·status(completed|failed)·error 객체 검증 유지.
  - `webhook/service/VlmResultService.java` — 적재 분기: **describe 콜백 → CoT 축**, **describe-sub 콜백 → VQA 축**(둘 다 event_annotation 관련 — §4 확정 후 DOMAIN-010 과 협의). `META_KEY_ACCURACY`(`vlm.accuracy`) 적재 **제거**(R12). 원자 upsert·멱등·트랜잭션 원자성·R13 재검수 게이트 유지.
  - `webhook/VlmResultController.java` — 경로 `/v1/vlm/callback` 유지(describe/describe-sub 공용 수신). **콜백 처리는 2xx 를 5초 내 짧게 반환**하고 무거운 후속은 커밋 후(§5.1) — 현 트랜잭션 구조가 이를 만족하는지 확인.
  - `WebhookIdempotencyLedger`(webhook/idempotency) — request_id → (rawSn, **api_kind**) 저장. kind 컬럼 신설 필요 시 Phase 3.5(표준용어 준수). 중복 콜백은 request_id 로 멱등(이미 구현).
- **불변**: 신고구간 보류(ADR-024) · 미결 스위퍼 · 콜백 무서명 3계층 방어(ADR-031) · 논블로킹 제출(Phase C-1) · request_id 발급 게이트.
- **주의**: describe·describe-sub 는 **각각 별도 request_id** 필수(한 request_id 로 둘을 부르면 콜백 역조회가 깨진다). 동시 처리 한도(32건)·rate limit 를 이중 위탁으로 재점검.
- **수용기준**: describe/describe-sub 위탁이 KLID 접두어 경로로 나가고, 콜백이 `{description}` 만 받아 CoT/VQA 각 축에 적재되며, verify 경로·accuracy 적재가 0건. status 조회 메서드가 동작. 429 는 재시도.

### DOMAIN-010 (라벨링/VQA·CoT·export) — `klid-d010-implementer`

- **대상 파일·심볼**:
  - VQA/CoT 적재: `evntanno/`(event_annotation = VQA/CoT, ADR-036). **describe(CoT) → `caption`/`cot` 축, describe-sub(VQA) → `question`/`answer`/`evidence` 축** 매핑. 현재 event_annotation 은 **수동편집 전용 + 승인시 동결 + 저장→재검수→새 버전**(CLAUDE.md 구속) 시맨틱이라, VLM 콜백 자동 채움을 여기에 붙이는 방식은 §4 미확정 ① 확정 후 구현.
  - R12 폐기: `dataset/export/json/VlmDescriptionPolicy.java` · `NiaJsonBuilder.java` · `VideoMetaMapper.java` · `dataset/export/LabelContentHasher.java`(accuracy 해시 축) · `DatasetExportTxService.java` · `meta/controller/MetaController.java` · `meta/dto/MetaResponse.java` · `transfer/ImportMetaKeys.java` — `vlm.accuracy` 조달·노출·해시 편입 제거. `vlm.description` 시계열 조달순서는 §4 ① 확정에 따름.
- **불변**: 레거시 구간 키(`0-8`·`8-16`) 보존(CLAUDE.md 구속) · event_annotation 승인 동결·재검수 시맨틱.
- **주의**: `vlm.accuracy` 는 데이터마트 뷰에 구조적으로 없어(R12) 제거해도 관제 계약면 영향 0. GET `/v1/frames/{srcSn}/meta` 응답에서 accuracy 행이 빠지므로 프론트 메타 패널 확인.
- **수용기준**: export/메타 응답/콘텐츠 해시에서 `vlm.accuracy` 0건. describe/describe-sub 적재는 §4 ① 확정 후.

### DOMAIN-003 (영상·이벤트유형) — `klid-d003-implementer`

- **대상**: `video/entity/LsDataIngest.java` `VRFC_EVNT_TYPES`(현재 6종). KLID v1.1.0 지원 7종(+`smoke`). → **`smoke` 추가 여부 §4 미확정 ②**.
- **불변**: event_type 은 위탁을 막지 않고 벤더 응답이 수용 여부를 정함(CLAUDE.md 구속).

### DOMAIN-011 (마킹) — `klid-d011-implementer`

- **대상**: `marking/entity/LsMarking.java` — 마킹 상태 전이(VLM_REQUESTED→VLM_FAILED). 이중 위탁 시 "둘 다 성공/일부 실패" 마킹 상태 판정 규칙 확인.
- **불변**: 마킹 완료 → 잔여 배치 트리거 · deIdntfYn='Y' 가드.

### mock-server — 메인 직접

- **대상**: `mock-server/app/routers/vlm.py`·`schemas/vlm.py`·`services/vlm_sim.py`·`tests/test_vlm*.py`
- **변경**: ① 전 엔드포인트 KLID 접두어(`/v1/videovlm-klid/*`) ② `describe-sub` 라우터·콜백 시뮬 신설(결과 `{description}`) ③ describe 콜백 결과를 `{description}` 로 ④ verify 목 제거 ⑤ `GET /events`(7종)·**`GET /status`(`{status, queue, pending}`)** KLID 접두어 ⑥ 429(동시 32건)·503·415 응답 시뮬(연동 유의사항 검증용).
- **주의**: framerate 상한 제거(`le=240`) 유지. 콜백 request_id 멱등·재전송 3회×5초 시뮬(선택).

## §4 영향·리스크

- **하위호환**: 관제 계약면은 `vlm.accuracy` 미노출이라 R12 폐기 관제 영향 0. 레거시 `vlm.accuracy`·구간 키 행은 DB 보존(삭제 마이그레이션 안 함).
- **✅확정 ① — describe(CoT)/describe-sub(VQA) → event_annotation 자동초안 채움 + 수동편집** (사용자 확정 2026-08-24): VLM 콜백이 event_annotation 초기값(describe→`caption`/`cot`, describe-sub→`question`/`answer`/`evidence`)을 채우고 REVIEWER 가 수정·승인. **신규 적재에만 자동채움**을 적용해 재검수 폭주를 피한다(기존 수동편집·승인동결·저장→재검수→새버전 시맨틱 유지). 구현 시 자동채움이 재검수를 발화하지 않도록 배선.
- **✅확정 ② — framerate 제거, 항상 frame_selected + selected_frames** (사용자 확정): §3 공유기반 참조. `uniform` mode 미도입.
- **✅확정 ③ — R12 폐기, accuracy 완전 제거** (사용자 확정): accuracy 신호를 잃는 것을 수용.
- **★미확정(문의 대기) — `smoke` 이벤트(7종째)**: 관제 인입이 smoke 를 보내는지 확인 후(§8 C9).
- **되돌리기**: 코드 revert(verify 는 우리 연동 아님).
- **리스크**: describe/describe-sub 별도 request_id 배선 누락 시 콜백 유실. 이중 위탁으로 외부 호출 2배 → 미결 스위퍼·429·동시 한도 재점검. event_annotation 자동채움이 승인동결·재검수 시맨틱을 건드리지 않게 신규 적재 한정.

## §5 검증

- backend FULL 회귀 — VLM 테스트(`VlmClientTest`·`VlmResultServiceTest`·`VlmResultRequest*Test`·`VlmTimeseriesStep*Test`·`HmacWebhookFilterTest`) 재작성.
- mock-server 기동 + describe/describe-sub/status 왕복 실동작(로컬 mock-server 필수). 콜백 멱등·2xx·429 재시도 케이스.
- `docs/test-cases/G-ai-server.md`·`UNCERTAINTIES.md`·`docs/v2-wiki/09-vlm-timeseries.md` 갱신(같은 커밋).

## §6 관련 설계 ITEM  ★구현 전에 먼저 확정한다

| ITEM | 타입 | 무엇을 어떻게 | 근거 |
|---|---|---|---|
| INT-002 | integration_point | verify 단일 → describe(CoT) + describe-sub(VQA) 이중 위탁 + status 조회. transport_meta.path·실측 계약 절 정정. | 본문이 `/v1/videovlm/verify` 를 실측 계약으로 확정 |
| INT-003 | integration_point | 콜백 results={description} 만(accuracy/detected 제거), CoT/VQA 두 축 수신 + 연동 유의사항(멱등·2xx·재전송) 명시 | verify 콜백 전제 |
| EXTSYS-002 | external_system | 노출 endpoint = describe·describe-sub·events·status | verify endpoint 전제 |
| INTSPEC-003 | integration_spec | INT-002 스펙 아티팩트 — 요청/응답/콜백 스키마 정정 | INT-002 연결 |
| CDIAG-014 | class_diagram | 검수 도메인 VLM 클래스 구조(있으면) | v2.0.1 참조 |
| ADR-036 | adr | event_annotation(VQA/CoT) 이 VLM describe/describe-sub 로 자동 조달되는지 반영(§4 ① 확정 후) | VQA/CoT 축 소유 결정 |
| (신규) ADR | adr | "외부 VLM 연동 대상 = describe(CoT)+describe-sub(VQA), verify 미연동" 결정. 2026-08-06 verify 확정 supersede. | ADR 신설 전 전수 스윕(verify 결정 ADR 유무 확인) |

**cascade 예상 하위**: INT-002/003 `used_by_domains: DOMAIN-005` → 검수 AC·SEQ. R12 폐기 → export/meta AC. describe-sub/describe → event_annotation AC(ADR-036). specialist 위임 시 `analyze_impact` 로 leaf 완주 지시.

**CLAUDE.md 정정 대상**(Phase 6 doc sync): "★외부 VLM 위탁은 verify다" 절 · "metaKey 규격"(vlm.accuracy) · R12 절 · "★event_type 은 위탁을 막지 않는다" 등 verify 전제 서술.

**확정 (Phase 3.6 진행 중 — 2026-08-24)**

| ITEM | 버전 | 반영 내용 |
|---|---|---|
| INT-002 | v15 → **v16** | describe+describe-sub 이중 위탁, framerate 제거, selected_frames 600, status/events 사전확인, 429 재시도·503·415·900초 |
| INT-003 | v13 → **v14** | 콜백 results `{description}` 단일, accuracy 제거, KLID §5.1 유의사항, 두 축 request_id 역조회 |
| EXTSYS-002 | v10 → **v11** | 노출 endpoint 4종(describe·describe-sub·events·status), 계약 KLID v1.1.0 |
| INTSPEC-003 | v8 → **v10** | 위탁 규격 재작성 + 발효일 2026-08-13 |
| INTSPEC-002 | v9 → **v11** | 콜백 규격 재작성 + 발효일 정정(작업일→발효일) |
| API-065 | v15 → **v17** | 콜백 스키마에서 `accuracy` 필드·검증·적재 규정 제거, 400 사유 정정 |
| API-066 | v4 → **v5** | `readOnlyMeta` 에서 일치도 제거(목록은 하위호환 존치) |
| API-067 | v5 → **v6** | 일치도 수정금지 규정 제거(영상 기술 메타 금지는 유지) |
| AC-028 | v5 → **v6** | framerate 폐기, frame_selected 단일, 상한 600, 이중 위탁 |
| UC-022 | v17 → **v18** | 위탁 2엔드포인트, 콜백 단일 서술, 일치도·framerate 폐기, event_annotation 초안 절 신설 |

| UC-019 | v17 → **v18** | 마킹 위탁 입력 도출 규칙 정정(framerate 폐기 표기·이중 위탁·상한 600) |
| SEQ-001 | v16 → **v17** | 파이프라인 위탁 단계 이중 위탁, 콜백 "사건 일치도+서술" → 서술 1건 |
| SEQ-023 | v2 → **v3** | 콜백 흐름 16곳 인용 정리 — 일치도 적재 단계·opt 분기 제거, request_id 축 역조회 신설 |
| DFEAT-039 | v8 → **v9** | 마킹 기능 위탁 입력 규칙 정정 |
| DOMAIN-011 | v6 → **v7** | 마킹 도메인 VLM 연계 문단 재작성 |
| CDIAG-002 | v4 → **v5** | "위탁에 마킹 프레임 간격을 싣는다" 거짓 주장 정정(속성은 존치) |
| CDIAG-014 | v8 → **v9** | 콜백 페이로드 `results` `{accuracy,description}` → `{description}` |
| AC-024 | v6 → **v7** | 서술 전문 단일 축 단언 + event_annotation 자동채움 경계 보강 |
| TEST-002 | v12 → **v13** | 시험 입력자료 정정(framerate 항목 제거·상한 600·이중 제출·results{description}) |
| SCREEN-005 | v96 → **v98** | 일치도 표시 컴포넌트 원소째 제거 + **와이어프레임 렌더 미러 재생성·재게시** |
| SCREEN-019 | v37 → **v39** | 메타 탭 일치도 표시 제거 + **렌더 미러 재생성·재게시** |
| UI-056 | v5 → **v6** | 시계열 메타 편집 패널 서술에서 일치도 축 제거 |
| API-076 | v8 → **v9** | 관제 pull 응답의 일치도가 "과거 적재분에 한한다"로 정확화(스키마 무변경) |
| INT-010 | v9 → **v10** | 뷰에 일치도가 없는 **근거를 교체**(정책 배제 → 신규 미생성), 결론·보존 유지 |
| **ADR-051** | **신규 v1** | **결정 기록 신설** — 위탁 대상을 묘사·추가 질문 두 창구로 확정하고 판정 창구는 쓰지 않는다. 전수 스윕으로 이 결정을 소유한 ADR 이 없음을 확인한 뒤 신설(두 번째 진실원 아님) |
| **API-047** | v10 → **v11** | 마킹 응답 필드 설명 정정 — *"이 값이 외부 위탁의 프레임 정책으로 실린다"* → 실리는 것은 **그 간격으로 산출한 프레임 인덱스 목록**이고 간격값 자체는 싣지 않는다 |
| **SD-002** | v15 → **v16** | 고충실 시안 렌더 재게시 — 일치도 막대 블록 원소째 제거(HTML+CSS) |
| **SD-005** | v5 → **v6** | 고충실 시안 렌더 재게시 — 일치도 블록 제거 + **서술 전문을 읽기 전용으로 정정**(사양과 어긋나던 편집 가능 표기 해소) |

**총 26건 확정 + 시안 렌더 2건 재게시.**

### ★★ 적재 축 확정 (2026-08-24 사용자 확정 — 구현의 핵심 계약)

`LS_DATA_META` 는 `(RAW_SN, META_KEY)` 유니크라 두 축이 같은 키를 쓰면 한쪽이 유실된다. 확정:

| 위탁 | 성격 | 적재 대상 |
|---|---|---|
| `describe` | **CoT** | **기존 시계열 메타 `vlm.description`** — verify 가 채우던 자리를 그대로 이어받는다. 검수큐 진입·export 조달순서 불변 |
| `describe-sub` | **VQA** | **`event_annotation` 의 VQA 축**(`question`/`answer`/`evidence`) 초안. REVIEWER 가 수정·승인. 신규 적재에만 자동채움 |

⚠ 이 확정 이전에 일부 ITEM 에 *"describe(CoT) → caption/cot 축"* 으로 지시한 것이 있다 — **구현 시 위 표가 정본**이며, 어긋나는 ITEM 서술이 발견되면 이 표 기준으로 정정한다.

### 설계 축 종결 (2026-08-24 · 남은 대상 전부 처리)

| 대상 | 결과 |
|---|---|
| **SD-002 · SD-005** | ✅ 처리 — 아래 「시안 렌더 처리 절차」 |
| **신규 ADR** | ✅ **ADR-051** 신설 |
| **API-047** | ✅ v11 — 전수 스윕이 새로 찾아낸 유일한 실 결함 |
| **CMP-002** | ⏸ **정합 대상 아님**(닫음) — 이 C4 컴포넌트도에 위탁 경로가 **애초에 그려져 있지 않아** 폐기 잔재가 없다. 그리는 것은 도해 범위 확대라 별건이다 |
| **저확신 7건**(AC-027·SCREEN-006·SEQ-014·SD-012·NAV-001·UC-021·DOMAIN-005/010) | ✅ 전부 무관 확인 — 아래 전수 스윕 결과 |

#### 시안 렌더 처리 절차 (SD-002 · SD-005)

게시본을 바이트로 내려받아 기준선으로 삼고, 결정론적 치환(각 건수 assert)만 적용한 뒤 업로드했다. 손으로 새 본문을 타이핑하지 않았다.

- **SD-002**: 일치도 블록 삭제뿐이라 문자 단위 diff 가 **`delete` 하나** — 문자 치환이 없으므로 한글 손상이 원리적으로 불가능하다.
- **SD-005**: 세 가지를 함께 고쳤다 — ①일치도 블록 제거 ②안내 문구에서 일치도 삭제 ③**서술 전문을 읽기 전용으로 전환**(`readonly` + 라벨 병기 + 안내 교체). ③은 이번 축과 별개로 시안이 *"서술 전문은 수정할 수 있고"* 라고 적어 사양과 정면으로 어긋나던 것이다.
- **재조회 검증**: 두 렌더 모두 **`replace` opcode 0 · 비공백 차이 0 · CSS 원문 일치**. 게시본에 `일치도`·`accuracy` **각 0건**.
- **로컬 키트 미러 4벌 + 로컬 원본 1벌**을 게시본에 맞춰 갱신했다(서버에 먼저 올린 뒤 로컬을 맞추는 순서 — 반대로 하면 SYNC 가 로컬 편집을 덮는다).
- ⚠ **SCREEN-005 는 로컬 원본이 없어 미러를 기준선으로 썼다.** 게시본이 이미 서버 정규화(SVG 속성명 소문자화 등)를 거친 상태라 그 정규화가 그대로 고정된다. 렌더는 정상이며 되돌릴 원본이 존재하지 않는다.

#### 전수 스윕 — 그래프가 아니라 코퍼스로 판정했다

`kit-export?include_retired=true` 로 **활성·폐기 포함 1,246건 전량**을 받아 7개 패턴(판정 창구 경로·`verify`·일치도·`accuracy`·`framerate`/프레임 간격·`detected`·구 상한 8)으로 훑었다.

- 신호 보유 **50건** → 이번 라운드 처리분 24건을 빼면 26건이 남았고, 그중 **25건이 오탐**이었다.
- 오탐의 정체는 셋이다: ①**상태 전이 검증**(`ReviewStateMachine.verify`)·**토큰 검증**·**백필 절차의 Verify 단계**·**ai-server 내부 객체 검증 endpoint** ②**검색 부분일치도** ③**자동 마킹 프레임 간격**(`intervalFrames`) — 이 축은 우리 자체 설정이라 그대로 유지된다.
- 실 결함은 **API-047 한 건**뿐이었다.
- 구현 기록(IMPREC)에 남은 일치도 언급은 **당시 사실의 기록**이라 소급 수정하지 않는다.

#### 층별 확인 범위 (보지 않은 층을 함께 적는다)

| # | 층 | 결과 |
|---|---|---|
| 1 | ITEM 본문 | ✅ 1,246건 전수 — 실 결함 1건 처리 |
| 2 | 정적 와이어프레임 미러 | ✅ 39 화면 전 렌더 — 이 축 잔재 **0건** |
| 2' | 고충실 시안 렌더 | ✅ 33 시안 전 렌더 — 이 축 잔재 **0건** |
| 3 | 링크·역참조 | ✅ ADR-051 신설 시 미해결 링크 0 |
| 4 | 로컬 키트 스냅샷 | ⚠ **시안 렌더만** 맞췄다. 키트의 화면·API 요약본은 서버 갱신 이전 판이라 **SYNC 로 해소**해야 한다 |
| 5 | 위키 | ❌ **미확인** — 3개 파일에 신호(시계열 위키 28건·로컬 셋업 2건·산출물 24건 3건) |
| 6 | 테스트케이스 | ❌ **미확인** — 6개 파일에 신호(프론트 E2E 32건·AI 서버 23건·배치 23건 등) |
| 7 | 코드 | ❌ **미착수** — 41개 파일에 신호 |

5·6·7 은 구현 단계에서 **같은 커밋**으로 처리한다. 프로젝트 `CLAUDE.md` 의 판정 창구 구속 절도 그때 함께 고친다.

#### 이번 스윕이 새로 찾은 것 — 이 CO 축과 무관 (별건)

- **정적 미러 10건이 `source_hash` 기준 stale** 이다(`SCREEN-008·009·012·022·024·025·027·030·031`). 화면 사양이 바뀐 뒤 와이어프레임을 다시 만들지 않은 것이며, **이 CO 축의 잔재는 아니다**(그 10건에 판정 창구·일치도 신호 0건). 별도 라운드가 필요하다.
- **`design-notes.md` 는 소급 수정 대상이 아니다** — 생성 시각이 박힌 작업 기록이라 그때의 접근성 검토·판단 이력을 담는다. 지우면 무엇을 왜 했는지가 사라진다. 다만 다음 사람이 현재 사양으로 오독할 여지는 남는다.
- **SD-005 의 이벤트 어노테이션 절이 사양과 어긋난다** — 시안은 *"이벤트 유형·시작/종료 시각 등"* 을 검토한다고 적고 시각 입력칸을 실제로 그리는데, 화면 사양은 **"시각(시작·종료) 필드는 없다"** 고 명시하고 분류·질문·답변·캡션·근거를 검토 대상으로 규정한다. **이번 축과 별개**이고 고치려면 그 패널을 다시 그려야 해 임의 진행하지 않았다.

### ★그래프가 놓친 층 — 이번 라운드의 핵심 교훈

`analyze_impact` 는 INT-002/INT-003 의 하위로 **INTSPEC 2건과 SEQ 2건만** 반환했다. 그러나 로컬 키트 전수 스캔에서 verify·일치도 잔재가 **훨씬 넓게** 나왔고, 그 대부분이 그래프에 **링크가 없어** 자동으로는 영영 발견되지 않는다:

- **DOMAIN-005(검수) 축**: AC·CDIAG 가 INT-002/003 과 그래프 미연결(specialist 3건이 각각 독립 보고)
- **ui_component 카탈로그**: `TimeseriesSidePanel` 서술에 "읽기 전용 메타(일치도 등)" 존재
- **고충실 디자인 렌더(SD 층)**: SD-002·SD-005 의 `design-main.html` 에 일치도 표시 요소가 그려져 있음
- **정적 렌더 미러**: SCREEN-005/019 의 wireframe 이 ITEM 본문과 같은 문구를 복제 보유

⇒ **결정 단위 전수 스윕이 필수이며 `analyze_impact` 결과만으로 완료 판정하면 안 된다.** 이 프로젝트가 「층이 통째로 빠진다」로 이미 네 번 뚫린 패턴이다.

### 이번 라운드에서 발견했으나 이 CO 범위 밖인 것 (별도 정합 필요)

- **SEQ-001 의 기존 층 드리프트**: mermaid `source` 에 「전체 건너뛰기 스위치」 서술이 **0건**인데 정형 `messages` 에는 **3건 + fragment 1건**이 있다. 즉 한 ITEM 안에서 두 진실원이 이미 갈려 있다. 이번 축(위탁 엔드포인트·콜백)과 무관해 손대지 않았다.
- **SEQ-001 에 미반영된 INT-002 v16 신설 축**: ①위탁 전 `status`(ready/busy/loading)·`events` 사전 확인 ②429 재시도 분류·503·415 ③분석 제한 900초. 흐름에 그릴지 여부는 미확정.
- **API-076 · INT-010 의 취급**: 두 ITEM 은 일치도를 **관제 계약 근거**로 서술한다. 신규 값은 생기지 않지만 **과거 적재분은 계속 나가므로** 규정을 통째로 지우면 사실과 어긋난다 → **"신규로는 생기지 않는다"로 정정**한다(관제 계약면을 임의로 좁히지 않는다는 구속 규칙 준수).
- **CMP-002 는 「정정」이 아니라 「신규 표현」 결정**: 이 C4 컴포넌트도에는 VLM 위탁이 **애초에 그려져 있지 않다**(마킹 배치 브릿지까지만, framerate·frame_interval·describe 토큰 각 0건). 폐기 잔재가 없으므로 정합 대상이 아니고, 이중 위탁 경로를 **새로 그릴지**가 별도 판단이다 — 도해 범위를 넓히는 일이라 임의 진행하지 않는다.
- **본문 오염 후보(별건)**: `DFEAT-039` 의 `brownfield.diff_summary` 와 description 말미에 *"확정·구현되었다"* 류 **구현 상태·작업 경위 서술**이 남아 있다. 이번 축과 무관해 보존했다. 또 그 ITEM 의 `brownfield.decided_by` 가 마킹 도메인 신규 도입 결정에 머물러 있어 이번 연동 계약 전환 결정과 연결돼 있지 않다.

### 미해결 — stale 표식 자동 해제 (추적 신호 소실)

쓰기가 `stale` 을 자동 해제하므로, 이번 축과 **무관한** 미해결 전파가 표식만 사라진 건이 있다. 원인은 그대로이니 별도 확인 대상이다:

- **AC-028**: `DFEAT-039 의 brownfield 변경이 derived_from 로 2-hop 전파` 표식이 해제됨
- **UC-022**: `SCREEN-005 의 consumes_apis 변경 — references` 표식이 해제됨(SCREEN-005 cascade 에서 함께 처리 필요)

## §8 추가 지원 필요 사항 · 벤더/관제 문의 목록

> KLID v1.1.0 규격을 코드·기존 verify 전제와 대조하며 드러난, **우리가 추가로 지원해야 할 것**과 **확답이 없으면 연동이 깨지는 문의 항목**. Phase 3 게이트에서 우선순위를 정하고, 문의는 관제/벤더에 회신 요청한다.

### A. ✅내부 확정됨 (2026-08-24) — 규격 정합

1. **`framerate` 제거** — KLID §2.5 는 `mode` 만 지정하고 간격은 서버 관리(`frame_policy` 에 framerate 필드 없음). 현 코드의 `framerate`(v2.0.1 verify 규격)를 뺀다. 우리는 **항상 `frame_selected` + `selected_frames`** 로 프레임을 지정한다(마킹 있으면 사용자 마킹 프레임, 없으면 자동 선택 리스트, ≤600). CLAUDE.md framerate 구속 3건 정정.
2. **`selected_frames` 상한 8 → 600** (KLID §2.2·§2.5).
3. **describe/describe-sub 콜백 구분** — 동일 callback_url·동일 스키마이므로 request_id → api_kind 원장 매핑으로 구분(우리 배선).

### B. 우리가 추가로 지원해야 할 것 (규격 준수)

4. **`GET /status` 사전 조회** — 요청 전 서버 처리 가능 여부 확인(ready/busy/loading). loading 이면 위탁 보류·재시도(§5.2). (사용자 지시 반영)
5. **`GET /events` 가용성 확인** — describe_events/describe_sub_events 목록에 해당 event_type 이 있어야 위탁(없으면 벤더가 400). 위탁 전 조회 또는 캐시.
6. **429(동시 32건 초과) 재시도 · 503(미준비) 대기 · 415(Content-Type) 처리** — 현 코드는 전 4xx 를 비재시도로 묶어 429 를 재시도하지 않는다 → 429 만 재시도 대상으로 분리.
7. **콜백 유의사항 준수(§5.1)**: request_id 멱등(구현됨) · **2xx 를 5초 내 반환**(무거운 후속은 커밋 후) · 재전송 최대 3회×5초 견딤 · **요청/콜백 순서 불일치**를 request_id 로 매칭 · **description 은 자연어 평문이라 문자열 파싱 의존 금지**(§5.3).
8. **분석 제한 900초(15분)** — 우리 대기 시간을 이보다 여유 있게. 900초 초과는 실패 콜백(status:failed) → 실패 기록.

### C. 관제 문의

9. **`smoke`(7종째) 이벤트 코드** — 관제 인입(`VRFC_EVNT_TYPE_CD`)이 smoke 를 보내는가? 우리 목록은 6종. → §4 미확정 ②.
10. **event_type 매핑 정합 주체** — 관제 이벤트 코드 ↔ VLM 7종(fire/smoke/fall/violence/flooding/car_accident/kidnapping) 의 매핑을 누가 소유하는가.

### D. 배포·경로 문의 (벤더/인프라)

11. **path 방식 접근성** — path 방식은 서버가 파일을 직접 읽는다(§2.1). 우리 비식별 NAS 경로를 VLM 서버가 공유 마운트로 읽는 전제인가, 아니면 upload(4GB 상한) 방식인가? 현 코드는 path 방식(경로만 전달) 전제.
12. **describe-sub 질문 문장 커스터마이즈 불가(§3.3)** — 이벤트별 질문은 서버가 관리하고 우리가 지정하지 않는다. VQA 요구사항상 특정 질문이 필요한 경우 대응 방안.

## §7 구현 로그

| 일시 | 범위 | 수행 | 결과 | 검증 |
|---|---|---|---|---|
| 2026-08-24 | 설계 선반영(Phase 3.6) | 메인 + `mc-logi-update` | 26건 확정 + 시안 렌더 2건 재게시 | 전수 스윕 1,246 ITEM · 층별 확인표 |
| 2026-08-24 | 공유기반(Phase 3.5) — `common/`·`batch/`·`webhook/` | 메인 직접 | 7파일 수정 + 2파일 신설 | backend FULL 7,399 |
| 2026-08-24 | 어노테이션 초안 반영 — `evntanno/` | 메인 직접 | `EvntAnnoSubResultApplier` 신설 | 위 FULL 포함 |
| 2026-08-24 | mock-server | 메인 직접 | 라우터·스키마·시뮬 재작성 | pytest **371 passed / 8 skipped** |
| 2026-08-24 | 문서 동기화 | 메인 직접 | 위키 3 · 테스트케이스 5 · `CLAUDE.md` | 한글 손상 3축 검사 0건 |

### 검증 결과 — 정직한 기록

| 축 | 결과 |
|---|---|
| backend FULL 회귀 | **7,399 테스트 / 1 실패 / 5 스킵** — 실패 1건은 **기존 플레이크**(아래) |
| mock-server | **371 통과 / 8 스킵** |
| frontend | ❌ **미실행** — `node_modules` 미설치. 단 **`frontend/` 변경 0건**이라 이번 축의 회귀 대상이 아니다 |
| 독립 QA(`klid-qa-verifier`) | ❌ **미실행** — Phase 5.5 잔여 |

**★`ImportControllerIT.가져온_프레임이_열리고_승인하면_학습데이터_산출이_성공으로_마감된다` 는 기존 플레이크다 (이번 변경과 무관 · 증거 있음)**

- **격리 실행 3회 중 1회 실패**했다(전체 회귀 없이 그 클래스만 돌렸을 때). 부하·순서 문제가 아니라 그 시험 자체가 경합을 안고 있다.
- 이 축에서 내가 건드린 파일은 `VlmDescriptionPolicy` **javadoc 한 줄**뿐이고 동작 변경이 0 이다. 시험 본문에 VLM 참조도 **0건**이다.
- 경합의 형태: 승인이 발동한 비동기 산출과 시험이 직접 부르는 동기 산출이 **각각 산출 원장 행을 만드는데**, 시험은 `output_sn DESC LIMIT 1` 로 마지막 행을 읽는다. 비동기 행이 나중에 생겨 아직 `PENDING` 이면 실패한다.
- **이번 CO 범위 밖**이라 고치지 않았다 — 별건으로 다뤄야 한다(DOMAIN-017 축).

### 남은 작업

1. **독립 QA(`klid-qa-verifier`)** — Phase 5.5 미실행.
2. **frontend 회귀** — 의존성 설치 후 1회(변경 0건이라 형식 확인 성격).
3. **§8 문의 12건** — 벤더·관제 회신 대기. 특히 `smoke` 이벤트 인입 여부(§4 미확정 ②)와 path 방식 접근성.
4. **`ImportControllerIT` 플레이크** — 별건.

**미반영·보류 항목**: §4 미확정 ②(smoke 7종째 — 우리 목록은 6종 유지, 목서버만 7종 수용).
