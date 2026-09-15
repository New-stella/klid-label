import tailwindcss from '@tailwindcss/postcss';

import scopePortalBaseLayer from './postcss/scope-portal-base-layer.js';

// [@design INT-013]
export default {
  // ⚠ 배열 형태다 — «순서가 계약»이기 때문이다. 객체 형태는 키 순서에 기대게 되고, 두 번째
  //   플러그인이 첫 번째의 결과(펼쳐진 `@import`)를 봐야 한다는 사실이 코드에 드러나지 않는다.
  plugins: [
    // Tailwind CSS v4 — PostCSS 플러그인이 전용 패키지로 분리됐고(구 `tailwindcss` 직접 지정 폐기),
    // import 번들링·vendor prefixing 을 자체 처리하므로 `autoprefixer` 는 제거한다.
    tailwindcss(),
    // ★ 포털 채널에서만 동작한다 — 전역 리셋을 우리 마운트 앵커 안으로 좁힌다.
    //   반드시 Tailwind «뒤»여야 한다: 그 전에는 `@import`(preflight)가 아직 펼쳐지지 않아
    //   좁힐 규칙이 존재하지 않는다. 관제 채널에서는 아무 일도 하지 않는다(산출물 무변경).
    scopePortalBaseLayer(),
  ],
};
