# 20. 외부 시스템 (v2 연동 · 범위 외)

> **목적**: v2(저작도구)가 말하는 "외부 시스템"이 무엇인지, 어떻게 연동하고 어디까지 책임지는지 정리.
> 근거: 루트 [`CLAUDE.md`](../../CLAUDE.md) + v2 코드 조사(`common/client/`, `webhook/`, `controlnotify/`, db migration) — 2026-06.
> 관련: [18 v1↔v2 비교](18-v1-v2-comparison.md) · [19 갭 체크리스트](19-v2-gap-checklist.md)

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
   데이터마트 · 생성형 AI 본체 · VLM 모델 본체 · 영상 합성 모델 본체 · 학습데이터 Export
```

---

## 20.2 A. 연동 외부 시스템

### A-1. 관제서버 (control server)

| 항목 | 내용 |
|------|------|
| 역할 | 내부 채널 JWT 발급(로그인 인계), 영상/데이터 원천, 데이터마트 적재 주체 |
| v2 → 관제 (outbound) | 검수 완료 시 `TASK_COMPLETED`, 검수 후 수정 시 `TASK_MODIFIED` **단방향 통지**(비동기, 영상 1건 단위, 수정 요약만·본문 미포함) |
| 관제 → v2 (inbound) | 통지 수신 후 **저작도구 API로 상세 조회** + `V_COMPLETED_*` View SELECT |
| 데이터 공유 | `MNG_*` 9개 테이블 소유 → v2는 `ddl-auto=validate` 읽기 참조 |
| 회복성 | 요청 ID idempotency + 디바운스 + dead-letter/재등록 큐(`LS_CONTROL_NOTIFY_FALLBACK`) + Resilience4j |
| 보안 | 양방향 M2M 인증은 deprecated. 통지는 인계 토큰 또는 IP 화이트리스트로 보호 |
| 코드 | `ControlNotifyClient`, `controlnotify/` (Service·Debouncer·Fallback·TaskQueryController) |

### A-2. 포털 서버 (portal)

| 항목 | 내용 |
|------|------|
| 역할 | 외부 채널(포털 회원) JWT 발급, 데이터마트 영상 제공 |
| 연동 | **포털 DB 공유** — v2가 포털 DB에서 영상/라벨 Load |
| 저장 정책 | 포털 사용자 작업은 `LS_PORTAL_USER_LABEL`에 별도 적재 (원본·데이터마트 미수정, 단방향) |
| 범위 | 업로드·오토라벨링·VLM·버전관리·검수 미제공 (ADR-013) |
| 코드 | `portal/`, `PortalDataSourceConfig`(듀얼 데이터소스) |

### A-3. 비식별화 서버 (de-identification)

| 항목 | 내용 |
|------|------|
| 역할 | 영상 비식별 처리(발주기관 SW 직접구매 외부 솔루션) |
| 연동 | 배치 파이프라인에서 호출(`PRVC_TYPE_CD='PRVC' or 'PSDO'`일 때), 결과 콜백 수신 |
| 정책 | 실패 시 `DE_IDNTF_YN='F'` 마킹 + 재시도 큐, **원본 절대 삭제 금지**. 누락 신고 시 외부 재비식별 |
| 회복성 | Resilience4j(타임아웃/재시도/서킷) + 웹훅 멱등성 |
| 코드 | `DeidentifyClient`, `batch/step/DeidentifyStep`, `webhook/DeidentifyResultController`, `LS_DEIDENT_REPORT`/`LS_DEIDENT_PROC_LOG` |

### A-4. 외부 VLM 서비스 (VLM timeseries)

| 항목 | 내용 |
|------|------|
| 역할 | 영상 시계열 메타(VLM) 생성 — **모델 본체는 외부**, v2는 호출만 |
| 연동 | `VlmTimeseriesStep`가 동기 호출(45s 타임아웃, Resilience4j 재시도) → 즉시 응답 시 진행. 상세 결과는 콜백(`POST /v1/vlm/result`)으로 수신 |
| 처리 | 결과를 `LS_DATA_META` 적재 + 검수큐(`LS_DATA_META_REVIEW`) → REVIEWER 검토(SC-015) |
| 코드 | `VlmClient`, `batch/step/VlmTimeseriesStep`, `webhook/VlmResultController`, `ai-server/app/routers/vlm.py`(호출 어댑터) |

### A-5. 외부 증강 / 생성 시스템 (augmentation)

| 항목 | 내용 |
|------|------|
| 역할 | 영상 증강(WINTER/NIGHT/RAIN/RESOLUTION) — 생성형 AI/합성 모델 본체는 외부 |
| 연동 | `ExternalAugmentClient` 요청 → 결과 콜백 수신 |
| 처리 | 증강 결과는 **새 영상**(`PARENT_RAW_SN`으로 원본 참조)으로 생성 → 미검수(PENDING)부터 기존 플로우. 라벨 무결성 검증(`LabelIntegrityCalculator`) |
| 코드 | `ExternalAugmentClient`, `augment/`, `webhook/AugmentResultController`, `LS_DATA_AUG`/`LS_DATA_AUG_RVW` |

### 공통 연동 인프라

- **Resilience4j** — 모든 외부 호출에 타임아웃/재시도/서킷 브레이커
- **웹훅 멱등성** — `webhook/` + `LS_WEBHOOK_IDEMPOTENCY` (In-Memory/Persistent Ledger)
- **Fallback 큐** — `LS_CONTROL_NOTIFY_FALLBACK`(관제 통지), `LS_GITEA_FALLBACK_QUEUE`(버전관리)
- **공통 클라이언트** — `common/client/`에 5종(`DeidentifyClient`/`AiServerClient`/`VlmClient`/`ControlNotifyClient`/`ExternalAugmentClient`)

---

## 20.3 B. 범위 외 시스템 (책임 위임)

`CLAUDE.md`가 명시적으로 외부 책임으로 분리한 것들. v2는 연동 어댑터만 두거나 아예 다루지 않는다.

| 범위 외 시스템 | 위임 이유 | v2가 하는 것 |
|---------------|----------|-------------|
| **데이터마트** | 마트 구축·검색·다운로드는 외부 제공 시스템 | `V_COMPLETED_*` View 노출까지만 (관제→마트→포털 DB는 외부) |
| **생성형 AI 본체** | 모델 학습·파인튜닝·프롬프트·생성 UI는 외부 | 외부 증강 결과 검수만 (SCR-AUG-002) |
| **VLM 모델 본체** | 학습·파인튜닝·프롬프트 관리는 외부 | 외부 VLM **호출 연동 + 결과 검토**(SC-015)만 |
| **영상 합성 모델 본체** | 합성/증강 모델은 외부 | 합성 영상 수신·라벨링·검수만 |
| **학습데이터셋 Export** | 외부 시스템 책임 | 라벨링·검수·버전관리까지만 |

> 이 그룹이 [19 갭 체크리스트](19-v2-gap-checklist.md)의 ⛔ "범위 외"에 해당한다. v1은 이들을 저작도구에 **내장**했지만(→ [12 생성형 AI](12-generative-ai.md), [11 증강·내보내기](11-augmentation-export.md)), v2는 **외부로 분리하고 연동(호출/통지/검수)만** 한다.

---

## 20.4 v1 대비 변화

| 기능 | v1 | v2 |
|------|----|----|
| 생성형 AI | 저작도구 내장(Text/Image→Image/Video) | 외부 증강 시스템 연동 + 검수 |
| VLM 시계열 | (없음) | 외부 VLM 서비스 연동(신설) |
| 비식별화 | 작업자가 캔버스에서 직접 블러 | 외부 비식별 서버 연동 |
| 데이터마트/Export | 저작도구가 등록·내보내기 | 외부 책임, View 노출까지만 |
| 시스템 간 통신 | 데이터송신/수신 양방향 II | 단방향 outbound 통지 + inbound 조회 |

> 핵심: v2는 책임 경계를 **"라벨링·검수·버전관리까지"**로 좁히고, 그 너머는 외부 시스템과 **연동(호출/통지/검수)**으로 처리한다.
