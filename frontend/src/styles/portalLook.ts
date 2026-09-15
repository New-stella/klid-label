/**
 * 포털 모습 — 포털 채널 화면이 쓰는 포털 저장소의 토큰·부품 스킨. [@design INT-013]
 *
 * ★ 차례는 포털 앱 진입점(KLID_Portal `src/main.tsx`)과 **같은 목록·같은 차례**다.
 *   뒤에 온 테마가 이겨야 하고, 시각보정 파일이 빠지면 테마의 계산식이 통째로 무효가 된다.
 *
 * ⚠ 관제 채널에 실리면 안 된다 — KRDS 는 `html { font-size: 62.5% }` 를 깔아 관제 화면의
 *   Tailwind 치수를 전부 줄인다. 그래서 이 모듈은 포털 채널 전용 레이아웃(지연 로드)만 불러온다.
 */
import 'krds-react/dist/index.css';
import '@portal/styles/krds-theme.css';
import '@portal/styles/krds-focus.css';
import '@portal/index.css';
import '@portal/styles/klid-optical.css';
