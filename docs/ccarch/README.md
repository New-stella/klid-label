# ccarch 업로드 자료

> 원본 RFP: `docs/requirements/[붙임2]  제안요청서(수정)_260303.pdf` (161 pages, 2026.02)
> 사업명: **AI 기반 지방정부 CCTV 관제지원시스템 구축(2차)** (한국지역정보개발원, 263일, 3,859,688천원)
> 본 폴더는 ccarch wiki에 업로드할 노드/링크의 사전 정의·매핑을 담는다. ccarch MCP 도구(`mcp__ccarch__ccarch_create_node`, `_create_link`) 호출 시 그대로 사용 가능한 형태로 작성되었다.

## 추적성 체인

```
SOURCE_REQUIREMENT  →  REQUIREMENT  →  USECASE  →  ENTITY/COMPONENT/INTERFACE  →  TESTCASE/CODE
   (RFP 발췌)         (정제·구체화)    (시나리오)        (도메인 모델·모듈·계약)         (구현·검증)

추가 그룹핑:
  USECASE  →  PERFORMED_BY  →  ACTOR
  USECASE  →  BELONGS_TO    →  FEATURE
```

## 파일 일람

| 파일 | 노드 타입 | 개수 | 비고 |
|---|---|---:|---|
| [01-actors.md](01-actors.md) | ACTOR | 4 | 검수자(REVIEWER) / 라벨링작업자(WORKER) / 포털회원(PORTAL_USER) / 배치시스템(SYSTEM) |
| [02-features.md](02-features.md) | FEATURE | 10 | 저작도구의 큰 기능 단위 |
| [03-source-requirements.md](03-source-requirements.md) | SOURCE_REQUIREMENT | 25 | RFP의 사업 추진 전략·SFR/DAR/SER 발췌 원문 |
| [04-requirements.md](04-requirements.md) | REQUIREMENT | 30 | SFR 18 + PER/SIR/TER/QUR/SER 중 저작도구 영향 핵심 |
| [05-usecases.md](05-usecases.md) | USECASE | 22 | 사용자 시나리오 단위 |
| [06-entities.md](06-entities.md) | ENTITY | 15 | LS_DATA_RAW 등 핵심 도메인 객체 |
| [07-components.md](07-components.md) | COMPONENT | 14 | backend/ai-server/frontend 모듈 |
| [08-interfaces.md](08-interfaces.md) | INTERFACE | 12 | REST API + 외부 연동 SPI |
| [09-architecture.md](09-architecture.md) | ARCHITECTURE | 2 | 시스템(전체) + 서브시스템(저작도구) |
| [10-traceability.md](10-traceability.md) | (링크) | — | DERIVES_FROM/REALIZES/PERFORMED_BY/BELONGS_TO/DEPENDS_ON/IMPLEMENTS/REFERS_TO 매트릭스 |
| [11-upload-plan.md](11-upload-plan.md) | (실행) | — | MCP 도구 호출 순서·idempotencyKey 가이드 |

## ccarch 컨벤션 준수

- 등록 순서: **target 먼저 → source 다음 → link 마지막** (예: REQUIREMENT/ACTOR/FEATURE 등록 후 USECASE 등록, 그 다음 PERFORMED_BY/BELONGS_TO 링크)
- `idempotencyKey`(UUID v4)를 매 호출마다 전달하면 24시간 재시도가 안전
- 모든 등록은 ServiceToken 바인딩된 단일 workspace 내에서 수행
- 노드 수정 시 `reason` 권장 (감사 추적)

## 본 사업 범위 주의 (V1.4 ~ V1.8 정리)

저작도구의 **요구사항 매핑 시 다음 SFR은 외부 시스템 책임**으로 정리되어 ccarch에 등록할 때 `attrs.scope`로 명시한다:

| SFR | 본 도구 범위 | 외부 책임 |
|---|---|---|
| SFR-03 (시계열 메타) | **외부 메타 검토 UI(SCR-AUTO-002)만 보유** | 메타 자동 생성·VLM 본체는 외부 |
| SFR-06 (생성형 AI 학습데이터 제작 고도화) | **증강 결과 검수(SCR-AUG-002)만 보유** | 생성형 AI 모델·UI·프롬프트는 외부 |
| SFR-07 (데이터 증강 자동화) | 증강 결과 라벨링·검수 인계 | 증강 자동화 본체 |
| SFR-11 (학습용 데이터 생성 AI 개발) | 생성 결과 라벨링 | 영상 합성 모델 (SFR-11 본체) |
| SFR-12 (VLM 이상상황 분석) | **YOLO/SAM2가 감지한 객체 검증으로 한정** | 시계열 메타 자동 생성 |
| SFR-13 (학습데이터 외부제공) | — | **외부 학습데이터 시스템(데이터마트·검색·다운로드)** |
| SFR-15 다운로드 기능 | — | 포털 자체 책임 |

이 정리는 `04-requirements.md` 각 REQUIREMENT 노드의 `content` 내 **범위 주의** 섹션에 반영되어 있다.
