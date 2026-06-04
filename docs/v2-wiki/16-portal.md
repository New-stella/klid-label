# 16. 포털 (외부 채널)

> 출처: CLAUDE.md(포털), R2 KLID-AT-ACT-003, ADR-013, 코드(`portal/`, `frontend portal/`)
> 관련: [03 인증·권한](03-auth-roles.md) · [04 화면·IA](04-screens-ia.md)

화면: 포털 홈(데이터마트 영상 선택 `/portal`), `KLID-AT-SC-029`(포털 라벨링 `/portal/label/:id`). 코드: `portal/`(7 파일).

## 16.1 데이터 소스

```
관제서버 → 데이터마트 → 포털 DB 적재 (관제서버 책임)
        ↓
저작도구는 포털 DB에서 Load (PortalDataSourceConfig 듀얼 데이터소스)
```

- 관제서버가 제공한 데이터마트를 포털에 등록 → 포털 사용자가 영상 선택 → 기존 저장 라벨/메타 Load → 라벨링 화면 표시

## 16.2 저장 정책 (단방향)

- **저장 시 원본·데이터마트 미수정** — 사용자별 작업 데이터로 `LS_PORTAL_USER_LABEL`에 **별도 적재**
- 데이터마트에 정합/반영 안 됨 (단방향)
- 다운로드는 사용자 작업 데이터 기준
- 기여도 점수 없음

## 16.3 포털 범위 (ADR-013)

| 기능 | 제공 |
|------|:----:|
| 데이터마트 영상 선택 | ✓ |
| 기존 라벨 확인·수정·저장 | ✓ |
| 본인 데이터 다운로드 | ✓ |
| 오토라벨링(YOLO/SAM2) | ✗ |
| VLM·버전관리·검수 | ✗ |
| 업로드 | ✗ |

## 16.4 UI 특성

- 반응형 웹 (PC/태블릿/모바일), **WCAG 2.1 AA 준수** (NFR-006)
- PortalLayout (모바일 친화, LNB 없음)

## 16.5 관련 데이터 (DB)

`LS_PORTAL_USER_LABEL` (V47) — `PORTAL_USER_NO`, 원본 `LS_DATA_LBL` 미수정. → [18](18-database.md).
