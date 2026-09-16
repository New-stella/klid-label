# 포털 테마가 참조하는 자산

부모 포털(KLID_Portal)에서 들여온 테마 CSS 가 **파일 경로로** 가리키는 자산만 둔다.

- `MOIS.svg` — 상위기관(행정안전부) 심벌. `styles/portal/krds-theme.css` 의
  `.krds-identifier .logo` 가 `url('../assets/MOIS.svg')` 로 참조한다.

★ **자리가 `styles/assets/` 인 것은 우연이 아니다.** 부모 포털에서는 그 CSS 가 `src/styles/` 에
있어 `../assets/` 가 `src/assets/` 였는데, 우리는 한 단 더 깊은 `src/styles/portal/` 에 두었으므로
같은 상대경로가 **`src/styles/assets/`** 를 가리킨다. `src/assets/` 에 두면 빌드가 조용히
*"didn't resolve at build time"* 로 넘어간다(오류가 아니라 경고라 더 늦게 드러난다 — 실측).

★ **우리 화면이 그리는 자리는 아니다.** 그 규칙이 칠하는 머리·꼬리 영역은 Host 가 소유하므로
포털 채널에서 이 그림이 우리 산출물로 보일 일은 사실상 없다. 그럼에도 두는 까닭은
**테마 CSS 를 한 글자도 고치지 않기 위해서**다 — 고치면 부모 포털과 갈려 다음 동기화에서
되돌아오고, 비워 두면 빌드가 *"didn't resolve at build time"* 경고를 남기며 실행 중 404 가 된다.

⚠ 이 폴더에 **우리 화면용 그림을 넣지 말 것.** 여기는 벤더 CSS 의 참조를 채우는 자리다.
