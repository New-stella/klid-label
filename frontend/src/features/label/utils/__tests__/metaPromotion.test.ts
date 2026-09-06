// 자동 계산값 승격 방지 판정 — 진리표 + <b>사본이 다시 생기지 않는지</b>.
//
// @design API-235
// @design AC-1068
import { readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { isUserDeterminedMetaValue } from '../metaPromotion';

describe('isUserDeterminedMetaValue', () => {
  it('손대지_않은_자동_계산값은_보내지_않는다', () => {
    expect(isUserDeterminedMetaValue('N', 'N', 'DERIVED')).toBe(false);
  });

  it('사용자가_값을_바꿨으면_보낸다', () => {
    expect(isUserDeterminedMetaValue('Y', 'N', 'DERIVED')).toBe(true);
  });

  it('원본이_이미_사람이_고른_값이면_손대지_않아도_보낸다', () => {
    expect(isUserDeterminedMetaValue('맑음', '맑음', 'MANUAL')).toBe(true);
  });

  it('출처_구분이_없는_축의_저장값은_손대지_않으면_보내지_않는다', () => {
    // 이 축에는 자동 계산 프리필이 없지만, 무변경 저장이 「내 작업물」을 만들지 않게 하려면
    // 손대지 않은 값을 보내지 않는 것이 같은 결론이다.
    expect(isUserDeterminedMetaValue('서술', '서술', 'STORED')).toBe(false);
    expect(isUserDeterminedMetaValue('고친 서술', '서술', 'STORED')).toBe(true);
  });

  it('원본에_값이_없던_자리에_새로_채우면_보낸다', () => {
    expect(isUserDeterminedMetaValue('새 값', '', 'NONE')).toBe(true);
    expect(isUserDeterminedMetaValue('', '', 'NONE')).toBe(false);
  });

  it('출처를_내려주지_않는_창구는_손댔는가_한_축으로_판정한다', () => {
    expect(isUserDeterminedMetaValue(true, true, null)).toBe(false);
    expect(isUserDeterminedMetaValue(true, false, null)).toBe(true);
  });
});

/*
 * ★사본이 다시 생기는 것을 막는다.
 *
 * 이 규율은 원래 세 화면에 <b>각자</b> 있었고(두 파일이 「같은 규율」임을 주석으로만 밝히고 있었다)
 * 포털 채널이 네 번째 사본이 될 참이었다. 사본이 늘면 한쪽만 고쳐도 화면은 그럴듯하게 보인다 —
 * 진리표 시험만으로는 그 회귀가 잡히지 않는다(새 사본은 자기 식을 쓰므로 이 함수를 통과하지 않는다).
 */
const SRC = path.resolve(__dirname, '../../../..');
const CONSUMERS = [
  'features/label/components/EnvironmentMetaPanel.tsx',
  'features/label/components/VideoPrivacyMetaPanel.tsx',
  'features/label/components/FramePrivacyMetaPanel.tsx',
  'features/portal/work/metaSavePayload.ts',
];

describe('승격 방지 판정의 단일 소유', () => {
  it.each(CONSUMERS)('%s 는 판정을 스스로 구현하지 않고 공용 함수를 부른다', (relative) => {
    const source = readFileSync(path.join(SRC, relative), 'utf-8');
    expect(source).toContain('isUserDeterminedMetaValue');
    // 판정식을 그대로 베껴 쓴 자리가 없어야 한다(주석에서 규율을 설명하는 것은 문자열이 다르다).
    expect(source).not.toMatch(/current\s*!==\s*original\s*\|\|\s*source\s*===\s*'MANUAL'/);
  });
});
