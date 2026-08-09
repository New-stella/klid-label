# 와이어프레임 생성기

`generate-wireframes.py` — LogiCraft `screen_spec` 의 `data.sections` 에서 와이어프레임 HTML 을
**결정론적으로** 생성한다. LLM 개입 0 · 같은 입력이면 항상 같은 출력.

## 왜 있나

와이어프레임은 손으로 그리면 섹션을 고칠 때마다 어긋난다(실제로 11개 화면이 stale 이었다).
sections 가 진실원이므로 거기서 그리면 어긋날 수 없다.

## 사용

```bash
# 1) 최신 ITEM 내려받기 (mc-logi-screen-kit 의 download-kit.mjs)
LOGICRAFT_API_KEY=lc_... LOGICRAFT_API_BASE=https://<host>/api \
  node <path>/download-kit.mjs --project <uuid> --out <kit>/.staging --ids "SCREEN-001,..."

# 2) HTML 생성
python3 generate-wireframes.py <kit>/.staging/screen_spec/_raw ./out

# 3) 업로드 — MCP `upload_static_render`
#    render_id=main · surface=page · width=1440
#    ⚠ sections 파라미터는 보내지 않는다(page 렌더는 ITEM-level sections 를 쓴다)
```

## 규칙

- `wireframe.css` link 를 본문에 넣지 않는다 — 서버 sanitize 가 자동 주입한다.
- `<style>` · `<script>` · 외부 폰트/CDN 금지(sanitize 가 제거하고 sandbox 가 차단).
- 색상·border·shadow 는 `wf-*` 클래스 또는 `var(--wf-*)` 로만.
- 새 `wf-*` 변형을 임의로 만들지 않는다 — `wireframe.css` 에 정의가 없으면 효과가 0 이다.

## 산출물에 담기는 것

섹션당 카드(메타 칩 role/layout/API + '섹션 상세' 링크) · 컴포넌트 프리뷰(타입별 마크업) ·
본문 끝의 섹션 상세 모달(설명 · Components 목록 + 검증규칙/바인딩/트리거 API · API · Feature).

REST 업로드 엔드포인트(`POST /projects/{id}/items/{SCREEN}/static-render`)는 **세션 인증만
받으므로 API 키로는 401** 이다. 업로드는 MCP 도구로 한다.
