# Version Commit API Test Report

## Endpoint

| Method | URL | Description |
|--------|-----|-------------|
| POST | `/v1/frames/{srcSn}/commit` | 현재 라벨 상태 Gitea 커밋 |

## Test Summary

| # | Test Name | Status | Request | Response |
|---|-----------|--------|---------|----------|
| 1 | POST_frames_commit_REVIEWER_정상_200_commitSha_반환 | PASS | `POST /v1/frames/{srcSn}/commit` + REVIEWER JWT + `{"message": null}` | 200 `{"data":{"commitSha":"aabb...","committedAt":"..."}}` |
| 2 | POST_frames_commit_배정된_WORKER_정상_200 | PASS | `POST /v1/frames/{srcSn}/commit` + WORKER(assigned) JWT + `{"message":"수동 커밋 메시지"}` | 200 `{"data":{"commitSha":"1111...","committedAt":"..."}}` |
| 3 | POST_frames_commit_미인증시_401 | PASS | `POST /v1/frames/{srcSn}/commit` (no Authorization) | 401 |
| 4 | POST_frames_commit_미존재_srcSn_404 | PASS | `POST /v1/frames/999999/commit` + REVIEWER JWT | 404 |
| 5 | POST_frames_commit_미배정_WORKER_403 | PASS | `POST /v1/frames/{srcSn}/commit` + WORKER(not assigned) JWT | 403 |
| 6 | POST_frames_commit_PORTAL_사용자_403 | PASS | `POST /v1/frames/{srcSn}/commit` + PORTAL_USER JWT | 403 |
| 7 | POST_frames_commit_body_없이_호출해도_정상_200 | PASS | `POST /v1/frames/{srcSn}/commit` + REVIEWER JWT + `{}` | 200 |

## Request / Response

### POST /v1/frames/{srcSn}/commit

**Request:**
```json
{
  "message": "optional commit message (nullable)"
}
```
- `message`: 선택 사항. null 또는 생략 시 VersionService가 자동 생성.
- Body 자체가 optional (`@RequestBody(required = false)`).

**Response (200):**
```json
{
  "success": true,
  "data": {
    "commitSha": "aabbccdd1122334455667788aabbccdd11223344",
    "committedAt": "2026-05-28T01:50:55.123Z"
  },
  "message": null,
  "errorCode": null
}
```

**Error Responses:**
- 401: 인증 실패 (토큰 없음/만료)
- 403: PORTAL 채널 또는 미배정 WORKER
- 404: srcSn에 해당하는 프레임 없음
- 409: 비식별 재처리 중인 영상 (VersionService 내부에서 발생)

## Security

- `@PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")` — 역할 기반 접근 제어
- PORTAL 채널 추가 차단 (`VersionService.isCommittable()`)
- IDOR 방어: `LabelAccessGuard.verifyAndGet()` — WORKER는 본인 배정 프레임만
- 비식별 재처리 잠금: `WorkLockService.isRawLocked()` 검사 (VersionService 내부)
