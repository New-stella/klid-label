# ACTOR 노드 정의

> ccarch type: `ACTOR` / 필수 필드: `title`, `content` / 옵션: `description`, `role`(USER\|ADMIN\|SYSTEM)
> 핸들(`handle`)은 본 폴더 내부의 식별자(우리가 매김)이며, ccarch 등록 후의 `node_id`는 응답에서 받아 traceability.md에 매핑한다.

## A1. 검수자 (REVIEWER)

```json
{
  "type": "ACTOR",
  "title": "검수자(REVIEWER)",
  "content": "**역할 코드**: `REVIEWER`\n\n학습데이터 저작도구의 운영 책임자. 1차 사업의 ADMIN 권한이 V1.3에서 흡수되어, 작업자 배정·검수 승인뿐 아니라 시스템 설정·사용자 관리·프리셋 관리까지 담당한다.\n\n**주요 권한**\n- 작업자(WORKER)에게 라벨링 작업 배정 / 재배정 / 배정 이력 조회\n- 검수 승인·반려, 검수 상세 화면 진입, 라벨 diff/롤백\n- 사용자 관리(`/manage/users`), 시스템 설정(`/manage/sysconfig`), 프리셋(`/manage/presets`)\n- 데이터 증강 요청 검토·승인 (검수 완료 영상에 한해)\n- 외부 메타데이터(VLM 시계열) 검토·수정 (SCR-AUTO-002)\n\n**진입 채널**: INTERNAL — 관제서버 발급 JWT 토큰 인계\n**RFP 매핑**: SFR-14(관제지원시스템 기능), SFR-08(저작도구 고도화), SFR-09(비식별 처리내역 확인), SFR-12(유괴 탐지 결과 검토)",
  "attrs": {"role": "USER"},
  "_handle": "actor-reviewer"
}
```

## A2. 라벨링 작업자 (WORKER)

```json
{
  "type": "ACTOR",
  "title": "라벨링 작업자(WORKER)",
  "content": "**역할 코드**: `WORKER`\n\nREVIEWER가 배정한 라벨링 작업을 수행하는 운영 인력. 본인에게 배정된 프레임에 한해 라벨을 수정·제출하며, 시스템 설정/사용자 관리 권한은 없다.\n\n**주요 권한**\n- 본인 배정 작업 목록 조회 (`/task` WORKER 시각)\n- 라벨링 캔버스 진입 (`/label/:id`) — BBox/Polygon/Segmentation/SAM2 Track\n- 라벨 저장·검수 제출 (LabelAccessGuard로 본인 배정만 접근 가능, CWE-639 IDOR 방어)\n- 작업자 통계(`/stat`) — 본인 처리량·labelCount 조회\n\n**진입 채널**: INTERNAL — 관제서버 발급 JWT 토큰 인계\n**RFP 매핑**: SFR-08(저작도구 라벨링), SFR-16(이미지 10만장 산출), SFR-17(영상 5,000건 산출)",
  "attrs": {"role": "USER"},
  "_handle": "actor-worker"
}
```

## A3. 포털 회원 (PORTAL_USER)

```json
{
  "type": "ACTOR",
  "title": "포털 회원(PORTAL_USER)",
  "content": "**역할 코드**: `PORTAL_USER`\n\n외부 채널(AI 영상학습 사용자 포털)을 통해 가입한 일반 사용자. 본인이 업로드한 영상에 한해 체험·테스트 목적의 간편 라벨링과 기간 제한 내 다운로드를 수행한다.\n\n**주요 권한**\n- 회원가입(이메일/SMS 인증), 로그인, 마이페이지\n- 영상 TUS 업로드 (재개 가능, 5GB까지) — 본인 데이터만 접근\n- 간편 라벨링(`/portal/label`) — 오토라벨링(YOLO+SAM2) 체험\n- 본인 업로드 데이터 기간 내 다운로드\n- 공지사항·FAQ·매뉴얼 열람\n\n**미제공 (V1.5)**: 학습데이터셋 다운로드(외부 포털 자체 책임), 기여도 점수, VLM/버전관리/검수\n**진입 채널**: PORTAL — 포털 서버 발급 JWT 토큰 인계\n**비기능**: 반응형 웹(PC/태블릿/모바일), WCAG 2.1 AA\n**RFP 매핑**: SFR-15(영상학습 사용자 포털), SFR-09(개인정보 비식별 처리), SIR-04(반응형)",
  "attrs": {"role": "USER"},
  "_handle": "actor-portal-user"
}
```

## A4. 배치 처리 시스템 (BATCH_SYSTEM)

```json
{
  "type": "ACTOR",
  "title": "배치 처리 시스템(BATCH_SYSTEM)",
  "content": "**역할 코드**: 시스템 자동 (인증 불필요, Quartz 트리거)\n\n저작도구 내부의 자동 처리 행위자. 영상이 적재되면 1건/분 간격으로 프레임 추출 → 조건부 비식별화 → YOLO → SAM2 → 트랙 보간 → VLM 객체 검증 → 완료 단계를 수행한다. 실패 시 최대 3회 지수 백오프 재시도, 외부 호출(Gitea/AI/Deidentify)은 Resilience4j CircuitBreaker로 보호.\n\n**주요 동작**\n- FrameExtractStep (FFmpeg)\n- DeidentifyStep — `PRVC_TYPE_CD in (PRVC, PSDO)`일 때만 호출, `ANONY`는 원본만 저장\n- YoloAutolabelStep → AiServerClient.predictYoloTrack\n- Sam2SegmentStep → AiServerClient.predictSam2Track\n- TrackInterpolationStep (CVAT portable-modules/01 포팅)\n- VlmMetaStep → AiServerClient.predictVlmMeta (객체 검증 한정, V1.7)\n\n**상태 머신**: `PENDING → FRAME_EXTRACT → DEIDENTIFY → YOLO → SAM2 → TRACK_INTERPOLATION → VLM_META → COMPLETED` (실패 시 `FAILED`, 재시도 큐로 이동)\n**RFP 매핑**: SFR-01(VLM 수집·정제 — 외부 책임 부분 제외), SFR-08(오토라벨링), SFR-09(비식별 자동 호출), SFR-10(침수 모델 시범적용)",
  "attrs": {"role": "SYSTEM"},
  "_handle": "actor-batch-system"
}
```

---

## 등록 순서 (`mcp__ccarch__ccarch_create_node` 호출 시)

ACTOR는 USECASE보다 먼저 등록한다 (PERFORMED_BY 링크의 target).

1. `actor-reviewer`
2. `actor-worker`
3. `actor-portal-user`
4. `actor-batch-system`

각 호출 시 `idempotencyKey`로 새 UUID v4를 전달하고, 응답의 `node_id`를 `10-traceability.md` "ACTOR ID 맵"에 채워 넣는다.
