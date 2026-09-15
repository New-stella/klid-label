// [@design INT-013]
/**
 * 덧띄움(모달·서랍·떠 있는 창·도구 안내) 전용 마운트 자리 — **`document.body` 직하 대신 여기.**
 *
 * ## 왜 필요한가
 *
 * `createPortal(node, document.body)` 로 붙인 것은 우리 React 트리 안에 있어도 **DOM 상으로는
 * 마운트 앵커 밖**이다. 포털 채널에서 그것이 두 가지를 동시에 일으킨다:
 *
 *   1. **이번 변경 이전부터 있던 결함** — 앵커가 주는 `--spacing: 4px` 를 못 받는다. 포털 Host 가
 *      루트 글꼴을 62.5% 로 쓰므로 `--spacing` 이 기본값 `0.25rem` = **2.5px** 로 떨어져,
 *      모달·서랍의 여백·크기가 전부 62.5% 로 눌린다(글자 크기는 px 리터럴이라 멀쩡하다 —
 *      「글자는 정상인데 간격만 눌린」 모습이면 이 축을 의심할 것).
 *   2. **이번 변경으로 새로 생길 결함** — 전역 리셋을 앵커 하위로 좁히는 순간, 앵커 밖인
 *      이것들은 리셋마저 못 받아 브라우저 기본 스타일로 되돌아간다.
 *
 * 그래서 **컨테이너를 하나 만들어 거기에 같은 앵커 클래스를 붙인다.** 다섯 곳이 각자
 * `document.body` 를 참조하면 한 곳만 빠뜨려도 조용히 밖으로 떨어지므로 창구를 하나로 모은다.
 *
 * ## 관제 채널에서는 동작이 같다
 *
 * 앵커 클래스가 붙지 않고, 컨테이너는 상자를 만들지 않는다(`display: contents`). 덧띄움 요소는
 * 전부 `position: fixed` 라 위치·크기도 그대로다.
 *
 * ## ⚠ 쌓임 맥락(stacking context)을 만들지 않는다
 *
 * 컨테이너에 `transform`·`filter`·`opacity`·`position`+`z-index`·`will-change`·`contain` 중
 * 무엇이라도 주면 **새 쌓임 맥락**이 생겨 안쪽 `z-50` 이 바깥과 겨루지 못한다 — 모달이 배경 뒤로
 * 들어가거나 잘린다. 그래서 스타일은 `display: contents` **하나뿐**이고, 그 값은 목록 어디에도
 * 없어 쌓임 맥락을 만들지 않는다(상자 자체를 만들지 않으므로 클리핑도 없다).
 *
 * ## 왜 하필 `display: contents` 인가
 *
 * 아무 스타일도 주지 않으면 블록 상자가 하나 생긴다. 자식이 전부 `fixed` 라 높이는 0 이지만,
 * **Host 의 `<body>` 가 flex/grid 면 그 빈 상자가 항목 하나로 끼어들어** 간격(`gap`)이나 격자
 * 한 칸을 차지할 수 있다. `display: contents` 는 상자를 아예 만들지 않아 그 위험이 없고,
 * 커스텀 프로퍼티 상속(`--spacing`)과 선택자 매칭(`.klid-portal-embed *`)은 그대로 남는다.
 *
 * ⚠ `AuthoringRemote` 의 마운트 앵커에는 같은 값을 **쓰지 않는다** — 그쪽은 Host 슬롯이 자식에
 *   거는 레이아웃을 받아야 해서 상자가 필요하다. 여기는 받을 레이아웃이 없다(자식이 전부 fixed).
 */
import { isPortalEmbedChannel } from '@/lib/buildChannel';
import { PORTAL_EMBED_ANCHOR_CLASS } from '@/lib/portalEmbedAnchor';

/** 찾기 쉬우라고 두는 표식 — 선택자로 쓰지 않는다(스타일을 걸지 않는다). */
export const PORTAL_OVERLAY_ROOT_ATTR = 'data-klid-overlay-root';

let cached: HTMLElement | null = null;

/**
 * 덧띄움을 붙일 요소 — 없으면 만들고, 있으면 그대로 쓴다.
 *
 * ⚠ 채널 판정을 **호출할 때마다** 다시 반영한다. `isPortalEmbedChannel()` 이 매번
 *   `import.meta.env` 를 다시 읽는 함수인 이유(시험이 `vi.stubEnv` 로 두 채널을 다 검증할 수
 *   있어야 한다)와 같은 이유다 — 만들 때 한 번만 보면 첫 호출 시점 값에 고정된다.
 * ⚠ 문서가 갈아엎인 경우(시험 격리 등)를 대비해 **연결 여부**를 확인하고 다시 만든다.
 */
export function getPortalOverlayRoot(): HTMLElement {
  if (!cached || !cached.isConnected) {
    cached = document.createElement('div');
    cached.setAttribute(PORTAL_OVERLAY_ROOT_ATTR, '');
    cached.style.display = 'contents';
    document.body.appendChild(cached);
  }
  cached.classList.toggle(PORTAL_EMBED_ANCHOR_CLASS, isPortalEmbedChannel());
  return cached;
}
