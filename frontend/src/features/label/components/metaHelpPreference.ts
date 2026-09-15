// 메타 탭 「도움말」 표시 선택의 브라우저 기억. [@design SCREEN-005] [@design SCREEN-019]
//
// <h3>★창의 도움말과 기본값이 서로 반대다 — 통일하지 말 것</h3>
// 창(`annotationHelpPreference`)은 <b>기본이 「보임」</b>이고 이 메타 탭은 <b>기본이 「감춤」</b>이다.
// 같은 이름의 기능이라 「일관성」을 이유로 한쪽에 맞추기 쉬운데, 그러면 둘 중 하나가 자기 자리에서
// 틀린 동작을 한다. 근거는 <b>자리의 폭</b>이다:
// <ul>
//   <li>창은 1440×810 이라 설명이 늘 보여도 값을 밀어내지 않는다 — 처음 쓰는 사람에게 유리하다.</li>
//   <li>메타 탭은 폭 약 360px 세로 한 줄이라 설명 한 문장이 두세 줄로 접혀 값을 아래로 밀어낸다.
//       사용자가 2026-09-15 에 바로 그 점을 지적했다 — 「해당 탭에 보여야 할 데이터가 많은데
//       자리를 너무 차지하는 거 같다」. 그래서 <b>감춤이 기본</b>이고 필요할 때만 펼친다.</li>
// </ul>
//
// <h3>★저장 키도 별개다</h3>
// 한 키를 공유하면 창에서 도움말을 끈 사람이 메타 탭에서도 끈 것이 되고 그 반대도 된다. 두 자리의
// 기본값이 반대라 그 전이는 사용자가 의도한 적 없는 상태를 만든다.

import { useCallback, useState } from 'react';

export const META_HELP_STORAGE_KEY = 'klid.metaTab.help';

/** 저장소에 적히는 값 — 기본값(감춤)은 적지 않고 「보임」만 기록한다. */
const SHOWN_VALUE = 'shown';

export function readMetaHelpVisible(): boolean {
  try {
    if (typeof localStorage === 'undefined') return false;
    return localStorage.getItem(META_HELP_STORAGE_KEY) === SHOWN_VALUE;
  } catch {
    // 저장소를 읽지 못하면 기본값(감춤)이다 — 못 읽었다고 설명이 쏟아지면 안 된다.
    return false;
  }
}

export function writeMetaHelpVisible(visible: boolean): void {
  try {
    if (typeof localStorage === 'undefined') return;
    if (visible) localStorage.setItem(META_HELP_STORAGE_KEY, SHOWN_VALUE);
    else localStorage.removeItem(META_HELP_STORAGE_KEY);
  } catch {
    /* 기억하지 못할 뿐 이번 화면에서는 그대로 동작한다. */
  }
}

/**
 * 메타 탭 도움말 표시 여부 — 탭이 처음 그려질 때 기억된 선택으로 시작한다.
 *
 * 초기값을 <b>지연 초기화</b>로 읽는 이유는 창 쪽과 같다 — 매 렌더마다 저장소를 읽으면 값 하나를
 * 고칠 때마다 동기 I/O 가 일어난다.
 */
export function useMetaHelpPreference(): [boolean, () => void] {
  const [visible, setVisible] = useState<boolean>(() => readMetaHelpVisible());
  const toggle = useCallback(() => {
    setVisible((prev) => {
      const next = !prev;
      writeMetaHelpVisible(next);
      return next;
    });
  }, []);
  return [visible, toggle];
}
