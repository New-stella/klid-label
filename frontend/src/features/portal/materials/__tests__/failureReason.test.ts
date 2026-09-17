/**
 * 회귀 가드 — 실패 사유 표기.
 *
 * ★ <b>폴백을 실제로 타는 픽스처를 함께 둔다.</b> 배선만 해 두고 알려진 값만 흘리면 그 가지는 한
 *   번도 실행되지 않고, 폴백을 지워도 전건 초록이라 「폴백을 뒀다」는 주장과 증명이 갈린다.
 *   (`Record` 조회는 모르는 키에 `undefined` 를 주고 React 는 그것을 조용히 무시한다 — 오류도
 *   경고도 없이 사유 칸만 빈칸이 된다.)
 *
 * ★ 타입이 이 축을 지켜 주지 않는다 — 값의 원천이 <b>서버 응답</b>이라 런타임에는 무엇이든 온다.
 *   모르는 값을 넣으려면 캐스팅이 필요한데, <b>그 캐스팅이 필요하다는 사실 자체가</b> 타입이 여기를
 *   못 지킨다는 증거다.
 *
 * @design INT-014
 */
import { describe, expect, it } from 'vitest';

import { materialsFailureNotice } from '../failureReason';
import { PortalMaterialsFailureReason } from '../types';

describe('소재 조달 실패 사유 표기', () => {
  it('알려진_사유_전부에_문구가_있다_빈칸이_없다', () => {
    for (const reason of Object.values(PortalMaterialsFailureReason)) {
      const notice = materialsFailureNotice(reason);
      expect(notice.title.trim(), `${reason} 의 제목이 비었다`).not.toBe('');
      expect(notice.description.trim(), `${reason} 의 설명이 비었다`).not.toBe('');
    }
  });

  it('사유마다_문구가_다르다_한_문구로_뭉개는_변이를_잡는다', () => {
    const titles = Object.values(PortalMaterialsFailureReason).map(
      (r) => materialsFailureNotice(r).title,
    );
    expect(new Set(titles).size).toBe(titles.length);
  });

  it('다시_물어도_같은_사유와_일시_장애를_가른다', () => {
    // 설정 미비·포털 거부는 되풀이해야 같은 답이 온다.
    expect(materialsFailureNotice(PortalMaterialsFailureReason.NOT_CONFIGURED).retryWorthwhile).toBe(
      false,
    );
    expect(materialsFailureNotice(PortalMaterialsFailureReason.FETCH_REJECTED).retryWorthwhile).toBe(
      false,
    );
    // 조회 실패·입출력 오류는 잠시 뒤 달라질 수 있다.
    expect(materialsFailureNotice(PortalMaterialsFailureReason.FETCH_FAILED).retryWorthwhile).toBe(
      true,
    );
    expect(materialsFailureNotice(PortalMaterialsFailureReason.IO_ERROR).retryWorthwhile).toBe(true);
  });

  it('★우리가_모르는_사유가_와도_빈칸이_되지_않는다', () => {
    const unknown = 'QUOTA_EXCEEDED' as unknown as PortalMaterialsFailureReason;

    const notice = materialsFailureNotice(unknown);

    expect(notice.title.trim()).not.toBe('');
    expect(notice.description.trim()).not.toBe('');
    // 모르는 값을 확정 실패로 단정하면 실제 일시 장애에서 회복 경로를 막는다.
    expect(notice.retryWorthwhile).toBe(true);
    // 사유 코드를 그대로 화면에 찍지 않는다 — 이용자에게 뜻이 없는 문자열이다.
    expect(notice.title).not.toContain('QUOTA_EXCEEDED');
    expect(notice.description).not.toContain('QUOTA_EXCEEDED');
  });

  it('사유가_비어_와도_빈칸이_되지_않는다', () => {
    for (const empty of [null, undefined]) {
      const notice = materialsFailureNotice(empty);
      expect(notice.title.trim()).not.toBe('');
      expect(notice.description.trim()).not.toBe('');
    }
  });
});
