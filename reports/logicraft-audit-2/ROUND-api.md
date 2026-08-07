# 라운드 3 — `api_endpoint` 전수 정합 (2026-08-07)

> 재개 순서: `RESUME.md` → `ROUND-인가IA.md` → **이 문서**.
> 범위·수행방식은 사용자 확정: **162건 전수 · 직접 수행**(에이전트 병렬 아님).

---

## 0. 왜 이 라운드인가

화면정의서·컴포넌트·인가·IA 를 정합한 뒤 남은 최대 잔여다. 화면이 "무엇을 보여줄지"는
정해졌지만 **요청을 어떻게 만드는지는 `api_endpoint` 가 갖는다.** 여기가 틀리면 화면을
정확히 구현해도 서버와 어긋난다.

이 축의 결정적 차이: **실제 구현과 기계 대조가 가능하다.** 화면은 사람 판단이 필요했지만
경로·메서드·권한·응답코드는 컨트롤러·`SecurityConfig`·게이트 호출 그래프에서 뽑아낼 수 있다.

## 1. 도구 (신설)

`bin/audit-api.py` — 결정론적·LLM 0. 6개 축.

```bash
KIT=docs/screen-design/klid-authoring-screens
python3 $KIT/bin/audit-api.py endpoints  $KIT/.staging-api <repo>   # 경로·메서드 대조
python3 $KIT/bin/audit-api.py roles      $KIT/.staging-api <repo>   # 권한(SecurityConfig 사다리 ∩ @PreAuthorize)
python3 $KIT/bin/audit-api.py gate       $KIT/.staging-api <repo>   # 비식별 신고 게이트 응답코드
python3 $KIT/bin/audit-api.py shape      $KIT/.staging-api <repo>   # 계약 완결성
python3 $KIT/bin/audit-api.py symbol     $KIT/.staging-api          # 내부 구현 심볼 오염
python3 $KIT/bin/audit-api.py screenrefs $KIT/.staging-api $KIT/.staging-screen
```

기준선: `baseline/api_before_raw/` (착수 전 162건 원본).
스테이징: `.staging-api`(api_endpoint 162) · `.staging-screen`(screen_spec 31).

### ⚠ 감사기를 만들면서 실제로 걸린 오탐 5종 (다시 밟지 말 것)

| 오탐 | 증상 | 원인 | 교정 |
|---|---|---|---|
| 매핑 어노테이션 | 구현된 TUS 4건이 "정의서에만 있음" | `HEAD`·`OPTIONS` 는 전용 어노테이션이 없어 `@RequestMapping(method=…)` 로 붙는다 | `PAT_M_RM` 추가 |
| 권한 원천 | 불일치 **101건** | 인가 1차 원천은 `@PreAuthorize` 가 아니라 `SecurityConfig` 의 **순서 있는 매처** | 사다리 인코딩 + 교집합 |
| 어노테이션 순서 | `submit`/`start` 역할이 **서로 뒤바뀜** | 이 코드베이스는 `@PreAuthorize` 를 매핑 어노테이션 **뒤**에 단다. 앞만 보면 직전 메서드 것을 집는다 | 앞(빈 줄까지)+뒤(시그니처까지) 양방향 |
| 형식만 센 완결성 | 결손 **80건** | 204 에 example 요구, 본문 없는 POST 에 request_body 요구 | 구현과 대조해 판정 → 20건 |
| 게이트 매칭 | 사용자수정·공지수정까지 게이트 대상 | 메서드명(`update`·`list`)만 보면 전 컨트롤러로 번진다 | 서비스 **클래스 주입 여부**로 결박 |

추가로 게이트 코드 판정에서 두 번 더 틀렸다 — ①호출 뒤 400자의 첫 `ErrorCode` 를 집어
**무관한 404**를 게이트 코드로 오인 ②던지지 않는 게이트(export **보류**)를 412 로 기본값
처리해 검수 승인이 딸려 옴. 지금은 `isUnderDeidentReport` 의 **if 블록 안 throw** 만 본다.

> **교훈**: 이 라운드의 "0건"·"N건"은 전부 **감사기의 시야**다. 수치를 보고할 때마다
> 그 검사가 무엇을 **못 보는지** 함께 적을 것(라운드 2의 세 번 뚫린 이력과 같은 축).

## 2. 결함 인벤토리 (착수 시점)

정의서 **162** / 구현 **172**(backend 167 + ai-server 5).

| # | 축 | 건수 | 성격 |
|---|---|---:|---|
| A | **정의서에 없는 실제 엔드포인트** | **11** | 신설 대상. 화면이 참조조차 할 수 없다 |
| B | 폐기 잔존 | 1 | `API-180` `POST /v1/videos/resolution-backfill` — 백필은 2026-07-30 제거됨 |
| C | **비식별 신고 게이트 응답코드 누락** | **21** | 게이트 통과 26건 중 21건이 412(또는 404)를 안 적었다 |
| D | 권한 불일치 | 18 | `security[]` 가 비었거나(14) 실제보다 좁거나 넓다(4) |
| E | 계약 완결성 결손 | 20 | 2xx example 없음 13 · security 없음 6 · 4xx 없음 1 |
| F | 내부 구현 심볼 오염 | 36 ITEM / 54건 | `…Service`·`…Step`·`…Controller` 등. `…Request`/`…Response` 는 계약 어휘라 제외 |
| G | 구현상태 오염 | 3 | `API-065`·`API-091`·`API-122` — "미구현"·"planned"·"mock 응답" |
| H | 화면 죽은 참조 | 1 | `SCREEN-008` → `API-077`(부재). ※ 화면 쪽 수정 |
| I | 화면 미연결 | 54 | 이 중 웹훅·ai-server·dev 는 정상. **화면이 써야 하는데 안 걸린 것**만 골라낸다 |

**수정 대상 ITEM 합집합 = 79건** + 신설 11건.

### A. 신설 대상 11건 (구현 실측)

| 메서드 | 경로 | 컨트롤러 | 비고 |
|---|---|---|---|
| GET | `/v1/videos/{rawSn}/privacy-meta` | `VideoPrivacyMetaController` | 영상 축 개인정보 3필드(구속 정책) |
| PUT | `/v1/videos/{rawSn}/privacy-meta` | 〃 | **412 게이트 대상** |
| GET | `/v1/manage/event-types` | `EventTypeAdminController` | 표시명 그룹 축 |
| PATCH | `/v1/manage/event-types/{code}` | 〃 | 표시명 변경 → 캐시 무효화 |
| GET | `/v1/assignments/event-types` | `AssignmentController` | |
| GET | `/v1/augments/{jobId}/progress` | `AugmentController` | |
| POST | `/v1/augments/{jobId}/cancel` | 〃 | |
| POST | `/v1/augments/{jobId}/restore` | 〃 | 폐기 복구 |
| POST | `/v1/control-ingests/requeue` | `ControlIngestRequeueController` | REVIEWER |
| POST | `/v1/control-ingests/{sn}/requeue` | 〃 | REVIEWER |
| POST | `/v1/deident/{rawSn}/reprocess` | `DeidentController` | |

### C. 게이트 응답코드 — 판정 결과 요약

게이트 통과 **26건**. 이미 맞는 것 5건(`API-175`·`API-182`·`API-084`·`API-114`, 그리고
`PUT /v1/videos/{}/privacy-meta` 는 ITEM 자체가 없어 신설과 함께 처리).

- **412** = 라벨 조회/저장·라벨 이력·프레임 이미지(2)·버전 diff/rollback·개인정보 메타(프레임 단·벌크)·
  오토라벨·SAM2 분할/추적·YOLO 추적·증강 결과·포털(라벨·이미지·마트라벨·user-labels)·관제 조회·**검수 승인**
- **404** = 영상 스트리밍 2건 (기존 규약 — 신고 여부가 응답코드로 새지 않게)

> `POST /v1/reviews/{videoId}/approve` 가 412 를 낼 수 있다는 것은 **호출 그래프로만 드러났다**
> (승인 → 버전 동결이 게이트를 탄다). 손으로는 못 찾았을 항목이다.

## 3. 판정 규칙 (이 라운드 확정)

1. **권한·응답코드는 구현이 진실원**이다. 정의서를 구현에 맞춘다.
   단 구현이 **확정 정책과 충돌**하면 정의서를 바꾸지 않고 **코드 결함 후보로 보고**한다.
2. **`…Request`/`…Response`/`…Dto` 는 계약 어휘라 본문에 남긴다.** OpenAPI 스키마명 역할이기 때문.
   반면 `…Service`·`…Step`·`…Controller`·`…Guard` 는 내부 구조라 제3자에게 참이 아니다 → 제거.
3. 배열 원소·응답코드를 **삭제하지 않는다**(P8). 추가·정정만 한다.
4. 한글은 이스케이프 없이 **그대로** 입력한다.

## 4. 진행

| 단계 | 상태 |
|---|---|
| 기준선 확보(162) · 감사기 신설 · 인벤토리 확정 | ✅ |
| **P1 — 게이트 응답코드 22** | ✅ **누락 0건** |
| **P2 — 권한 18** | ✅ **불일치 0건** |
| **P0 — 신설 10 · 폐기 1 · 죽은 참조 판정** | ✅ 경로 대조 잔여 1(아래) |
| **P3-a — 구현상태 오염 3** | ✅ **오염 0건** |
| **P3-b — 내부 구현 심볼 35 ITEM** | ✅ **0건** |
| **P3-d — 통지 축(신설)** | ✅ **0건** |
| P3-c — 계약 완결성 **14**(2xx example 12 · 201 example 2) | ⬜ |
| P4 — 화면 연결 보강 | ⬜ |
| 최종 검증(6축 + loss/pollute/hangul) | ⬜ |

### P3-b 결과 — 심볼을 걷어내다가 **폐기된 정책 4건**이 함께 드러났다

본문에서 `…Service`·`…Step`·`…Controller` 같은 내부 구조를 걷어내려면 그 문장을 다시 써야 하는데,
다시 쓰는 과정에서 **문장이 말하는 내용 자체가 낡았다는 것**이 드러났다. 심볼 제거의 부수 효과가
아니라 이 방식의 본질적 이점이다 — 기계 검사로는 못 잡는 층이다.

| ITEM | 폐기된 서술 | 확정 정책 |
|---|---|---|
| `API-065` | `describe` 콜백 · `results` 를 구간 **배열**로 수신 | `verify` · `results` 는 **객체** `{accuracy, description}` |
| `API-172`·`API-173` | *"익명값은 표시·기록용이며 export 를 덮지 않는다"* | 프레임 수동값 **3필드 모두** 산출물 `image` 블록을 결정 |
| `API-170` | *"편집은 export 재생성을 트리거하지 않고 다음 승인에 반영"* | 촬영환경 수정도 **새 버전으로 전량 재생성 후 통지** |
| `API-181` | 필터 옵션을 **카테고리 단위**로 dedup | **표시명 단위**로 접으며 표시명을 지정하면 그룹이 쪼개진다 |
| `API-091` | 자동 재비식별 큐 | 폐기 — 외부 수동 비식별 후 해소 요청이 유일 통로 |

### P3-d — **통지 축**(사용자 지적으로 신설, 2026-08-07)

> *"완료/수정 통지는 검수완료때가 대상인거 잊지말고"* · *"export로 Json 문서 생성도 마찬가지로
> 검수완료때인거야"* · *"export > 완료/수정 통지 이게 세트야"*

**세 문장이 한 규칙이다**: 검수 완료가 대상이고, 산출(export)이 먼저이고, 통지는 그 뒤다.
코드로도 확정된다 — `controlnotify/listener/ControlNotifyEventListener.java(onExportCompleted)`
가 `DatasetExportCompletedEvent` 를 받아 완료 통지를 보내고,
`dataset/service/FramePrivacyMetaService.java(isReviewApproved)` 가 수정 통지를 승인 영상으로 제한한다.

#### ★그 다음 확정 — 수정분의 트리거는 「재검수 승인」이다 (정책 반전)

> *"완료된 영상을 수정하고 다시 검수자가 수정한걸 검수했을때가 트리거가 되어야지"*

내가 처음 쓴 문장은 *"검수 완료된 영상을 **수정했을 때** 재생성되고 통지된다"* 였는데 이건
**2026-07-27 확정 정책 그대로**였다. 사용자가 그 트리거 자체를 뒤집었다.

- **확정**: 완료된 영상을 고치면 **재검수 대상**이 되고, 검수자가 **그 수정을 다시 승인한 시점**에
  산출 재생성 + 통지가 돈다. 수정만으로는 아무것도 나가지 않는다.
- **근거**: 학습데이터는 **검수를 통과한 것만 확정**이다. 구 근거였던 *"파일이 옛 내용이면 동기화
  요구가 성립하지 않는다"* 는 **미검수 내용을 내보내는 것으로 그 요구를 충족시키려 한 것**이라
  방향이 틀렸다 — 동기화의 단위는 확정된 학습데이터다.
- **대가(인지·수용)**: 수정 후 재승인 전까지 관제는 **직전 승인본**을 본다.
- ⚠ **이건 정의서만의 변경이 아니다** — `CLAUDE.md` 「export 재생성·동기화 정책」 절을 함께 반전시켰고,
  **코드는 아직 따라오지 않았다**(아래 §5 구현 갭).

정정 대상: `API-014`·`API-129`·`API-170`·`API-173`·`API-174`·`API-184`.

| ITEM | 무엇이 틀렸나 |
|---|---|
| `API-014` 검수 승인 | *"상태 전이 시점에 완료 통지 발행"* → **산출 성공 후**다. 순서가 뒤바뀌면 관제가 **아직 만들어지지 않았거나 구버전인 산출 폴더를 픽업**한다. 산출 실패 시 통지가 보류됐다 재산출 성공 후 재개된다는 점도 없었다 |
| `API-129` 프레임 설명 | 통지만 적고 **산출 재생성 선행**이 없었다 |
| `API-174` 개인정보 벌크 | **검수 완료 전제 자체가 없어** 저장할 때마다 통지가 나가는 것으로 읽혔다(이 라운드에서 내가 만든 누락) |
| `API-074`·`075`·`076` 관제 조회 | 통지 수신 후 조회라고만 적혀 **조회 대상이 검수 완료 영상**이라는 사실이 없었다 |
| `API-172` 응답 필드 | 본문은 고쳤는데 **필드 설명에만 폐기 정책이 남아** 같은 문서가 서로 반대되는 말을 하고 있었다 |

**재발 방지 — 감사기에 `notify` 축 추가**:

```bash
python3 $KIT/bin/audit-api.py notify $KIT/.staging-api
```

세 가지를 본다 — ①검수 완료 전제 ②산출 선행 순서 ③**재검수 승인 트리거**(수정 시점으로 읽히면 실패).

⚠ 이 검사는 **발행하는 쪽과 받는 쪽을 구분**한다 — 상태를 바꾸는 엔드포인트(POST/PUT/PATCH/DELETE)만
산출 선행 순서를 요구하고, 통지를 받아 조회하는 쪽(GET)은 검수 완료 기준만 요구한다.
*"통지가 일어나지 않는다"* 같은 **부정 서술은 대상에서 제외**한다(안 그러면 `API-148` 이 오탐이 된다).

### §5 — 이 반전이 만든 **구현 갭** (다음 작업)

정의서와 `CLAUDE.md` 는 새 정책으로 갔고 **코드는 아직 구 정책**이다. 지금은 승인 영상이면
**수정 즉시** `TaskModifiedEvent(exportRegenerated=true)` 가 나간다.

| 대상 | 지금 | 바뀌어야 할 것 |
|---|---|---|
| `LabelService` · `TrackEditService` · `TrackMergeService` · `VersionService` · `EnvironmentMetaService` · `FrameDescriptionService` · `FramePrivacyMetaService` | 승인 영상 수정 시 즉시 재생성·통지 | ①수정 시 **재검수로 되돌림** ②**재승인 시점**에 재생성·통지 |
| `VlmResultService`(R13) | 이미 재검수로 되돌림 + **동시에** 통지 | 통지 시점만 재승인으로 이동 |

⚠ 함께 갱신해야 할 것: `docs/v2-wiki`(문서 동기화 규칙) · `docs/test-cases`(동작 변경 시 같은 커밋 규칙).
⚠ **정의서가 앞서고 구현이 뒤처진 상태**를 이 문서에 남겨 둔다 — ITEM 본문에는 구현 상태를 적지 않는다(P0).

### 감사기 오탐 2건 추가 교정

- **추론 서버에 `security` 를 요구**했다 → 그 계약의 사실은 "인증 없음"이지 "미기재"가 아니다(5건 소멸)
- **입력도 인증도 없는 프로브(`/health`)에 4xx 를 요구**했다 → 거부될 여지가 없는 엔드포인트다(1건 소멸)

### P0 결과 — 신설은 11 이 아니라 **10** 이었다

`POST /v1/deident/{videoId}/reprocess` 는 **이미 `API-053` 으로 있고 폐기 상태**였다.
즉 정의서 갭이 아니라 **코드 쪽 잔존물**이다 — 폐기된 계약의 빈 placeholder 핸들러가 라우팅에만
남아 있다. 신설하지 않았고 `API-053` 도 폐기 상태 그대로 둔다. (코드 정리는 별건 → §5)

| 신설 | 엔드포인트 |
|---|---|
| `API-183`·`API-184` | 영상 개인정보 메타 조회·저장 (저장은 412 게이트 포함) |
| `API-185`·`API-186` | 이벤트유형 관리 조회·정정 |
| `API-187` | 배정 화면 이벤트유형 필터 옵션 |
| `API-188`·`API-189`·`API-190` | 증강 진행률·취소·폐기 복구 |
| `API-191`·`API-192` | 관제 인입 재큐(단건·일괄) |

`API-180`(해상도 백필) 폐기 처리 — 1회성 운영 배치라 실행 통로가 이미 없다.

### 죽은 참조는 결함이 아니었다

`SCREEN-008` → `API-077` 참조 3건은 **전부 `[폐기]` 섹션 안**이다. 채택하지 않은 설계와 그
설계가 쓰던 엔드포인트를 함께 보존한 기록이므로 정상이다. 감사기가 이를 구분하지 못해
매 라운드 재보고할 구조였어서 `screenrefs` 를 **유효 섹션 / 폐기 섹션 2단 보고**로 고쳤다.

### 부수 성과 — 폐기된 외부 계약이 본문에 살아 있었다

`API-065`(VLM 콜백)가 **폐기된 구 규격**(`describe` 호출 · `results` 를 구간별 **배열**로 수신)을
그대로 기술하고 있었다. 실제 계약은 `verify` 이고 `results` 는 **객체** `{accuracy, description}` 다
(근거: `webhook/dto/VlmResultRequest.java(Results)` — `accuracy` `0~1` · `description` ≤2000 단일 필드,
`webhook/service/VlmResultService.java(META_KEY_DESCRIPTION · META_KEY_ACCURACY)`).
**이 본문대로 구현하면 배열 파서를 만들어 벤더 페이로드를 통째로 거부한다.**
같은 수정에서 적재 키 규격(검수큐 진입은 서술 1건뿐)도 명시했다.

`API-091`(마킹 단계 비식별 신고)에서는 **폐기된 자동 재비식별 큐** 서술을 걷어내고,
접수 조건(마킹 대기 상태에서만)과 해소 후 재개 지점(마킹부터 다시)을 명시했다.

### P1·P2 검증 결과 (재다운로드 후)

- 게이트 축 **누락 0건**(게이트 통과 27건 중 27건 정합) · 권한 축 **불일치 0건**(대조 156건)
- 콘텐츠 감소 **0건**(응답코드·파라미터·태그·설명 길이 전항목) · 한글 손상 **0건**
- ⚠ `security` 는 감소 검사에서 제외했다 — `API-021`·`API-022` 의 포털 회원 제거는 **의도된 정정**이다
  (내부 채널 전용 경로라 그 역할로는 호출 자체가 불가능하며, 남겨두면 구현할 수 없는 계약이 된다).

### ⚠ 이 라운드에서 내가 만든 사고 2건 (같은 실수 반복 방지)

| 사고 | 원인 | 발견 | 조치 |
|---|---|---|---|
| `API-173` 본문 한글 손상(`뜨`→`뜼`) | 도구 인자에 한글을 **유니코드 이스케이프로 직접 작성**했다. 깨진 글자가 아니라 읽히는 다른 글자가 된다 | 희귀음절 검사 | 정정 + 이후 한글은 그대로 입력 |
| `API-137` 설명 **720자 → 200자 절단** | 손상 위치를 찾을 때 본문을 `[:200]` 으로 잘라 출력해 놓고, **그 잘린 화면을 원문으로 착각**해 통째로 교체했다 | 기준선 길이 대조 | 원문 전량 복원(720자 일치, `곳`→`곧` 1자만 차이) |

교훈: **"고칠 때가 더 위험하다."** 표기 1자를 고치려다 문단 4개를 날렸다.
전체 교체(`merge` 로 문자열 필드 덮어쓰기) 전에는 **반드시 기준선 길이를 먼저 확인**할 것.
그리고 기존 손상(`API-137` 의 `곳`)은 이번에 내가 만든 것이 아니라 **이전 라운드부터 있던 것**이다 —
희귀음절 검사를 매 라운드 돌리는 이유가 이것이다.

**P0 에서 같은 사고가 2건 더 났다** — 신설 10건에 또 이스케이프를 써서 `API-186` 의 `컬럼→컴럼`,
`API-192` 의 `곧바로→곳바로` 를 만들었다. ⚠ `컬럼→컴럼` 은 CLAUDE.md 가 **실사례로 명시해 둔 바로 그 오타**다.
즉 규칙을 알고도 세 번 반복했다. **도구 인자에 한글을 쓸 때는 이스케이프를 쓰지 않는다** —
`change_summary` 를 한글 그대로 쓴 회차에서는 손상이 한 건도 나지 않았다.
검사 절차: 등록 직후 **그 ID 범위만** 희귀음절 검사 + 알려진 오타 문자열 grep 을 함께 돌린다
(전수 검사는 배경 잡음이 많아 신규분 손상이 묻힌다).

---

## §6 계약 완결성 · 화면 연결 (2026-08-07 완료)

### 최종 상태 — 8축 전부 0건

| 축 | 결과 |
|---|---|
| 경로·메서드 | ① 정의서에만 있음 **0건** / ② 구현에만 있음 **1건**(폐기 계약 `API-053` 의 잔존 핸들러 — §5 코드 갭) |
| 권한 | 대조 166건 **불일치 0건** (구현 부재 5건 건너뜀) |
| 게이트 응답코드 | **누락 0건** |
| 계약 완결성 | 대상 171건 **결손 0건** |
| 통지 축 | **0건** |
| 내부 구현 심볼 | **0건** |
| 오염(api·screen) | **각 0건** |
| 한글 손상 | 편집 ID 범위 **0건** (희귀음절 + 알려진 오타 문자열) |
| 화면 참조 | ① 죽은 참조 **0건** / ② [폐기] 섹션 참조 1건(정상) / ③ 미연결 **63 → 35건** |

### 진행 상태 (구 표)

| 축 | 상태 |
|---|---|
| 경로·메서드 / 권한 / 게이트 / 심볼 / 오염 / 한글 / 통지 | ✅ **전부 0건** |
| 계약 완결성 | ✅ **0건** (아래 10건 처리 완료) |
| 화면 연결(P4) | ✅ 28건 연결 |

`shape` 축을 **두 층으로 나눠 다시 셌다** — `example 없음` 만 보던 검사가 더 큰 결함을 가리고 있었다:
**2xx 응답 스키마 자체가 빈 것 9건**(응답을 파싱할 수 없다) + example 만 없는 것 5건.
`audit-api.py shape` 에 `"2xx 스키마 없음"` 판정을 추가했다.

### 남은 14건과 **이미 파낸 코드 사실** (재조사 불필요)

스키마 없음 9건 — 컨트롤러 반환 타입 실측:

| ITEM | 엔드포인트 | 응답 타입 | 필드 |
|---|---|---|---|
| ✅ `API-025` | POST /v1/manage/labels | `LabelMasterResponse` | 완료 |
| ✅ `API-032` | POST /v1/labels/{srcSn}/deident-report | `ApiResponse<Long>`(신고 PK) | 완료 |
| ✅ `API-047` | POST /v1/videos/{rawSn}/markings | `MarkingResponse` | 완료 |
| ✅ `API-082` | POST /v1/portal/user-labels | `PortalUserLabelResponse` | 완료 |
| ⬜ `API-070` | POST /v1/assignments | `AssignmentResponse{items[Item]}` | Item 21필드: `id · authrtSeq · workerId · videoId · videoTitle · cctvName · workerName · taskType · rawDataId · taskTypeCd · regUserNo · regDt · assignedAt · reviewerId · reviewerName · firstSrcSn · eventName · eventTypeCd · status · augmented(boolean) · augType` |
| ⬜ `API-102` | POST /v1/videos/{rawSn}/issues | `IssueThreadResponse` | `issueSn · issueTypeCd · issueSttsCd · srcSn · reason · reportedUserNo · reportedUserName · regDt · comments[IssueCommentResponse]` |
| ⬜ `API-104` | POST /v1/issues/{issueSn}/comments | `IssueCommentResponse` | `commentSn · authorNo · authorName · authorRoleCd · content · regDt` |
| ⬜ `API-106` | POST /v1/notices/{id}/attachments | `NoticeAttachResponse` | `attachSn · fileName · fileSize(long) · regDt` |
| ⬜ `API-158` | POST /v1/uploads | **`ResponseEntity<Void>`** | ⚠ **본문이 없다** — 201 + `Location: /v1/uploads/{uploadId}` · `Tus-Resumable` 헤더가 계약이다. example 을 채우는 게 아니라 **본문 없음 + 헤더 계약**으로 고쳐야 한다 |

example 만 없는 5건: `API-094`(해소 — data null) · `API-109` · `API-115` · `API-116` ·
`API-163`(포털 TUS create — **`ResponseEntity<Void>`**, `API-158` 과 같은 처리).

참고 — `MarkItem` = `frameIndex(0-base, 필수)` + `timestamp(mm:ss 또는 mm:ss:ff)`.

### P3-c 처리 결과 (10건)

| 유형 | ITEM | 한 것 |
|---|---|---|
| 2xx 스키마 없음 | `API-070`·`API-102`·`API-104`·`API-106` | 비어 있던 `200` 을 필드 단위 스키마 + 예시로 채움 |
| 계약에서 빠져 있던 필드 | 〃 | `API-070` `augmented`·`augType` / `API-102` `reportedUserName` + `comments[]` 원소 형태 / `API-104` `authorName` |
| 본문 없는 201 | `API-158`·`API-163` | 예시가 아니라 **헤더 계약**(`Tus-Resumable`·`Location`)으로 명시 + 응답 미디어 타입 비움 |
| 서술만이던 스키마 | `API-109` | 한 줄 요약 → 9필드 전개(빠져 있던 `reporterName`·`stage` 포함) + 예시 |
| example 없음 | `API-094`·`API-115`·`API-116` | 예시 추가(배정 이력은 배정·재배정 2행으로 널 갈림을 보임) |

**부수 정정** — `API-070` 의 `cctvName` 조달처가 **`MNG_RESOURCE_CCTV`** 로 적혀 있었다.
그 테이블은 제거됐고 실제 조달처는 관제 인입 원장의 `CCTV_NM`(비면 `VMS_CCTV_ID` 폴백)이다.

> ⚠ **`200` 을 채운 판단 → 같은 날 재정리됨(아래 §7-3)**. 이 4건은 구현이 **201 만** 반환한다
> (`ResponseEntity.status(CREATED)` / `@ResponseStatus(CREATED)`). `200` 은 8개 POST ITEM 에만 남은
> 잔존 스텁이었고, 지금은 8건 전부 **`[폐기]` 표기**로 정리했다.

### P4 결과 — 화면 연결 28건 (미연결 63 → 35)

연결 근거는 전부 **프론트 호출부 실측**이다(추정 연결 없음).

| 화면 | 연결한 API |
|---|---|
| SCREEN-005 라벨링 캔버스 | `API-012`·`API-178`(검수 제출·취소) · `API-022`·`API-023`(라벨 속성값) · `API-168`·`API-170`(촬영환경) · `API-172`·`API-173`(프레임 개인정보) · `API-183`·`API-184`(영상 개인정보) · `API-177`(AI 탐지 후보) — **11건** |
| SCREEN-006 마킹 | `API-114`(서명 URL) · `API-084`(스트리밍) — 재생 요소가 인증 헤더를 못 붙여 한 쌍 |
| SCREEN-012 작업 목록 | `API-116`(배정 이력) · `API-187`(이벤트유형 필터 옵션) |
| SCREEN-022 증강 요청 | `API-179`(해상도 파생 확정 상태) |
| SCREEN-023 증강 결과 | `API-188`·`API-189`·`API-190`(진행·취소·폐기 복구) · `API-175`(비식별 프레임) |
| SCREEN-024 사용자 관리 | `API-003` |
| SCREEN-026 프리셋 관리 | `API-117`(이벤트 코드 표시명 맵) |
| SCREEN-027 오토라벨 테스트(개발) | `API-156`·`API-158`·`API-160`·`API-162`·`API-164`(TUS 5종) |
| SCREEN-033 포털 업로드 | `API-161`(포털 TUS 능력 광고) |

⚠ `SCREEN-005` 의 `change_summary` 가 **"12건"** 이라고 적혔으나 실제 추가는 **11건**이다
(`API-174` 벌크 개인정보 메타는 호출부가 없어 제외했는데 문장에서 빼지 못했다).
리비전 로그는 소급 수정할 수 없어 사실만 여기 남긴다.

#### 연결하지 않은 35건 — 사유별

> ※ 아래 표는 P4 시점 기준이다. `API-185`·`186` 은 §7-1 로 `SCREEN-038` 에 연결됐고,
> `API-191`·`192` 는 §7-2 로 **화면 없음이 확정**됐다.

| 사유 | ITEM | 판단 |
|---|---|---|
| 웹훅(외부→우리) | `API-065`·`API-165` | 화면 없음이 정상 |
| 추론 서버 | `API-113`·`API-119`~`122` | 〃 |
| 프로브 | `API-141` | 〃 |
| 개발·검수 전용 배치 | `API-143`~`146`·`148`·`150`·`152`·`153` | 〃 |
| 관제 inbound 조회 | `API-074`·`075`·`076` | 관제서버가 호출 |
| **화면 UI 가 폐지됨** | `API-016`·`API-017`(메타 검수 승인·반려) | 라벨링 화면에서 승인/반려 UI 를 제공하지 않기로 확정(2026-08-03)돼 클라이언트가 제거됐다. **계약은 존치**이므로 연결하면 없는 화면 기능을 만들어 낸다 |
| **호출부 없음** | `API-005`·`006`(내 정보) · `045`(오토라벨 요약) · `046`(프레임 이미지) · `081`·`083`(포털 마트/내 라벨) · `118`(스케줄러 상태) · `147`(포털 업로드 프레임) · `167`(배치 재처리) · `174`(개인정보 벌크) | 어느 화면이 써야 하는지 **정할 근거가 없다** — 지어내지 않고 남긴다 |
| **화면 ITEM 자체가 없음** | `API-185`·`API-186`(이벤트유형 관리) | 아래 |
| 화면·호출부 모두 없음 | `API-191`·`API-192`(관제 인입 재큐) | 아래 |

## §7 미결 3건 처리 (2026-08-07, 사용자 지시 "남긴 내용 진행")

### 7-1. 이벤트유형 관리 화면 신설 — `SCREEN-038`

구현에는 `/manage/event-types` 화면이 실재하는데 `screen_spec` 이 없었다. 표시명 지정이
「표시명 그룹 축」에서 그룹을 쪼개는 조작이라 사양 없이 두면 그 파급을 모르는 채 이름을 바꾸게 된다.

- **`SCREEN-038` 신설** — 섹션 2(페이지 헤더 · 목록 테이블), `consumes_apis`=`API-185`·`API-186`,
  `required_roles`=`ROLE-001`, `device: desktop`, `implementation.status: implemented`.
  본문에 못박은 것: ①생성·삭제를 두지 않는다(등록의 유일한 출처가 관제 인입) ②표시명 4단 폴백은
  서버가 단독 해석하고 화면이 재계산하지 않는다 ③표시명 지정이 필터 그룹을 가른다(영구 병합 아님)
  ④표시명을 비우면 관제 수신명으로 복귀 ⑤저장 요청은 표시명·수집여부만 싣는다.
- **와이어프레임** 생성기로 산출 후 업로드(`main`/page/1440). 업로드본과 로컬 산출물을 diff 해
  차이가 **서버측 sanitize 4곳**(`<html>` 속성·css 링크·self-closing·엔티티 복원)뿐임을 확인했다.
- **파급 3곳 동시 반영** — `NAV-001` 관리 메뉴에 노드 추가(`manage-event-types` → `SCREEN-038`) ·
  `SHELL-001.applies_to_screens` 추가 · `ROLE-001` 권한 항목 + 설명의 관리 화면 나열 갱신.
  화면만 만들고 이 셋을 빠뜨리면 **메뉴에 없어 주소로만 도달하는 화면**이 된다.

> ⚠ 납품 FE 라우터에는 이 경로가 **없다**(테스트베드에만 있다). 「기능의 존재 여부는 합집합」
> 원칙에 따라 포함했다 — 라우트 정본 규칙은 *같은 기능의 표현이 갈릴 때* 적용되는 것이라
> 한쪽에만 있는 기능을 지우는 근거가 아니다.

### 7-2. 관제 인입 재큐 — **운영 전용으로 확정**(화면 두지 않음)

당초 "영상 처리 현황 화면에 배치"를 권했으나, **API 를 열어 보니 그 안이 성립하지 않았다.**
재큐 대상은 `LS_DATA_INGEST` 의 **종결 행**이고 그 행들은 **영상으로 적재되기 전 단계**라
`LS_DATA_RAW` 행이 없다 — 영상 목록 화면에 애초에 나타나지 않으므로 목록에서 고를 대상이 없다.
새 화면을 만드는 것은 구현이 전혀 없는 상태에서의 창작이라 「추정 금지」에 걸린다.

→ `API-191`·`API-192` 본문에 **화면 동선을 두지 않는 이유**를 명시했다(운영자 직접 호출 경로).
적지 않으면 다음 라운드가 "화면 연결 누락"으로 재보고한다.

### 7-3. 잔존 `200` — 8건 **`[폐기]` 표기**로 정리

8개 POST(`API-025`·`032`·`047`·`070`·`082`·`102`·`104`·`106`) 전부 구현이 **201 만** 반환함을
컨트롤러에서 확인했다. `200` 의 `description` 을 *"[폐기] 이 엔드포인트는 200 을 반환하지 않는다 —
성공은 201 하나뿐이다"* 로 바꿔, 오지 않는 코드를 성공으로 기술하던 상태를 없앴다.

> **왜 지우지 않았나 (도구 제약)**: `patch` 다이얼렉트가 **숫자 키 세그먼트를 거부**한다
> (`responses.200` → `E_PATCH_PATH: invalid segment '200'`). 실제로 시도해 확인했다.
> 남은 수단은 `data_mode: replace` 로 ITEM 전체를 재전송하는 것뿐인데, 그건 이 라운드에서
> **720자 설명을 200자로 절단**시킨 바로 그 조작이다. 8건에 그 위험을 지는 대신
> §2-A 의 기존 관례(`[폐기]` 표기 + 사유 보존)를 택했다. 스키마·예시는 남아 있어
> `shape` 검사도 통과한다(결손 0 유지).

### 최종 재검증 (7-1~7-3 반영 후)

경로 ①0건 / 권한 0건 / 게이트 0건 / 완결성 0건 / 통지 0건 / 심볼 0건 /
오염 api·screen·nav **각 0건** / 편집 ITEM 손실·절단 **0건** / 한글 손상 0건.
화면 미연결 **35 → 33건**(`API-185`·`186` 연결).

### ⚠ 이 회차에서 한글 이스케이프 사고 2건 (네 번째·다섯 번째)

| 건 | 손상 | 발견 경로 |
|---|---|---|
| `API-106` | `첨부 등록 일시` → **`체부 등록 일시`** | 등록 직후 되읽기 |
| `API-192` | `곧바로` → **`곲바로`** | **기준선 대비 문자 단위 diff** |

원인은 같다 — 도구 인자에 한글을 이스케이프로 직접 작성했다. 규칙(`.claude/rules/logicraft-integration.md` §3)을
읽고 착수한 회차에서도 두 번 재발했다. **"쓰기 전에 규칙을 아는 것"만으로는 안 막힌다.**

두 번째 건이 특히 중요하다 — `API-192` 는 **희귀음절 검사에 걸리지 않았다**(`곲` 이 그 배치의
1회 등장 목록에 들어갔지만 눈으로 훑는 형식이라 놓칠 수 있었다). 실제로 잡은 것은
**"기준선과 문자 단위로 비교해 변경분이 순수 삽입인지 확인"** 하는 절차였다.
문자열 필드를 통째로 다시 쓸 때는 이 diff 를 **반드시** 함께 돌린다 —
`replace`/`merge` 로 문자열을 덮어쓰면 절단(길이 감소)뿐 아니라 **같은 길이의 1자 치환**도 난다.
`곧`→`곳` 은 이전 라운드에도 있었던 자리다. 같은 낱말이 세 번째로 깨졌다.

### 재개 명령

```bash
KIT=docs/screen-design/klid-authoring-screens
R=/Users/ck/orca/workspaces/klid-label/upload-ui
DL=~/.claude/plugins/cache/logicraft/mc-logi-screen-kit/1.2.0/skills/mc-logi-screen-kit/bin/download-kit.mjs
KEY=$(python3 -c "import json,os;d=json.load(open(os.path.expanduser('~/.claude.json'),encoding='utf-8'));print(d['projects'][os.path.expanduser('~/Documents/workspace/klid/klid-label')]['mcpServers']['logicraft']['headers']['Authorization'].split()[1])")
LOGICRAFT_API_KEY=$KEY LOGICRAFT_API_BASE=https://logicraft.cudo.co.kr:10000/api \
  node $DL --project 4ece2c3f-8e99-46f5-9580-71108a76e578 --out $KIT/.staging-api --types api_endpoint
for c in endpoints roles gate shape notify; do python3 $KIT/bin/audit-api.py $c $KIT/.staging-api $R; done
python3 $KIT/bin/audit-api.py symbol $KIT/.staging-api
python3 $KIT/bin/verify-items.py pollute $KIT/.staging-api
```

### ⚠ 코드는 지금 건드리지 않는다 (사용자 확정)

*"코드는 logicraft 가 전부 반영 된 후에 한번 전체 수정을 진행할거야"* —
LogiCraft 정합이 끝난 뒤 코드를 **한 번에** 고친다. 그때까지 누적할 코드 갭:

1. **통지·산출 트리거 반전**(§5) — 7개 서비스 + `VlmResultService`(R13)
2. **`POST /v1/deident/{videoId}/reprocess` 잔존 핸들러** — 계약(`API-053`)은 폐기인데 빈 placeholder 가 라우팅에 남아 있다
