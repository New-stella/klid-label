# 추적성 매트릭스 (Link Definitions)

> 모든 링크는 ccarch MCP `mcp__ccarch__ccarch_create_link` 로 등록한다.
> 양쪽 노드가 같은 workspace에 사전 존재해야 하며, 자기참조는 INVALID_INPUT으로 거절된다.
> 등록 시 `idempotencyKey`(UUID v4) 전달.

## 1. DERIVES_FROM — REQUIREMENT → SOURCE_REQUIREMENT

| Source REQUIREMENT | Target SOURCE_REQUIREMENT | 비고 |
|---|---|---|
| req-vlm-filter | sr-sfr-01 | |
| req-dedup-ingest | sr-sfr-02 | |
| req-center-monitor | sr-sfr-04 | |
| req-vms-adapter | sr-sfr-05 | |
| req-network-bridge | sr-sfr-05 | 동일 SR에 2건 (수집·망연동) |
| req-batch-orchestrator | sr-sfr-01, sr-sfr-08 | 다중 매핑 |
| req-conditional-deident | sr-sfr-09 | |
| req-yolo-sam2 | sr-sfr-08 | |
| req-track-interpolate | sr-sfr-08 | |
| req-labeling-canvas | sr-sfr-08 | |
| req-label-precision | sr-sfr-08 | |
| req-label-master-pool | sr-sfr-08 | |
| req-assign-task | sr-sfr-14, sr-sfr-08 | |
| req-review-state-machine | sr-sfr-08 | |
| req-review-detail | sr-sfr-08 | |
| req-version-gitea | sr-sfr-08 | |
| req-augment-completed-only | sr-sfr-06, sr-sfr-07 | |
| req-meta-review | sr-sfr-03, sr-sfr-12 | 범위 축소 명시 |
| req-vlm-verify-objects | sr-sfr-12 | |
| req-flood-pilot | sr-sfr-10 | |
| req-gis-cctv | sr-sfr-14 | |
| req-mobile-cert-login | sr-sfr-14, sr-ser-01 | |
| req-label-preset | sr-sfr-08 | |
| req-worker-stat | sr-sfr-14 | |
| req-sysconfig-cache | sr-sfr-14 | |
| req-portal-signup | sr-sfr-15, sr-ser-01 | |
| req-portal-tus-upload | sr-sfr-15, sr-sfr-09 | |
| req-portal-simple-label | sr-sfr-15 | |
| req-image-100k | sr-sfr-16 | 산출물 |
| req-video-5k | sr-sfr-17 | 산출물 |
| req-nfr-performance | sr-per-02, sr-qur-03 | |
| req-nfr-responsive | sr-sir-04 | |
| req-nfr-security | sr-ser-01, sr-sfr-18 | |
| req-nfr-test | sr-ter-01 | |
| req-nfr-dar | sr-dar-standards | |

## 2. DERIVES_FROM — USECASE → REQUIREMENT

| Source USECASE | Target REQUIREMENT |
|---|---|
| uc-video-ingest | req-vlm-filter |
| uc-video-dedup | req-dedup-ingest |
| uc-vms-adapter | req-vms-adapter |
| uc-center-monitor | req-center-monitor |
| uc-auto-pipeline | req-batch-orchestrator, req-yolo-sam2 |
| uc-deidentify | req-conditional-deident |
| uc-deident-review | req-conditional-deident |
| uc-label-edit | req-labeling-canvas |
| uc-sam2-track | req-labeling-canvas, req-yolo-sam2 |
| uc-label-commit | req-version-gitea |
| uc-label-master | req-label-master-pool |
| uc-assign-task | req-assign-task |
| uc-review-decision | req-review-state-machine, req-review-detail |
| uc-review-issue | req-review-detail |
| uc-version-diff-rollback | req-version-gitea |
| uc-augment-flow | req-augment-completed-only |
| uc-meta-review | req-meta-review |
| uc-flood-dashboard | req-flood-pilot |
| uc-gis-view | req-gis-cctv |
| uc-preset-manage | req-label-preset |
| uc-stat-view | req-worker-stat |
| uc-portal-signup | req-portal-signup |
| uc-portal-upload-label | req-portal-tus-upload, req-portal-simple-label |

## 3. PERFORMED_BY — USECASE → ACTOR

| USECASE | ACTOR |
|---|---|
| uc-video-ingest | actor-batch-system |
| uc-video-dedup | actor-batch-system |
| uc-vms-adapter | actor-reviewer |
| uc-center-monitor | actor-reviewer |
| uc-auto-pipeline | actor-batch-system |
| uc-deidentify | actor-batch-system |
| uc-deident-review | actor-reviewer |
| uc-label-edit | actor-worker |
| uc-sam2-track | actor-worker |
| uc-label-commit | actor-worker |
| uc-label-master | actor-reviewer |
| uc-assign-task | actor-reviewer |
| uc-review-decision | actor-reviewer |
| uc-review-issue | actor-reviewer |
| uc-version-diff-rollback | actor-reviewer |
| uc-augment-flow | actor-reviewer |
| uc-meta-review | actor-reviewer |
| uc-flood-dashboard | actor-reviewer |
| uc-gis-view | actor-reviewer |
| uc-preset-manage | actor-reviewer |
| uc-stat-view | actor-reviewer |
| uc-portal-signup | actor-portal-user |
| uc-portal-upload-label | actor-portal-user |

## 4. BELONGS_TO — USECASE → FEATURE

| USECASE | FEATURE |
|---|---|
| uc-video-ingest | feat-video-ingest |
| uc-video-dedup | feat-video-ingest |
| uc-vms-adapter | feat-video-ingest |
| uc-center-monitor | feat-video-ingest |
| uc-auto-pipeline | feat-auto-labeling |
| uc-deidentify | feat-auto-labeling |
| uc-deident-review | feat-auto-labeling |
| uc-label-edit | feat-labeling-authoring |
| uc-sam2-track | feat-labeling-authoring |
| uc-label-commit | feat-labeling-authoring |
| uc-label-master | feat-labeling-authoring |
| uc-assign-task | feat-review-workflow |
| uc-review-decision | feat-review-workflow |
| uc-review-issue | feat-review-workflow |
| uc-version-diff-rollback | feat-version-control |
| uc-augment-flow | feat-augmentation |
| uc-meta-review | feat-meta-review |
| uc-flood-dashboard | feat-flood-pilot |
| uc-gis-view | feat-admin-stat |
| uc-preset-manage | feat-admin-stat |
| uc-stat-view | feat-admin-stat |
| uc-portal-signup | feat-external-portal |
| uc-portal-upload-label | feat-external-portal |

## 5. REALIZES — USECASE/REQUIREMENT → COMPONENT|INTERFACE

| Source | Target | type |
|---|---|---|
| uc-auto-pipeline | comp-batch-orchestrator | REALIZES |
| uc-auto-pipeline | comp-ai-server-client | REALIZES |
| uc-video-ingest | comp-video-ingest-service | REALIZES |
| uc-video-ingest | comp-ai-server | REALIZES |
| uc-vms-adapter | comp-vms-adapter | REALIZES |
| uc-center-monitor | comp-center-monitor-service | REALIZES |
| uc-deidentify | comp-deidentify-client | REALIZES |
| uc-deident-review | comp-deidentify-client | REALIZES |
| uc-label-edit | comp-label-canvas | REALIZES |
| uc-label-edit | comp-label-service | REALIZES |
| uc-label-edit | if-label-api | REALIZES |
| uc-sam2-track | comp-ai-server | REALIZES |
| uc-label-commit | comp-version-service | REALIZES |
| uc-label-commit | comp-gitea-client (= part of comp-version-service) | REALIZES |
| uc-label-master | comp-label-service | REALIZES |
| uc-assign-task | comp-assignment-service | REALIZES |
| uc-assign-task | if-assignment-api | REALIZES |
| uc-assign-task | if-task-board-api | REALIZES |
| uc-review-decision | comp-review-service | REALIZES |
| uc-review-decision | if-review-api | REALIZES |
| uc-version-diff-rollback | comp-version-service | REALIZES |
| uc-version-diff-rollback | if-version-api | REALIZES |
| uc-augment-flow | comp-augment-service | REALIZES |
| uc-augment-flow | if-augment-api | REALIZES |
| uc-meta-review | comp-meta-service | REALIZES |
| uc-meta-review | if-meta-api | REALIZES |
| uc-flood-dashboard | comp-stat-service | REALIZES |
| uc-gis-view | comp-stat-service | REALIZES |
| uc-preset-manage | comp-preset-service | REALIZES |
| uc-preset-manage | if-preset-api | REALIZES |
| uc-stat-view | comp-stat-service | REALIZES |
| uc-stat-view | if-stat-api | REALIZES |
| uc-portal-signup | comp-portal-suite | REALIZES |
| uc-portal-upload-label | comp-portal-suite | REALIZES |

## 6. DEPENDS_ON — COMPONENT → COMPONENT|INTERFACE|ENTITY

| Source COMPONENT | Target | Type | 비고 |
|---|---|---|---|
| comp-batch-orchestrator | comp-ai-server-client | DEPENDS_ON | YOLO/SAM2/VLM 호출 |
| comp-batch-orchestrator | comp-deidentify-client | DEPENDS_ON | 비식별 |
| comp-batch-orchestrator | ent-data-raw | DEPENDS_ON | |
| comp-batch-orchestrator | ent-batch-log | DEPENDS_ON | |
| comp-ai-server-client | if-ai-server-spi | DEPENDS_ON | |
| comp-deidentify-client | if-deidentify-api | DEPENDS_ON | |
| comp-gitea-client (=part of comp-version-service) | if-gitea-contents | DEPENDS_ON | |
| comp-label-service | ent-data-lbl | DEPENDS_ON | |
| comp-label-service | ent-label-master | DEPENDS_ON | |
| comp-label-service | comp-version-service | DEPENDS_ON | SaveCommitFlow |
| comp-label-canvas | if-label-api | DEPENDS_ON | FE → BE |
| comp-label-canvas | comp-ai-server | DEPENDS_ON | SAM2 Track 도구 (via BE) |
| comp-review-service | ent-raw-data-status | DEPENDS_ON | |
| comp-review-service | ent-data-issue | DEPENDS_ON | |
| comp-assignment-service | ent-task-assignment | DEPENDS_ON | |
| comp-version-service | ent-label-version | DEPENDS_ON | |
| comp-augment-service | ent-data-aug | DEPENDS_ON | |
| comp-meta-service | ent-data-meta | DEPENDS_ON | |
| comp-preset-service | ent-label-preset | DEPENDS_ON | |
| comp-portal-suite | ent-portal-user-video | DEPENDS_ON | |
| comp-portal-suite | comp-jwt-filter | DEPENDS_ON | 채널/역할 가드 |
| comp-stat-service | ent-data-lbl | DEPENDS_ON | labelCount 집계 |
| comp-video-ingest-service | comp-vms-adapter | DEPENDS_ON | |

## 7. REFERS_TO — ARCHITECTURE → 기타 노드

ARCHITECTURE 노드는 직접 의존 관계는 없지만, 상위 문서로서 다음을 REFERS_TO 로 연결한다.

| Source ARCHITECTURE | Target | 비고 |
|---|---|---|
| arch-system-overall | arch-authoring-subsystem | 시스템 → 서브시스템 |
| arch-authoring-subsystem | (전체 COMPONENT) | 핵심 컴포넌트 참조 |
| arch-authoring-subsystem | (전체 INTERFACE) | 핵심 API/SPI 참조 |
| arch-authoring-subsystem | feat-video-ingest, feat-auto-labeling, feat-labeling-authoring, feat-review-workflow, feat-version-control, feat-augmentation, feat-meta-review, feat-flood-pilot, feat-external-portal, feat-admin-stat | 10개 FEATURE 모두 참조 |

## 추적성 체인 요약 (예: 라벨링 한 줄)

```
sr-sfr-08 (저작도구 고도화 발췌)
  └─ DERIVES_FROM
     req-labeling-canvas
       └─ DERIVES_FROM
          uc-label-edit
            ├─ PERFORMED_BY  actor-worker
            ├─ BELONGS_TO    feat-labeling-authoring
            └─ REALIZES
               comp-label-canvas, comp-label-service, if-label-api
                  └─ DEPENDS_ON  ent-data-lbl, ent-label-master
```

## ACTOR / FEATURE ID 맵 (등록 후 채울 것)

| handle | ccarch node_id |
|---|---|
| actor-reviewer | (TBD) |
| actor-worker | (TBD) |
| actor-portal-user | (TBD) |
| actor-batch-system | (TBD) |
| feat-video-ingest | (TBD) |
| feat-auto-labeling | (TBD) |
| feat-labeling-authoring | (TBD) |
| feat-meta-review | (TBD) |
| feat-review-workflow | (TBD) |
| feat-version-control | (TBD) |
| feat-augmentation | (TBD) |
| feat-flood-pilot | (TBD) |
| feat-external-portal | (TBD) |
| feat-admin-stat | (TBD) |

> 노드 등록 단계에서 응답 `node_id`를 채워 넣으면 링크 작성 시 `fromNodeId/toNodeId` 매핑에 직접 사용 가능.
