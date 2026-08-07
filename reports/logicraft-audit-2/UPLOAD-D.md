# LogiCraft 와이어프레임 업로드 — 그룹 D

project_id: `4ece2c3f-8e99-46f5-9580-71108a76e578` / render_id: `main` / surface: `page` / width: `1440`

| SCREEN-ID | 화면명 | 원본 바이트 | sanitized_bytes | action | 성공 여부 |
|---|---|---:|---:|---|:---:|
| SCREEN-022 | 증강 요청 화면 | 13,967 | 13,970 | replace | ✅ |
| SCREEN-027 | 오토라벨 테스트 화면 (개발) | 14,413 | 14,351 | replace | ✅ |
| SCREEN-009 | 영상 상세 화면 | 13,034 | 12,972 | replace | ✅ |
| SCREEN-031 | 공지 상세 화면 | 12,111 | 12,109 | replace (total_renders=2, 기존 `delete-confirm` 렌더 보존) | ✅ |
| SCREEN-010 | 라벨 이력 화면 | 13,743 | 13,621 | replace | ✅ |

비고: SCREEN-031 은 `render_id=main` 을 명시 지정해 기존 `delete-confirm` 렌더를 덮어쓰지 않았음(업로드 후 `total_renders: 2` 로 확인).
