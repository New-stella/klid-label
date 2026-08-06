// 회귀(2026-08-06) — SAM2 Track 응답은 라벨명만 준다. 마스터 PK 를 복구하지 못하면
// labelId=null 로 저장돼 재조회 시 마스터 조인이 끊긴다(색·라벨명·속성 소실).

import { describe, expect, it } from 'vitest';

import type { LabelMaster } from '../../api/labelMaster';
import { resolveLabelIdByName } from '../labelMasterLookup';

function master(over: Partial<LabelMaster> & Pick<LabelMaster, 'labelId' | 'name'>): LabelMaster {
  return {
    color: '#EF4444',
    type: 'BBOX',
    sortNo: 1,
    useYn: 'Y',
    dtctTypeCd: null,
    ...over,
  };
}

const masters: LabelMaster[] = [
  master({ labelId: 1, name: '사람' }),
  master({ labelId: 2, name: '차량', color: '#3B82F6' }),
  master({ labelId: 3, name: '폐지된라벨', useYn: 'N' }),
];

describe('resolveLabelIdByName', () => {
  it('활성_마스터에_이름이_정확히_일치하면_labelId_반환', () => {
    expect(resolveLabelIdByName(masters, '차량')).toBe(2);
  });

  it('비활성(useYn=N)_마스터는_매칭하지_않는다', () => {
    expect(resolveLabelIdByName(masters, '폐지된라벨')).toBeNull();
  });

  it('미등록_이름은_null_추측하지_않는다', () => {
    expect(resolveLabelIdByName(masters, 'INTRUSION')).toBeNull();
  });

  it('동명_활성_마스터가_2건_이상이면_null_잘못된_분류로_저장되지_않는다', () => {
    const ambiguous: LabelMaster[] = [
      master({ labelId: 10, name: '사람' }),
      master({ labelId: 11, name: '사람' }),
    ];
    expect(resolveLabelIdByName(ambiguous, '사람')).toBeNull();
  });

  it('마스터_미로딩_또는_빈_이름이면_null', () => {
    expect(resolveLabelIdByName(undefined, '사람')).toBeNull();
    expect(resolveLabelIdByName([], '사람')).toBeNull();
    expect(resolveLabelIdByName(masters, '')).toBeNull();
    expect(resolveLabelIdByName(masters, null)).toBeNull();
  });
});
