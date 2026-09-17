// 「도움말 숨기기」 선택의 브라우저 기억. [@design UI-107]
//
// 도움말은 <b>기본이 표시</b>다 — 처음 쓰는 사람이 무엇을 적는 칸인지 알아야 하기 때문이다.
// 숨긴 선택만 기억하며, 저장소를 읽지 못하면 기본값(표시)으로 돌아간다.

import { useCallback, useState } from 'react';

export const ANNOTATION_HELP_STORAGE_KEY = 'klid.annotationWindow.help';

/** 저장소에 적히는 값 — 「숨김」만 기록한다(기본값은 적지 않는다). */
const HIDDEN_VALUE = 'hidden';

export function readHelpVisible(): boolean {
  try {
    if (typeof localStorage === 'undefined') return true;
    return localStorage.getItem(ANNOTATION_HELP_STORAGE_KEY) !== HIDDEN_VALUE;
  } catch {
    return true;
  }
}

export function writeHelpVisible(visible: boolean): void {
  try {
    if (typeof localStorage === 'undefined') return;
    if (visible) localStorage.removeItem(ANNOTATION_HELP_STORAGE_KEY);
    else localStorage.setItem(ANNOTATION_HELP_STORAGE_KEY, HIDDEN_VALUE);
  } catch {
    /* 기억하지 못할 뿐 이번 화면에서는 그대로 동작한다. */
  }
}

/**
 * 도움말 표시 여부 — 창이 열릴 때 기억된 선택으로 시작한다.
 *
 * 초기값을 <b>지연 초기화</b>로 읽는 이유: 매 렌더마다 저장소를 읽으면 창 안에서 글자를 한 자
 * 칠 때마다 동기 I/O 가 일어난다.
 */
export function useAnnotationHelpVisible(): [boolean, () => void] {
  const [visible, setVisible] = useState<boolean>(() => readHelpVisible());
  const toggle = useCallback(() => {
    setVisible((prev) => {
      const next = !prev;
      writeHelpVisible(next);
      return next;
    });
  }, []);
  return [visible, toggle];
}
