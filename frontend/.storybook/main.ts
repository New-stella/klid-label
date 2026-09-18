import type { StorybookConfig } from '@storybook/react-vite';

/**
 * 저작도구 화면 스토리북 (6010) — 포털판 다섯 면(내 작업 · 내 업로드 · 증강 · 마킹 · 라벨링)의
 * 상태를 가짜 응답으로 전부 세워 본다. 포털 스토리북(6008)이 이 화면들을 포털 안 iframe 으로 불러온다.
 *
 * ★ 포털 채널로 띄운다 — 화면 코드가 산출 시점 채널 값으로 포털 틀·안내를 가른다.
 *   이 값은 화면 도구 설정(vite.config.ts)이 읽기 전에 정해져야 해서 이 파일 맨 위에서 둔다.
 */
process.env.VITE_BUILD_CHANNEL = 'portal';

const config: StorybookConfig = {
  stories: ['../src/stories/**/*.stories.@(ts|tsx)'],
  framework: { name: '@storybook/react-vite', options: {} },
  // 견본 그림 · 영상 — 앱 공개 폴더(public/samples)의 것을 그대로 쓴다. 스토리의 가짜 응답과 시연판이 같은 `/samples/...` 를 가리킨다
  staticDirs: ['../public'],
  core: { disableTelemetry: true },
  viteFinal: async (viteConfig) => ({
    ...viteConfig,
    server: {
      ...viteConfig.server,
      // 화면 서버용 보안 머리(액자 막기 등)를 걷는다 — 포털 스토리북(6008)이 이 화면을 iframe 으로 불러온다.
      headers: {},
      proxy: undefined,
    },
  }),
};

export default config;
