// 프레임 이미지 실패 힌트 — 라벨링·검수 두 화면이 공유하는 단일 판정 지점의 계약 가드.
//
// 이 함수가 갈리면 같은 실패가 화면마다 다른 문구로 보인다. 문구를 바꿀 때는 반드시 여기부터
// 바꾸고 두 화면의 회귀 테스트를 함께 돌린다.

import { describe, expect, it } from 'vitest';

import { ApiError } from '@/lib/api/errors';

import { resolveFrameImageErrorHint } from '../frameImageError';

describe('resolveFrameImageErrorHint', () => {
  it('412면_비식별_재처리_대기_힌트를_돌려준다', () => {
    expect(resolveFrameImageErrorHint(ApiError.fromStatus(412))).toBe(
      '비식별 재처리 대기 중인 영상입니다.',
    );
  });

  it('403이면_권한_힌트를_돌려준다', () => {
    expect(resolveFrameImageErrorHint(ApiError.fromStatus(403))).toBe(
      '이 프레임에 접근할 권한이 없습니다.',
    );
  });

  it('404면_파일_없음_힌트를_돌려준다', () => {
    expect(resolveFrameImageErrorHint(ApiError.fromStatus(404))).toBe(
      '이미지 파일을 찾을 수 없습니다.',
    );
  });

  it('미매핑_상태코드는_힌트를_만들지_않는다', () => {
    // 500 은 사용자가 할 수 있는 조치가 없어 정형 문구를 두지 않는다.
    expect(resolveFrameImageErrorHint(ApiError.fromStatus(500))).toBeUndefined();
  });

  it('null_undefined_상태코드없는_오류에도_안전하다', () => {
    expect(resolveFrameImageErrorHint(null)).toBeUndefined();
    expect(resolveFrameImageErrorHint(undefined)).toBeUndefined();
    expect(resolveFrameImageErrorHint(new Error('boom'))).toBeUndefined();
  });

  it('서버_메시지를_그대로_노출하지_않는다', () => {
    // CWE-209 — 내부 경로가 섞인 서버 메시지가 힌트로 새어 나가면 안 된다.
    const leaky = new ApiError({
      errorCode: 'NOT_FOUND',
      status: 404,
      message: '/nas-storage/frames/raw/54/frame-0.jpg not found',
    });
    const hint = resolveFrameImageErrorHint(leaky);
    expect(hint).toBe('이미지 파일을 찾을 수 없습니다.');
    expect(hint).not.toContain('/nas-storage');
  });
});
