// 「내 작업」 목록의 **이어서 작업 진입 주소** 조립 — 순수 단언. @design SCREEN-028
//
// ★ 왜 렌더 시험과 별개로 두는가 — 렌더 시험은 화면과 이 팩토리를 **함께** 통과한다. 팩토리가
//   인자를 무시하도록 바뀌어도 화면이 그 결과를 그대로 쓰므로 「어느 인자에서 무엇이 갈리는가」는
//   렌더 단언만으로 고정되지 않는다. 「다른 인자 → 다른 주소」를 여기서 순수하게 못박는다.

import { describe, expect, it } from 'vitest';

import {
  buildPortalDatamartLabelPath,
  buildPortalUploadLabelPath,
  buildPortalWorkLabelPath,
} from '../labelingEntry';

describe('buildPortalWorkLabelPath — 출처가 진입 자리를 가른다', () => {
  it('데이터마트는_프레임_식별자로_열고_출처_표기가_붙지_않는다', () => {
    expect(buildPortalWorkLabelPath('DATAMART', 10, 100)).toBe('/portal/label/100');
    expect(buildPortalWorkLabelPath('DATAMART', 10, 100)).not.toContain('source=');
  });

  it('업로드는_자산_식별자로_열고_프레임은_표기가_나른다', () => {
    // 업로드 축에서 :id 는 프레임이 아니라 자산이다 — 목록에서 행을 누르는 시점에 화면이
    // 프레임 축 창구로 갈 근거가 없다.
    expect(buildPortalWorkLabelPath('PORTAL_UPLOAD', 77, 900)).toBe(
      buildPortalUploadLabelPath(77, 900),
    );
    expect(buildPortalWorkLabelPath('PORTAL_UPLOAD', 77, 900)).toContain('/portal/label/77?');
  });

  it('★두_출처는_같은_인자에서_서로_다른_주소를_만든다', () => {
    // 한쪽 규약으로 합치면 다른 축이 있지도 않은 자원을 조회한다.
    expect(buildPortalWorkLabelPath('DATAMART', 7, 9)).not.toBe(
      buildPortalWorkLabelPath('PORTAL_UPLOAD', 7, 9),
    );
  });

  it('★인자가_다르면_주소가_다르다_팩토리가_인자를_무시하지_않는다', () => {
    expect(buildPortalWorkLabelPath('DATAMART', 10, 100)).not.toBe(
      buildPortalWorkLabelPath('DATAMART', 10, 101),
    );
    expect(buildPortalWorkLabelPath('PORTAL_UPLOAD', 10, 100)).not.toBe(
      buildPortalWorkLabelPath('PORTAL_UPLOAD', 11, 100),
    );
    expect(buildPortalWorkLabelPath('PORTAL_UPLOAD', 10, 100)).not.toBe(
      buildPortalWorkLabelPath('PORTAL_UPLOAD', 10, 101),
    );
  });

  it('★진입_대상_프레임이_없으면_null_이다_두_축_모두', () => {
    // 「첫 프레임으로 대신 연다」로 때우지 않는다 — 서버가 null 을 준 것은 여는 것 자체가
    // 성립하지 않는다는 뜻이다. 문자열을 돌려주면 `/portal/label/null` 이 나간다.
    expect(buildPortalWorkLabelPath('DATAMART', 10, null)).toBeNull();
    expect(buildPortalWorkLabelPath('PORTAL_UPLOAD', 10, null)).toBeNull();
  });

  it('데이터마트_경로_조립은_한_곳이_소유한다', () => {
    expect(buildPortalDatamartLabelPath(482)).toBe('/portal/label/482');
  });
});
