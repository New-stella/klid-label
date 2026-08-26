export default {
  plugins: {
    // Tailwind CSS v4 — PostCSS 플러그인이 전용 패키지로 분리됐고(구 `tailwindcss` 직접 지정 폐기),
    // import 번들링·vendor prefixing 을 자체 처리하므로 `autoprefixer` 는 제거한다.
    '@tailwindcss/postcss': {},
  },
};
