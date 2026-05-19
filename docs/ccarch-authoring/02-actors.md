# ACTOR 노드 (사용자 + 내부 배치 행위자)

> 본 도구의 사람 사용자 3종 + 내부 자동 행위자 1종.
> 외부 시스템 ACTOR는 `01-external-systems.md` 참조.

## A1. 검수자 (REVIEWER)

```json
{
  "type": "ACTOR",
  "title": "검수자(REVIEWER)",
  "content": "**역할 코드**: `REVIEWER` — 작업 배정·검수 권한과 함께 사용자 관리·시스템 설정·프리셋 관리 권한도 보유\n**진입 채널**: INTERNAL — 관제서버(ext-control-server) 발급 JWT 인계\n\n**본 도구에서 수행하는 행위**\n- WORKER에게 라벨링 작업 배정·재배정·이력 조회 (완료 작업 재배정 차단)\n- 검수 승인/반려, 라벨 diff/롤백, 검수 이슈 코멘트\n- 사용자/시스템 설정/프리셋 관리 (`/manage/*`)\n- 데이터 증강 요청·결과 검토·승인 (검수 완료 영상만 허용)\n- 외부 시스템이 인계한 메타데이터 검토·수정 (SCR-AUTO-002)\n- GIS 기반 CCTV 자원·사각지대·재난 통계 조회\n- 침수 시범 대시보드 확인\n- 비식별 처리 결과 검토\n\n**비-책임**: 본 도구는 REVIEWER 사용자 계정 자체를 발급/관리하지 않음 — 관제서버가 발급한 JWT의 `role='REVIEWER'` 클레임을 신뢰",
  "attrs": {"role": "USER"},
  "_handle": "actor-reviewer"
}
```

## A2. 라벨링 작업자 (WORKER)

```json
{
  "type": "ACTOR",
  "title": "라벨링 작업자(WORKER)",
  "content": "**역할 코드**: `WORKER`\n**진입 채널**: INTERNAL — 관제서버 발급 JWT 인계\n\n**본 도구에서 수행하는 행위**\n- 본인에게 배정된 작업 목록 조회 (`/task` WORKER 시각)\n- 라벨링 캔버스 진입(`/label/:id`) — BBox/Polygon/Mask/SAM2 Track 도구\n- 라벨 저장·검수 제출 (저장 시 Gitea 자동 커밋 트리거)\n- 본인 작업의 이슈 확인·해결 표시\n- 본인 처리량/labelCount 통계 조회\n\n**보안**: LabelAccessGuard로 본인 배정 외 프레임 접근 시 403 (CWE-639 IDOR 방어)",
  "attrs": {"role": "USER"},
  "_handle": "actor-worker"
}
```

## A3. 포털 회원 (PORTAL_USER)

```json
{
  "type": "ACTOR",
  "title": "포털 회원(PORTAL_USER)",
  "content": "**역할 코드**: `PORTAL_USER`\n**진입 채널**: PORTAL — 포털 서버(ext-portal-server) 발급 JWT 인계\n\n**본 도구에서 수행하는 행위**\n- 본인 데이터에 대한 간편 라벨링 (`/portal/label`) — YOLO+SAM2 오토라벨링 체험\n- 본인 데이터 라벨 조회·수정 (저작도구 기능 한정)\n\n**본 도구 책임 외 (외부 포털 자체 책임)**\n- 회원가입·로그인·인증 (이메일/SMS 인증, 비밀번호 정책, 마이페이지, 탈퇴 처리)\n- 영상 업로드(TUS 등)\n- 학습데이터 다운로드 카드/D-day/만료 처리 UI\n- 공지사항/FAQ/매뉴얼",
  "attrs": {"role": "USER"},
  "_handle": "actor-portal-user"
}
```

## A4. 배치 처리 시스템 (BATCH_SYSTEM) — 내부 행위자

```json
{
  "type": "ACTOR",
  "title": "배치 처리 시스템(BATCH_SYSTEM)",
  "content": "**역할**: 본 도구 내부의 Quartz 트리거 기반 자동 처리 행위자 (인증 불필요)\n\n**본 도구 내에서 수행하는 행위**\n- 1건/분 영상 선점 (Quartz Job interval-sec=60)\n- FrameExtractStep (FFmpeg)\n- DeidentifyStep — 모든 영상에 대해 `DeidentifyClient` → ext-deidentify-sw 호출 (분기 없음)\n- YoloAutolabelStep / Sam2SegmentStep — 본 도구 내부 ai-server(comp-ai-server) 호출\n- VlmTimeseriesStep — 외부 VLM 서비스(ext-vlm-service) 호출로 시계열 메타 생성. 결과는 `LS_DATA_META`(META_TYPE_CD='VLM') + `LS_DATA_META_REVIEW`(AUTO_GENERATED|PENDING) 적재 → REVIEWER 검토 대상\n- 상태 전이: PENDING → FRAME_EXTRACT → DEIDENTIFY → YOLO → SAM2 → VLM_TIMESERIES → COMPLETED\n- 실패 시 재시도(max=3, exp backoff), 영구 실패 시 FAILED\n\n**프로파일**: local 비활성(수동 트리거), dev/stg/prd 자동\n\n**비-책임**\n- VLM 모델 본체는 외부 서비스. 본 도구는 호출자 + 결과 적재만 담당\n- 영상 단위 일반 메타는 중계서버 송신에 포함되므로 본 행위자가 추출하지 않음",
  "attrs": {"role": "SYSTEM"},
  "_handle": "actor-batch-system"
}
```

---

## 등록 순서

1. actor-reviewer
2. actor-worker
3. actor-portal-user
4. actor-batch-system
