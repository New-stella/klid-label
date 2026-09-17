# 01. 시스템 개요

> 출처: CLAUDE.md, R1 사용자요구사항정의서(v1.17), R2 §1·4
> 관련: [02 아키텍처](02-architecture.md) · [07 배치 파이프라인](07-batch-pipeline.md) · [19 외부 시스템](19-external-security-cvat.md)

## 1.1 목적

AI 기반 지방정부 CCTV 관제지원시스템(2차)의 **학습데이터 저작도구(AT)**. 영상/이미지 라벨링, 검수 워크플로우, 비식별화, 외부 생성 메타데이터 검토를 담당한다.

- **시스템명**: AI 기반 지방정부 CCTV 관제지원시스템 구축(2차) / 서브시스템: 학습데이터 저작도구(AT)
- **작업 단위 = 영상 1건** (`LS_DATA_RAW.RAW_SN`) — 프로젝트 단위 개념 없음 (v1과의 핵심 차이)

## 1.2 책임 범위

### 담당 (저작도구 책임)
사용자/권한 · 마킹(자동/수동) · 배치 파이프라인 · 라벨링 · 검수(REVIEWER 배정) · 비식별화 연동 · 버전관리(DB 스냅샷) · 데이터 증강 연동/검수 · 포털(데이터마트 Load).

### 범위 외 (외부 시스템 책임)
| 범위 외 | 저작도구가 하는 것 |
|---------|-------------------|
| **데이터마트** | 라벨링·검수·버전관리까지만. 마트 구축·검색·다운로드 미담당 (View 노출까지) |
| **생성형 AI 본체** | 외부 증강 결과 검수(SCR-AUG-002)만 |
| **VLM 모델 본체** | 외부 VLM 호출 연동 + 결과 검토만 |
| **영상 합성 모델 본체** | 합성/증강 영상 수신·라벨링·검수만 |
| **학습데이터셋 Export** [폐기 표기 — 아래 참조] | ~~라벨링·검수·버전관리까지만~~ → **export(NIA JSON) 산출 자체는 저작도구 범위 안** |

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"학습데이터셋 Export: 저작도구는 라벨링·검수·버전관리까지만 담당"*(= export 산출은 범위 외)은 사실과 다르다. **export(NIA JSON) 산출은 저작도구 범위 안**이며, 검수 승인(`APPROVED`) 시점에 라벨 스냅샷과 함께 `v{n+1}` 산출 폴더를 전량 재생성해 관제에 통지하는 것까지가 저작도구 책임이다. 근거 결정 `ADR-005`는 `superseded`이고 `ADR-020`("검수 승인 학습데이터 export 산출을 저작도구 범위로 포함")이 대체했다. 실측: `dataset` 도메인(74 `.java` 파일, 그중 `export` 하위 서브패키지만 36파일)이 NIA JSON export 생성·재생성 로직을 전담한다. 저작도구 범위 밖으로 남는 것은 위 **데이터마트 구축·검색·다운로드** 행뿐이다 — "학습데이터셋 Export"를 그와 동일한 범위 외 항목으로 별도 나열한 것 자체가 오류였다. 근거: `backend/src/main/java/kr/co/cudo/authoring/dataset/export/`(디렉터리 구조), `reports/wiki-align-20260819/facts/F1-backend-api.md`(도메인 파일수 표).
>
> 외부가 본체인 시계열 메타 모델·생성형 AI·영상 합성 모델은 요구사항에서 제외. 저작도구 잔존 책임(외부 VLM 호출·메타 검토 UI·증강 연동·생성 영상 라벨링)은 SFR-08에 흡수. 상세 → [19](19-external-security-cvat.md).

## 1.3 역할

| 역할 | 코드 | 주요 권한 |
|------|------|-----------|
| **관리자** | **`ADMIN`** | **사용자 관리(역할 지정) · 연동 서버 주소 · 파일 업로드 · 산출물 가져오기 · 관리자 패스워드 교체 · 위험 작업.** ★검수자 권한을 계층으로 물려받는다(`ROLE_ADMIN > ROLE_REVIEWER`) — 검수·배정을 그대로 수행하므로 겸직에 계정이 둘 필요 없다 |
| 검수자 | `REVIEWER` | 시스템 설정(배치·추론·정밀도·비식별)·라벨 마스터·프리셋·이벤트유형·비식별 신고 관리, 작업자 배정·재배정·이력, 검수 승인/반려, 증강 요청·검수, 비식별 요청·옵션, 버전 비교·복구. ⚠ **사용자 관리는 관리자 소유** |
| 라벨링 작업자 | `WORKER` | 본인 배정 영상 라벨 수정·검수 제출, AI 보조 라벨링, 비식별 누락 신고 |
| 포털 회원 | `PORTAL_USER` | 데이터마트 영상 선택, 기존 라벨 확인·수정·저장, 본인 데이터 다운로드, **본인 자산(영상) 업로드**(ADR-013 예외, 2026-07-17), **라벨링 AI 보조**(AI 탐지·AI 분할·AI 자동 추적 — 2026-09-15 확정 · `ADR-013` v24, [16 §16.3b](16-portal.md)). 키포인트·선택 객체 AI 추적·검수·버전관리는 미제공 |

> ### ★역할 4종 + 계층 (2026-08-28 확정, 구속 · `ADR-055`)
>
> ⚠ **구 서술 폐기** — *"ADMIN 역할 없음 / ADMIN 폐지 — 모든 관리 권한은 REVIEWER 에 통합"*. 그 결정(`ADR-003`)은
> **`ADR-055` 가 supersede** 했다. 되살리지 말 것.
>
> - **계층은 「관리자 → 검수자」 한 단계뿐**이다. `WORKER` 를 넣으면 작업자 전용 자리에 관리자·검수자가
>   흘러들고, `PORTAL_USER` 를 넣으면 외부 채널 사용자가 내부 권한을 얻는다(`AC-125`).
> - 계층 덕분에 **기존 `hasRole('REVIEWER')` 99곳을 하나도 바꾸지 않는다.** 배선은 `RoleHierarchy` 한 줄이다.
> - **관리 메뉴는 역할로 가른다** — 검수자에게는 관리자 항목이 보이지 않는다. 관리 화면 URL 은
>   관리자 소유가 `/admin/*`, 검수자 소유가 `/manage/*` 로 갈린다.
> - ⚠ **배포 직후 `ADMIN` 은 0명**이다 — 부트스트랩으로 최초 관리자를 만들 때까지 관리 기능이 잠긴다.
>
> 상세 → [03](03-auth-roles.md).

## 1.4 기술 스택

> 정확한 lock 기준 버전·설치 경로 전체표는 **D5 아키텍처설계서 §2-5 소프트웨어 구성요소 종류·버전·설치 경로**를 정본으로 한다. 아래는 요약이며 버전은 빌드/lockfile 기준이다.

### 백엔드 (Spring Boot)
Java 17(Temurin) · **Spring Boot 3.3.0**(내장 톰캣, 컨텍스트 `/api`) · Gradle 8.8 · Spring Data JPA(Hibernate **6.5.2**) + **QueryDSL 5.1.0** · Spring Security **6.3.0** + **JJWT 0.12.6** · **Flyway**(플러그인 10.13.0 / 런타임 10.10.0, PostgreSQL, `klid_at` 스키마) · **Spring Boot Quartz**(2.3.2, PostgreSQL JobStore) · **Resilience4j 2.2.0** · WebFlux WebClient · **net.bramp.ffmpeg 0.8.0** · Caffeine 3.1.8 · MapStruct 1.5.5/Lombok 1.18.32 · **Micrometer 1.13 + Prometheus** · Springdoc OpenAPI 2.5.0 · JUnit5 + Testcontainers.

### AI 추론 서버 (ai-server)
Python 3.11(base 이미지 종속) + **FastAPI 0.137**(uvicorn 0.49) · **YOLOX(ONNX Runtime 1.27, Apache-2.0)** 탐지 · **Meta SAM2(Apache-2.0)** 분할 · **ByteTrack(trackers, Apache-2.0)** 추적 · torch **2.5.1**(도커)/2.12(lock) · opencv 4.13. **경량 추론 전용, stateless, 인증/DB 없음** — Spring Boot가 오케스트레이션. (외부가 아니라 저작도구 내부 구성요소) · 라이선스: AGPL(ultralytics) 미사용 — 전부 permissive(MIT/Apache-2.0)로 구성

### 프론트엔드
React 18.3.1 + TypeScript 5.9 + Vite 5.4 · TanStack Query v5 · Zustand 4.5 · React Router v6.30 · axios 1.x · Tailwind 3.4 · **konva.js 9.3**(CVAT canvas-drawing 포팅) · Node 20 빌드 런타임.

### DB
**PostgreSQL 16**(**외부 인프라 제공 · 저작도구 미운영**, 온프렘 번들은 폐쇄망 단독 설치 옵션) · `klid_at` 스키마 — 저작도구 전용 **LS_*** 스키마 자체 소유·접속만 담당 [폐기 표기 — 아래 참조], Quartz `QRTZ_*`(11개, `V1__baseline.sql` 생성). DB 서버 가용성·백업/복구는 DB 운영 주체 책임. 상세 → [18](18-database.md).

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"관제서버 MNG_* 9개 `validate` 참조"*는 사실과 다르다. 관제 2차에서 적재 주체가 반전(ADR-042)돼 **관제가 저작도구 소유 `LS_DATA_INGEST` 에 직접 INSERT → 저작도구 주기 배치가 폴링**하는 구조로 바뀌었고, 저작도구가 과거 `validate` 로 읽던 관제 공유 마스터 4종(`MNG_CLIP_MASTER`·`MNG_CLIP_EVNT_LST`·`MNG_RESOURCE_CCTV`·`MNG_EX_LOCAL_GOV`)은 `V167` 로 DROP 됐다. `grep -rl '@Table(name = "mng_' backend/src/main/java` → **0건**, 마이그레이션 전체에도 `MNG_*` 테이블 정의 **0건**이며, 회귀 가드 `MngControlMasterTableRemovalTest`가 JPA 매핑·타입 참조·실행 SQL 참조 각 0건을 고정한다. ⚠ 이는 `MNG_*` 축에만 해당하며 **`QRTZ_*`(11개, Quartz JobStore)는 그대로 살아 있다** — 둘을 묶어 "둘 다 없다"고 읽지 말 것. 근거: `backend/src/test/java/kr/co/cudo/authoring/architecture/MngControlMasterTableRemovalTest.java` → [02 §2.5](02-architecture.md#25-듀얼-데이터소스).

## 1.5 핵심 파이프라인

```
영상 적재 → 비식별화(전체 영상 무조건, 적재 직후 선두 자동) → 마킹(비식별 영상)
  → 외부 VLM 시계열(콜백) → FFmpeg(마킹 위치 기반 원본+비식별 2벌)
  → YOLO(원본만) → SAM2 → 트랙 보간
  → 라벨링 → 검수(승인=완료) → 버전 스냅샷 + 관제 통지 → (선택) 증강
```

> 비식별 선두 재배치(R1 NFR-001 v1.5) **구현 완료**. 단계 순서는 `BatchPipelineConfig` 에서 선언적으로 관리. → [07](07-batch-pipeline.md).

## 1.6 핵심 산출물 목표 (NFR)

| NFR | 목표 |
|-----|------|
| NFR-002 | 이미지 학습데이터 **10만장**(`LS_DATA_SRC`) |
| NFR-003 | 영상 학습데이터 **5,000건**(30초 이상/건, `LS_DATA_RAW`) |
| 처리 종류 — 외부 위탁 + 내부 파생 | 증강 AI(`AUGMENT`, 외부 위탁 — 무엇으로 바꿀지는 생성 조건 5항목이 정한다) + 해상도 변경(`RESL_1080P`/`RESL_720P`/`RESL_480P`, 저작도구 내부 수행, SFR-06-03) |

> ⚠ **구 서술 폐기(2026-09-02 · `ADR-059`)** — *"외부 증강 3종 + 내부 파생 1종 | WINTER / NIGHT / RAIN(외부 위탁) + RESOLUTION"* 은 사실과 다르다. 증강 종류 세 값은 단일값 `AUGMENT` 로 합쳐졌고 겨울·야간·우천은 **생성 조건 프리셋**이 됐다 → [14](14-augmentation.md). ⚠ **기존 파생본의 구 코드값(`WINTER`·`NIGHT`·`RAIN`)과 레거시 `RESOLUTION` 은 백필 없이 보존**되므로 조회·표시 경로는 옛 값과 새 값을 모두 견뎌야 한다.

전체 NFR-001~007 → [19](19-external-security-cvat.md#nfr).
