# 20. 외부 시스템 (v2 연동 · 범위 외)

> **목적**: v2(저작도구)가 말하는 "외부 시스템"이 무엇인지, 어떻게 연동하고 어디까지 책임지는지 정리.
> 근거: 루트 [`CLAUDE.md`](../../CLAUDE.md) + v2 코드 조사(`common/client/`, `webhook/`, `controlnotify/`, db migration) — 2026-06.
> 관련: [18 v1↔v2 비교](18-v1-v2-comparison.md) · [19 갭 체크리스트](19-v2-gap-checklist.md)
>
> ⚠ **정합 갱신(2026-08-19 코드 실측)** — 이 문서는 작성 이후(2026-06) 이번 라운드 전까지 다른 위키 정합 작업에서
> 배정되지 않아 낡은 서술이 그대로 남아 있었다. 아래 각 절의 표·서술을 실제 코드와 대조해 정정했다(옛 서술은
> 삭제하지 않고 각주로 인용·폐기 표기했다). 근거는 각 항목에 `파일명(심볼명)`으로 남긴다.

"외부 시스템"은 두 종류다.
- **A. 연동 외부 시스템** — v2가 실제로 호출/통신하는 시스템
- **B. 범위 외 시스템** — v2가 책임지지 않고 외부에 위임한 시스템 (연동 어댑터만 있거나 아예 없음)

> 참고: **ai-server(YOLO/SAM2/VLM 추론)**는 외부가 아니라 **같은 모노레포 내부의 별도 프로세스**다. stateless 추론만 담당하며 `AiServerClient`로 호출한다. (배포만 분리, 책임은 저작도구)

---

## 20.1 관계도

```
                         ┌─────────────────────────────────────────┐
   [관제서버] ──JWT 인계──▶│                                         │
   (control)  ◀─outbound──│         v2 학습 저작도구 (klid-label)    │
              통지(TASK_*) │   Spring Boot + ai-server(내부) + FE     │
              ◀─조회 API──│                                         │
                         │  common/client/*  ·  webhook/*          │
   [포털서버] ──JWT 인계──▶│                                         │
   (portal)   ◀─포털 DB───│                                         │
                         └───┬───────────┬───────────┬─────────────┘
            호출/콜백 │           │           │
                    ▼           ▼           ▼
            [비식별화 서버]  [외부 VLM]   [외부 증강/생성]
            DeidentifyClient VlmClient   ExternalAugmentClient

   ── 범위 외 (책임 위임, 연동만 또는 미연동) ──
   데이터마트(구축·검색·다운로드) · 생성형 AI 본체 · VLM 모델 본체 · 영상 합성 모델 본체
```

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 위 다이어그램은 3곳이 사실과 다르다.
> ① *"DeidentifyClient"* — 이 클래스는 코드에 **없다**. 과거 `WebClientConfig.deidentifyWebClient`
> (`authoring.integration.deidentify.base-url`)·`Resilience4jConfig.deidCircuitBreaker` 빈이 있었으나
> **주입처 0건인 죽은 빈**이다(`DeidentifyHealthIndicator` 클래스 javadoc이 이 사실을 명시). 현재
> 비식별 연동의 실제 클라이언트는 **`KpstDeidentifyClient`**(`common/client/KpstDeidentifyClient.java`)다.
> ② *"[포털서버] ◀─포털 DB───"* — 화살표 방향이 반대다. 포털 라벨 조회·저장 경로(`PortalLabelService` 등)는
> `controlTransactionManager`로 묶여 **저작도구(control) DB 를 읽고 쓴다**. `@PortalRepo`를 쓰는 곳은
> `PortalDatasetVideoMetaRepository`(메타 복제 writer) **1곳뿐**이며 방향은 **저작도구 → 포털 DB
> 단방향 쓰기**(at-least-once 복제)다. 즉 포털 DB 는 우리가 읽는 곳이 아니라 **우리가 내보내는 곳**이다.
> ③ *"── 범위 외 … 학습데이터 Export"* — **Export(NIA JSON 산출)는 저작도구 범위 안**이다. 아래
> §20.3 표에서 상세 정정한다. 근거: `DeidentifyHealthIndicator.java`, `common/client/` 디렉터리 목록,
> `PortalDatasetVideoMetaRepository.java`, `dataset/export/*`.

---

## 20.2 A. 연동 외부 시스템

### A-1. 관제서버 (control server)

| 항목 | 내용 |
|------|------|
| 역할 | 내부 채널 JWT 발급(로그인 인계), 영상/데이터 원천, 데이터마트 적재 주체 |
| 관제 → v2 (인입) | 관제가 저작도구 소유 `LS_DATA_INGEST`에 **직접 INSERT**(`PRCS_STTS_CD='PENDING'`) → 저작도구 주기 배치(`ControlTrainingVideoScanJob`)가 미처리 행을 **폴링**해 `LS_DATA_RAW`로 적재 |
| v2 → 관제 (outbound) | 검수 완료 시 `TASK_COMPLETED`, 검수 후 수정 시 `TASK_MODIFIED` **단방향 통지**(비동기, 영상 1건 단위, 수정 요약만·본문 미포함) |
| 관제 → v2 (조회) | 통지 수신 후 **저작도구 API로 상세 조회** + `V_COMPLETED_*` View SELECT |
| 데이터 공유 | 저작도구 소유 `LS_*`(현재 57개) 자체 관리. 관제 공유 `MNG_*`는 **전량 DROP**(0개) — 아래 각주 참조 |
| 회복성 | 요청 ID idempotency + 디바운스 + dead-letter/재등록 큐(`LS_CONTROL_NOTIFY_FALLBACK`) + Resilience4j |
| 보안 | 양방향 M2M 인증은 deprecated. 통지는 인계 토큰 또는 IP 화이트리스트로 보호 |
| 코드 | `ControlNotifyClient`, `controlnotify/`(Service·Debouncer·Fallback·TaskQueryController), `TrainingVideoIngestService`, `ControlTrainingVideoScanJob` |

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"데이터 공유 \| `MNG_*` 9개 테이블 소유 → v2는
> `ddl-auto=validate` 읽기 참조"* 는 사실과 다르다. `MNG_*` 테이블은 **전량 DROP**됐다(V167 등) —
> JPA 매핑·SQL 참조 각 **0건**이며 회귀 가드 `MngControlMasterTableRemovalTest`가 이 상태를 고정한다.
> 적재 주체도 반전됐다 — 구 서술이 암시하던 "v2가 공유 DB를 읽어 픽업"이 아니라 **"관제가 저작도구
> 소유 테이블에 직접 쓰고 저작도구가 폴링"**(ADR-042)이다. 위 표의 "관제 → v2 (인입)" 행을 신설했다.
> 근거: `MngControlMasterTableRemovalTest`, `TrainingVideoIngestService.java`,
> `reports/wiki-align-20260819/CROSSAXIS.md` §B.

### A-2. 포털 서버 (portal)

| 항목 | 내용 |
|------|------|
| 역할 | 외부 채널(포털 회원) JWT 발급, 데이터마트 영상 제공 |
| 연동 | 관제서버 → 데이터마트 → 포털 DB 적재(관제서버 책임). **v2는 포털 DB를 읽지 않는다** — 포털 라벨 조회·저장은 저작도구(control) DB에서 처리하고, 저작도구가 메타를 포털 DB로 **단방향 복제(쓰기)**한다 |
| 저장 정책 | 포털 사용자 작업은 `LS_PORTAL_USER_LABEL`에 별도 적재 (원본·데이터마트 미수정, 단방향) |
| 범위 | 오토라벨링·VLM·버전관리·검수 미제공 (ADR-013). **본인 자산(이미지/영상) 업로드 + 수동 라벨링(BBOX/POLYGON)은 ADR-013 예외로 제공**(`LS_PORTAL_*` 전용 경로, 내부 파이프라인·데이터마트와 완전 분리) |
| 코드 | `portal/`, `PortalDataSourceConfig`(듀얼 데이터소스), `PortalDatasetVideoMetaRepository`(메타 복제 writer, `@PortalRepo` 유일 사용처) |

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"연동 \| **포털 DB 공유** — v2가 포털 DB에서 영상/라벨
> Load"* 는 사실과 다르다. 포털 라벨 경로(`PortalLabelService` 등)는 `controlTransactionManager`로
> 묶여 **저작도구(control) DB를 읽고 쓴다**. `@PortalRepo`(포털 DB 접속)를 쓰는 곳은
> `PortalDatasetVideoMetaRepository` **1곳뿐**이고 그 방향은 **저작도구 → 포털 DB 단방향 쓰기**다 —
> 즉 포털 DB는 우리가 읽는 곳이 아니라 **내보내는 곳**이다. 이 오기는 v2-wiki에도 같은 형태로
> 복제돼 있었고 이미 정정됐다(`16-portal.md`). 근거: `PortalDatasetVideoMetaRepository.java`,
> `PortalLabelService.java`(트랜잭션 매니저), `16-portal.md`(구 서술 폐기 각주).

### A-3. 비식별화 서버 (de-identification, KPST)

| 항목 | 내용 |
|------|------|
| 역할 | 영상 비식별 처리(발주기관 SW 직접구매 외부 솔루션) |
| 연동 | 적재 직후 **전체 영상에 자동 실행**(개인정보 유형 게이팅 폐지) — 파이프라인 선두 단계. 완료 감지는 **콜백이 아니라 주기 폴링**(`KpstDeidentPollJob`)이다 |
| 정책 | 실패 시 `DE_IDENT_YN='F'` 마킹, **원본 절대 삭제 금지**. 자동 재비식별 큐는 없음 — 누락 신고 시 **외부 비식별 프로그램에서 사람이 수동 재비식별** 후 resolve 경로로 회수 |
| 회복성 | Resilience4j(타임아웃/재시도/서킷) + 조건부 UPDATE 원자 클레임(2노드 Active-Active 중복 폴링 방지) |
| 설정 키 | `kpst.deid.base-url`(연동 서버 주소) |
| 코드 | `KpstDeidentifyClient`, `batch/step/DeidentifyStep`, `batch/scheduler/KpstDeidentPollJob`, `LS_DEIDENT_REPORT`/`LS_DEIDENT_PROC_LOG` |

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 이 항목의 옛 표는 4곳이 사실과 다르다.
> ① *"연동 \| 배치 파이프라인에서 호출(`PRVC_TYPE_CD='PRVC' or 'PSDO'`일 때), 결과 콜백 수신"* —
> 개인정보 유형별 게이팅은 **폐지**됐다. 지금은 **전체 영상**을 적재 직후 무조건 자동 비식별한다.
> 완료 감지도 콜백이 아니라 **폴링**이다 — 콜백 컨트롤러(`webhook/DeidentifyResultController`)는
> 코드에 **없다**.
> ② *"정책 \| … + 재시도 큐"* — 자동 재비식별 큐는 **없다**. 실패 시 `DE_IDENT_YN='F'`로 마킹만 하고,
> 회수는 외부 비식별 프로그램의 **수동** 재비식별 + `resolve` API 경로다. (`batch/retry/BatchRetryQueue`는
> 실재하지만 이는 배치 일반 재시도이지 재비식별 전용 큐가 아니다.)
> ③ *"코드 \| `DeidentifyClient`, …, `webhook/DeidentifyResultController`, …"* — `DeidentifyClient`
> 클래스는 코드에 없다(위 §20.1 각주 참조). 실제 클래스는 `KpstDeidentifyClient`이고, 콜백
> 컨트롤러도 없다(완료 감지는 `KpstDeidentPollJob` 폴링).
> ④ **설정 키 행을 신설**했다 — 비식별 연동 서버 주소는 `kpst.deid.base-url`이며
> `authoring.integration.deidentify.base-url`(죽은 빈이 읽던 키)이 **아니다**.
> 근거: `KpstDeidentifyClient.java`, `KpstDeidentPollJob.java`, `DeidentifyStep.java`,
> `application.yml`(`kpst.deid.base-url`), `DeidentifyHealthIndicator.java`.

### A-4. 외부 VLM 서비스 (VLM timeseries)

| 항목 | 내용 |
|------|------|
| 역할 | 영상 시계열 메타(VLM) 생성 — **모델 본체는 외부**, v2는 호출만. **ai-server 자체의 `/infer/vlm/verify-objects`(객체 단위 검증, 대개 mock)와는 다른 것**이다 — 이쪽은 ai-server를 경유하지 않고 외부 벤더를 직접 호출한다 |
| 벤더 | IntelliVIX Video VLM API(확정 계약 v2.0.1) |
| 연동 | `VlmTimeseriesStep`가 **`POST /v1/videovlm/verify`**로 **논블로킹 제출**(`subscribe()`, 파이프라인 스레드를 붙잡지 않음 — 구 `.block(45s)` 동기 대기는 폐기). 결과는 콜백(`POST /v1/vlm/callback`)으로 수신하며 `results`는 **배열이 아니라 객체** `{accuracy, description}` |
| 처리 | `vlm.description`을 `LS_DATA_META` 적재 + 검수큐(`LS_DATA_META_REVIEW`) 진입(REVIEWER 검토, 라벨링 캔버스 메타 탭). `vlm.accuracy`는 화면 전용이며 검수큐에 넣지 않는다 |
| 코드 | `common/client/VlmClient`, `batch/step/VlmTimeseriesStep`, `webhook/VlmResultController`(`POST /v1/vlm/callback`) |

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 이 항목의 옛 표는 3곳이 사실과 다르다.
> ① *"연동 \| `VlmTimeseriesStep`가 동기 호출(45s 타임아웃, Resilience4j 재시도) → 즉시 응답 시
> 진행"* — 실제로는 **논블로킹 제출**이다. 스텝이 확정적으로 말하는 사실은 "제출을 개시했다"뿐이고,
> 수락(ACK) 여부는 완료 핸들러가 비동기로 기록한다. 아무 신호도 없으면 미결 스위퍼가 회수한다.
> ② *"상세 결과는 콜백(`POST /v1/vlm/result`)으로 수신"* — 실제 콜백 경로는
> **`POST /v1/vlm/callback`**이다(`VlmResultController`, `@RequestMapping("/v1/vlm")` +
> `@PostMapping("/callback")`).
> ③ *"처리 \| … 검수큐(`LS_DATA_META_REVIEW`) → REVIEWER 검토(SC-015)"* — `SC-015`(VLM 메타 검토
> 전용 페이지 `/auto/:videoId/meta`)는 진입점 없는 orphan으로 2026-06-17 폐기·코드 제거됐다. 지금은
> **라벨링 캔버스(`SC-005`) 우측 '메타' 탭의 시계열 메타 패널**(`TimeseriesSidePanel`)에서 검토한다.
> 또한 위탁 규격 자체도 바뀌었다 — 엔드포인트는 `POST /v1/videovlm/verify` 하나이고 콜백 `results`는
> 구간별 배열(`describe`)이 아니라 **단일 객체** `{accuracy, description}`다.
> 근거: `VlmClient.java`(`VERIFY_PATH`), `VlmTimeseriesStep.java`, `VlmResultController.java`,
> `webhook/dto/VlmResultRequest.java`(`record Results`), `09-vlm-timeseries.md`.

### A-5. 외부 증강 / 생성 시스템 (augmentation)

| 항목 | 내용 |
|------|------|
| 역할 | 영상 증강(WINTER/NIGHT/RAIN) + 해상도 변경(내부 수행, 외부 위탁 아님) — 생성형 AI/합성 모델 본체는 외부 |
| 연동 | `HttpExternalAugmentClient`(인터페이스 `ExternalAugmentClient`) 요청 → 결과 콜백 수신 |
| 처리 | 증강 결과는 **새 영상**(`ORGNL_RAW_SN`으로 원본 참조, 구 `PARENT_RAW_SN`에서 개명)으로 생성 → 미검수(PENDING)부터 기존 플로우 |
| 코드 | `augment/integration/{ExternalAugmentClient,HttpExternalAugmentClient}`, `webhook/AugmentResultController`, `LS_DATA_AUG`/`LS_DATA_AUG_RVW` |

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — ① *"코드 \| `ExternalAugmentClient`, …"* — 실제 호출
> 클라이언트는 `HttpExternalAugmentClient`(`ExternalAugmentClient` 인터페이스의 구현체이며
> `common/client/`가 아니라 `augment/integration/` 패키지에 있다). ② *"처리 \| … `PARENT_RAW_SN`으로
> 원본 참조"* — 컬럼은 `ORGNL_RAW_SN`으로 개명됐다(V82, 표준단어 ORGNL=원본). 상세 정책(중복 요청
> 차단 폐지·해상도 변경 내부 통합·파생 깊이 1 고정 등)은 이 문서의 범위를 넘으므로
> [14 데이터 증강](../v2-wiki/14-augmentation.md) 참조. 근거: `HttpExternalAugmentClient.java`,
> `LsDataRaw.java`(`ORGNL_RAW_SN`).

### 공통 연동 인프라

- **Resilience4j** — 모든 외부 호출에 타임아웃/재시도/서킷 브레이커
- **웹훅 멱등성** — `webhook/idempotency/`(`LS_WEBHOOK_IDEMPOTENCY` — In-Memory/Persistent Ledger)
- **Fallback 큐** — `LS_CONTROL_NOTIFY_FALLBACK`(관제 통지)
- **공통 클라이언트** — `common/client/`에 **4종**(`AiServerClient`/`ControlNotifyClient`/`KpstDeidentifyClient`/`VlmClient`) + `augment/integration/HttpExternalAugmentClient`(별도 패키지)
- **연동 주소 검증** — 배포 시점 base-url(`ProfileGatedUrlPolicy`/`ExternalUrlPolicy`)은 stg/prd에서 HTTPS 전용 + 사설망 차단을 유지한다(local/dev만 완화 허용). 이와 별개로 **운영 화면에서 재기동 없이 바꾸는 저장값 축**(관리자 단기 인증)은 스킴(`http`/`https`)+URL 형식만 검증하고 대역 차단을 하지 않는다(2026-08-10 확정) — 판정 대상이 다른 두 축이며 서로 대체하지 않는다

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — ① *"Fallback 큐 \| `LS_CONTROL_NOTIFY_FALLBACK`(관제
> 통지), `LS_GITEA_FALLBACK_QUEUE`(버전관리)"* — `LS_GITEA_FALLBACK_QUEUE`는 **더 이상 존재하지
> 않는다**. Gitea 기반 버전관리(커밋 해시로 버전 식별)는 폐기됐고 지금은 페이로드 해시
> (`VERSION_HASH`, SHA-256) 기반 DB 스냅샷 방식이다 — 코드·스키마 전체에서 Gitea 흔적 **0건**.
> ② *"공통 클라이언트 \| `common/client/`에 5종(`DeidentifyClient`/`AiServerClient`/`VlmClient`/
> `ControlNotifyClient`/`ExternalAugmentClient`)"* — `DeidentifyClient`는 존재하지 않고
> (`KpstDeidentifyClient`가 실체), `ExternalAugmentClient`는 `common/client/`가 아니라
> `augment/integration/` 패키지 소속이다. `common/client/`에 실재하는 것은 **4개 파일**
> (`AiServerClient`·`ControlNotifyClient`·`KpstDeidentifyClient`·`VlmClient`)뿐이다.
> ③ **연동 주소 검증 행을 신설**했다 — 배포 축과 운영화면 저장 축이 서로 다른 정책을 쓴다는 점은
> 옛 표에 전혀 없던 내용이다. 근거: `db-archive/migration/V41__create_ls_gitea_fallback_queue.sql`
> (아카이브 확인, 현재 미실행), `common/client/` 디렉터리 목록, `ProfileGatedUrlPolicy.java`,
> `ExternalUrlPolicy.java`.

---

## 20.3 B. 범위 외 시스템 (책임 위임)

`CLAUDE.md`가 명시적으로 외부 책임으로 분리한 것들. v2는 연동 어댑터만 두거나 아예 다루지 않는다.

| 범위 외 시스템 | 위임 이유 | v2가 하는 것 |
|---------------|----------|-------------|
| **데이터마트 구축·검색·다운로드** | 마트 구축·검색·다운로드는 외부 제공 시스템 | `V_COMPLETED_*` View 노출까지만 (관제→마트→포털 DB는 외부) |
| **생성형 AI 본체** | 모델 학습·파인튜닝·프롬프트·생성 UI는 외부 | 외부 증강 결과 검수만 (SCR-AUG-002) |
| **VLM 모델 본체** | 학습·파인튜닝·프롬프트 관리는 외부 | 외부 VLM **호출 연동 + 결과 검토**(라벨링 캔버스 메타 탭)만 |
| **영상 합성 모델 본체** | 합성/증강 모델은 외부 | 합성 영상 수신·라벨링·검수만 |
| **학습데이터셋 Export** | ~~외부 시스템 책임~~ → **저작도구 범위 안**(ADR-020) | 검수 승인 시 NIA JSON + 프레임 이미지 2벌을 `v{n+1}`로 전량 재생성·산출까지 저작도구가 수행 |

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"학습데이터셋 Export \| 외부 시스템 책임 \|
> 라벨링·검수·버전관리까지만"* 은 사실과 다르다. **Export(NIA JSON 산출)는 저작도구 범위 안**이다.
> 근거 결정이 뒤집혔다 — 구 서술의 근거였던 `ADR-005`는 **`superseded`**이고 **`ADR-020`**(검수 승인
> 학습데이터 export 산출을 저작도구 범위로 포함)이 이를 대체했다. 검수 승인(`APPROVED`) 시점에
> 영상 단위로 라벨 전체 스냅샷을 DB(`LS_LABEL_VERSION`)에 저장하는 것과 별개로, **디스크 물리 파일**
> (프레임 이미지 + NIA COCO JSON)로 산출하는 것도 저작도구(`dataset/export/`)가 수행한다. 범위 밖으로
> 남는 것은 **데이터마트 구축·검색·다운로드**뿐이다 — 이 표의 첫 행 이름을 "데이터마트 구축·검색·
> 다운로드"로 좁혀 두 축을 구분했다. 근거: `dataset/export/`, `LS_DATASET_EXPORT`,
> `reports/wiki-align-20260819/CROSSAXIS.md` §B, 루트 `CLAUDE.md`("데이터마트 적재용 View" 절).

> 이 그룹이 [19 갭 체크리스트](19-v2-gap-checklist.md)의 ⛔ "범위 외"에 해당한다. v1은 이들을 저작도구에 **내장**했지만(→ [12 생성형 AI](12-generative-ai.md), [11 증강·내보내기](11-augmentation-export.md)), v2는 **데이터마트 구축·검색·다운로드만 외부로 분리**하고 나머지는 연동(호출/통지/검수) 또는 자체 산출(Export)로 처리한다.

---

## 20.4 v1 대비 변화

| 기능 | v1 | v2 |
|------|----|----|
| 생성형 AI | 저작도구 내장(Text/Image→Image/Video) | 외부 증강 시스템 연동 + 검수 |
| VLM 시계열 | (없음) | 외부 VLM 서비스 연동(신설) |
| 비식별화 | 작업자가 캔버스에서 직접 블러 | 외부 비식별 서버 연동(전체 영상 자동, 폴링 완료 감지) |
| 데이터마트 | 저작도구가 등록 | 외부 책임, View 노출까지만 |
| 학습데이터셋 Export | 저작도구가 내보내기 | **저작도구 범위 안**(ADR-020) — 검수 승인 시 저작도구가 NIA JSON 산출까지 수행 |
| 시스템 간 통신 | 데이터송신/수신 양방향 II | 단방향 outbound 통지 + inbound 조회 |

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"데이터마트/Export \| 저작도구가 등록·내보내기 \|
> 외부 책임, View 노출까지만"* 이 한 행에 두 가지 다른 책임(데이터마트 구축과 Export 산출)을 묶고
> 있었다. Export 는 저작도구 범위 안(ADR-020)이라 위 표에서 **행을 분리**했다 — "데이터마트"(외부
> 책임 유지)와 "학습데이터셋 Export"(저작도구 범위 안, 신규 행)로 나눴다. 근거는 §20.3 각주와 동일.

> 핵심: v2는 책임 경계를 **"라벨링·검수·버전관리·(검수 완료 학습데이터) Export까지"**로 좁히고, 그
> 너머(데이터마트 구축·검색·다운로드, 생성형 AI/VLM/영상합성 모델 본체)는 외부 시스템과 **연동(호출/
> 통지/검수)**으로 처리한다.
