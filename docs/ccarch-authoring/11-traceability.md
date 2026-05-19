# 추적성 매트릭스 (Link Definitions)

> 모든 링크는 `mcp__ccarch__ccarch_create_link` 로 등록. 양쪽 노드가 같은 workspace 에 사전 존재해야 하며, 자기참조는 거절된다.
> 등록 시 `idempotencyKey`(UUID v4) 전달.
>
> INTERFACE 4개는 외부 시스템과의 계약만 등록되어 있으므로, REALIZES/DEPENDS_ON 의 INTERFACE 참조는 외부 호출/인계 시나리오에만 등장한다. 본 도구 내부 호출은 COMPONENT 간 `DEPENDS_ON` 으로 표현한다.

## 1. DERIVES_FROM — REQUIREMENT → SOURCE_REQUIREMENT

| Source REQUIREMENT | Target SOURCE_REQUIREMENT |
|---|---|
| req-batch-orchestrator | sr-sfr-08 |
| req-deidentify-mandatory | sr-sfr-09 |
| req-yolo-sam2 | sr-sfr-08 |
| req-vlm-timeseries | sr-sfr-03 |
| req-labeling-canvas | sr-sfr-08 |
| req-label-precision | sr-sfr-08 |
| req-label-master-pool | sr-sfr-08 |
| req-label-preset | sr-sfr-08 |
| req-assign-task | sr-sfr-08 |
| req-review-state-machine | sr-sfr-08 |
| req-version-gitea | sr-sfr-08 |
| req-augment-completed-only | sr-sfr-06-07 |
| req-portal | sr-sfr-15 |
| req-dataset-output | sr-sfr-16-17 |
| req-nfr-core | sr-nfr-core |

## 2. DERIVES_FROM — USECASE → REQUIREMENT

| Source USECASE | Target REQUIREMENT |
|---|---|
| uc-auto-pipeline | req-batch-orchestrator, req-yolo-sam2, req-vlm-timeseries, req-deidentify-mandatory |
| uc-deidentify | req-deidentify-mandatory |
| uc-deident-review | req-deidentify-mandatory |
| uc-vlm-timeseries-request | req-vlm-timeseries |
| uc-meta-review | req-vlm-timeseries |
| uc-label-edit | req-labeling-canvas, req-label-precision |
| uc-sam2-track | req-labeling-canvas, req-yolo-sam2 |
| uc-label-master | req-label-master-pool |
| uc-preset-manage | req-label-preset |
| uc-assign-task | req-assign-task |
| uc-review-decision | req-review-state-machine |
| uc-label-commit | req-version-gitea |
| uc-version-diff-rollback | req-version-gitea |
| uc-augment-flow | req-augment-completed-only |
| uc-portal-label | req-portal |
| uc-jwt-ingress | req-nfr-core |

## 3. PERFORMED_BY — USECASE → ACTOR

| USECASE | ACTOR (다중 가능) |
|---|---|
| uc-auto-pipeline | actor-batch-system |
| uc-deidentify | actor-batch-system, ext-deidentify-sw |
| uc-deident-review | actor-reviewer |
| uc-vlm-timeseries-request | actor-batch-system, ext-vlm-service |
| uc-meta-review | actor-reviewer |
| uc-label-edit | actor-worker |
| uc-sam2-track | actor-worker |
| uc-label-master | actor-reviewer |
| uc-preset-manage | actor-reviewer |
| uc-assign-task | actor-reviewer |
| uc-review-decision | actor-reviewer |
| uc-label-commit | actor-worker, ext-gitea |
| uc-version-diff-rollback | actor-reviewer, ext-gitea |
| uc-augment-flow | actor-reviewer, ext-generative-ai |
| uc-portal-label | actor-portal-user |
| uc-jwt-ingress | ext-control-server, ext-portal-server |

## 4. BELONGS_TO — USECASE → FEATURE

| USECASE | FEATURE |
|---|---|
| uc-auto-pipeline | feat-auto-labeling |
| uc-deidentify | feat-auto-labeling |
| uc-deident-review | feat-auto-labeling |
| uc-vlm-timeseries-request | feat-meta-pipeline |
| uc-meta-review | feat-meta-pipeline |
| uc-label-edit | feat-labeling-authoring |
| uc-sam2-track | feat-labeling-authoring |
| uc-label-master | feat-labeling-authoring |
| uc-preset-manage | feat-portal-and-admin |
| uc-assign-task | feat-review-workflow |
| uc-review-decision | feat-review-workflow |
| uc-label-commit | feat-version-control |
| uc-version-diff-rollback | feat-version-control |
| uc-augment-flow | feat-augmentation |
| uc-portal-label | feat-portal-and-admin |
| uc-jwt-ingress | feat-portal-and-admin |

## 5. REALIZES — USECASE → COMPONENT|INTERFACE

> INTERFACE 는 외부 계약(if-deidentify-spi / if-gitea-contents / if-vlm-timeseries-spi / if-augment-result-handover) 4 개만 존재. 내부 API 는 COMPONENT 만으로 표현한다.

| Source USECASE | Target (COMPONENT/INTERFACE) |
|---|---|
| uc-auto-pipeline | comp-batch-orchestrator, comp-ai-server-client, comp-deidentify-client, comp-vlm-client |
| uc-deidentify | comp-deidentify-client, if-deidentify-spi |
| uc-deident-review | comp-deidentify-client |
| uc-vlm-timeseries-request | comp-vlm-client, if-vlm-timeseries-spi |
| uc-meta-review | comp-meta-service |
| uc-label-edit | comp-label-canvas, comp-label-service |
| uc-sam2-track | comp-ai-server-client, comp-ai-server |
| uc-label-master | comp-label-service |
| uc-preset-manage | comp-preset-service |
| uc-assign-task | comp-assignment-service |
| uc-review-decision | comp-review-service |
| uc-label-commit | comp-label-service, comp-version-service, comp-gitea-client, if-gitea-contents |
| uc-version-diff-rollback | comp-version-service, comp-gitea-client, if-gitea-contents |
| uc-augment-flow | comp-augment-service, if-augment-result-handover |
| uc-portal-label | comp-portal-suite, comp-label-service |
| uc-jwt-ingress | comp-jwt-filter |

## 6. DEPENDS_ON — COMPONENT → COMPONENT|INTERFACE|ENTITY

> 본 도구 내부 호출은 COMPONENT 간 직접 의존으로 표현. 외부 호출은 외부 INTERFACE 노드로 의존.

### 6-1. 본 도구 내부 (COMPONENT → COMPONENT)

| Source COMPONENT | Target COMPONENT | 비고 |
|---|---|---|
| comp-batch-orchestrator | comp-ai-server-client | YOLO/SAM2 호출 흐름 |
| comp-batch-orchestrator | comp-deidentify-client | 비식별 호출 흐름 |
| comp-batch-orchestrator | comp-vlm-client | 시계열 메타 호출 흐름 |
| comp-ai-server-client | comp-ai-server | BE → ai-server (본 도구 내 마이크로서비스) |
| comp-label-canvas | comp-label-service | FE → BE 라벨 CRUD |
| comp-label-canvas | comp-ai-server-client | SAM2 Track 도구 (BE 경유) |
| comp-label-service | comp-version-service | 저장 시 SaveCommitFlow 호출 |
| comp-version-service | comp-gitea-client | 커밋 위임 |
| comp-portal-suite | comp-label-service | 포털 진입 사용자 간편 라벨링 |
| comp-portal-suite | comp-jwt-filter | 채널·역할 가드 |
| (다른 도메인 서비스들) | comp-jwt-filter | 인증 가드 의존 |

### 6-2. 본 도구 → 외부 (COMPONENT → INTERFACE)

| Source COMPONENT | Target INTERFACE | 비고 |
|---|---|---|
| comp-deidentify-client | if-deidentify-spi | 외부 비식별 솔루션 |
| comp-gitea-client | if-gitea-contents | 외부 Gitea |
| comp-vlm-client | if-vlm-timeseries-spi | 외부 VLM 서비스 |
| comp-augment-service | if-augment-result-handover | 외부 생성형 AI 의 Inbound SPI 노출 |

### 6-3. COMPONENT → ENTITY (데이터 의존)

| Source COMPONENT | Target ENTITY |
|---|---|
| comp-batch-orchestrator | ent-data-raw, ent-batch-aux |
| comp-vlm-client | ent-data-meta, ent-data-meta-review |
| comp-deidentify-client | ent-batch-aux (LS_DEIDENT_REPORT 부분) |
| comp-label-service | ent-data-lbl, ent-label-master |
| comp-review-service | ent-raw-data-status, ent-data-issue |
| comp-assignment-service | ent-task-assignment |
| comp-version-service | ent-label-version |
| comp-augment-service | ent-data-aug |
| comp-meta-service | ent-data-meta, ent-data-meta-review |
| comp-preset-service | ent-label-preset |
| comp-portal-suite | ent-portal-user-video |

## 7. REFERS_TO — ARCHITECTURE → 기타

| Source | Target | 비고 |
|---|---|---|
| arch-authoring-subsystem | (모든 COMPONENT) | 서브시스템 내 모듈 참조 |
| arch-authoring-subsystem | (4개 INTERFACE) | 외부 계약 참조 |
| arch-authoring-subsystem | (7개 FEATURE) | 기능군 참조 |

## 추적성 체인 예시 (라벨링 저장 → Gitea 커밋)

```
sr-sfr-08 (저작도구 고도화)
 └─ DERIVES_FROM
    req-version-gitea
     └─ DERIVES_FROM
        uc-label-commit
         ├─ PERFORMED_BY  actor-worker, ext-gitea
         ├─ BELONGS_TO    feat-version-control
         └─ REALIZES
            comp-label-service, comp-version-service, comp-gitea-client, if-gitea-contents
             ├─ DEPENDS_ON  ent-data-lbl, ent-label-master, ent-label-version (데이터)
             └─ DEPENDS_ON  if-gitea-contents (외부 계약)
```

## 추적성 체인 예시 (시계열 메타 외부 VLM 호출)

```
sr-sfr-03 (시계열 메타)
 └─ DERIVES_FROM
    req-vlm-timeseries
     └─ DERIVES_FROM
        uc-vlm-timeseries-request
         ├─ PERFORMED_BY  actor-batch-system, ext-vlm-service
         ├─ BELONGS_TO    feat-meta-pipeline
         └─ REALIZES
            comp-vlm-client, if-vlm-timeseries-spi
             ├─ DEPENDS_ON  ent-data-meta, ent-data-meta-review (데이터)
             └─ DEPENDS_ON  if-vlm-timeseries-spi (외부 계약)
```

## ID 맵 (등록 후 채움)

각 노드 등록 응답의 `node_id` 를 본 표에 매핑하여 링크 작성 시 사용.

| 그룹 | handle | node_id |
|---|---|---|
| 외부 ACTOR (6) | ext-control-server / ext-portal-server / ext-deidentify-sw / ext-gitea / ext-generative-ai / ext-vlm-service | (TBD ×6) |
| 사람 + 내부 ACTOR (4) | actor-reviewer / actor-worker / actor-portal-user / actor-batch-system | (TBD ×4) |
| FEATURE (7) | feat-auto-labeling / feat-labeling-authoring / feat-meta-pipeline / feat-review-workflow / feat-version-control / feat-augmentation / feat-portal-and-admin | (TBD ×7) |
| SOURCE_REQUIREMENT (7) | sr-sfr-03 / sr-sfr-06-07 / sr-sfr-08 / sr-sfr-09 / sr-sfr-15 / sr-sfr-16-17 / sr-nfr-core | (TBD ×7) |
| REQUIREMENT (15) | req-* | (TBD ×15) |
| ENTITY (15) | ent-* | (TBD ×15) |
| USECASE (16) | uc-* | (TBD ×16) |
| COMPONENT (16) | comp-* | (TBD ×16) |
| INTERFACE (4) | if-augment-result-handover / if-deidentify-spi / if-gitea-contents / if-vlm-timeseries-spi | (TBD ×4) |
| ARCHITECTURE (1) | arch-authoring-subsystem | (TBD) |
