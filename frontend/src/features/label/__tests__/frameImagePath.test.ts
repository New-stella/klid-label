/**
 * 프레임 이미지 창구 경로 — **판정 단일 지점**의 순수 가드. @design SCREEN-029
 *
 * ★왜 순수 단언을 따로 두나 — 렌더 시험은 «그 경로로 요청이 나갔다» 를 보지만, 판정기가 인자를
 *  무시하도록 바뀌어도 픽스처가 한 축만 쓰면 드러나지 않는다. **다른 인자 → 다른 경로**를 값으로
 *  고정해야 축을 지우는 변이가 죽는다.
 *
 * ★업로드 축이 포털 축보다 **앞**이라는 순서도 함께 고정한다 — 뒤집으면 업로드 자산이
 *  데이터마트 창구로 나가고, 그 실패는 «썸네일만 깨지는» 형태로 늦게 드러난다.
 */
import { describe, expect, it } from 'vitest';

import { resolveFrameImagePath } from '../hooks/useImageBlob';

describe('프레임 이미지 창구 경로', () => {
  it('내부_채널은_내부_창구다', () => {
    expect(resolveFrameImagePath(7)).toBe('/frames/7/image');
    expect(resolveFrameImagePath(7, {})).toBe('/frames/7/image');
    expect(resolveFrameImagePath(7, { portalMode: false, uploadSource: false })).toBe(
      '/frames/7/image',
    );
  });

  it('포털_데이터마트는_프레임_축_창구다', () => {
    expect(resolveFrameImagePath(7, { portalMode: true })).toBe('/portal/frames/7/image');
  });

  it('★업로드_출처는_자산_축_창구다', () => {
    expect(resolveFrameImagePath(7, { portalMode: true, uploadSource: true })).toBe(
      '/portal/uploads/frames/7/image',
    );
  });

  it('★업로드_표기가_포털_표기보다_우선한다_순서가_뒤집히면_데이터마트로_샌다', () => {
    // 두 표기는 실제로 함께 선다(업로드는 포털 채널 안에서만 성립한다).
    const both = resolveFrameImagePath(7, { portalMode: true, uploadSource: true });
    const portalOnly = resolveFrameImagePath(7, { portalMode: true });
    expect(both).not.toBe(portalOnly);
    expect(both).toContain('/uploads/');
  });

  it('★세_갈래가_서로_다른_경로다_한_축을_무시하면_같아진다', () => {
    const paths = new Set([
      resolveFrameImagePath(7),
      resolveFrameImagePath(7, { portalMode: true }),
      resolveFrameImagePath(7, { portalMode: true, uploadSource: true }),
    ]);
    expect(paths.size).toBe(3);
  });

  it('프레임_식별자가_다르면_경로도_다르다', () => {
    expect(resolveFrameImagePath(7, { portalMode: true, uploadSource: true })).not.toBe(
      resolveFrameImagePath(8, { portalMode: true, uploadSource: true }),
    );
  });
});
