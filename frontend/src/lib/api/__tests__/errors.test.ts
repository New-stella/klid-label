import { describe, expect, it } from 'vitest';

import { ApiError } from '../errors';
import { resolveApiMessage } from '../resolveApiMessage';

/**
 * S7 (DEV_FIX-G) — 412(PRECONDITION_FAILED) 매핑 회귀 방어.
 *
 * BE 는 비식별 누락 신고 구간에서 AI 추론·라벨 조회·이미지 서빙을 412 + 안내문으로 끊는다.
 * FE 가 412 를 미매핑하면 errorCode 가 INTERNAL_ERROR 로 떨어지고 안내문도 일반 오류 문구로
 * 대체되어, 사용자는 "왜 막혔는지"를 알 수 없다.
 */
describe('ApiError 상태코드 매핑', () => {
  it('412는_PRECONDITION_FAILED로_매핑된다', () => {
    const err = ApiError.fromStatus(412);
    expect(err.errorCode).toBe('PRECONDITION_FAILED');
    expect(err.status).toBe(412);
  });

  it('412_기본_메시지는_일반_오류_문구가_아니다', () => {
    expect(ApiError.fromStatus(412).userMessage).not.toBe('요청을 처리할 수 없습니다.');
  });

  it('기존_상태코드_매핑은_그대로다', () => {
    expect(ApiError.fromStatus(400).errorCode).toBe('INVALID_INPUT');
    expect(ApiError.fromStatus(409).errorCode).toBe('CONFLICT');
    expect(ApiError.fromStatus(413).errorCode).toBe('PAYLOAD_TOO_LARGE');
    expect(ApiError.fromStatus(500).errorCode).toBe('INTERNAL_ERROR');
  });
});

describe('resolveApiMessage', () => {
  const build = (status: number, message: string) =>
    new ApiError({ errorCode: 'X', status, message, userMessage: message });

  it('412_서버_안내문을_그대로_노출한다', () => {
    const err = build(412, '비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다.');
    expect(resolveApiMessage(err, '실패했습니다.')).toBe(
      '비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다.',
    );
  });

  it('400_409는_기존대로_노출한다', () => {
    expect(resolveApiMessage(build(400, '입력값 오류'), 'fb')).toBe('입력값 오류');
    expect(resolveApiMessage(build(409, '중복입니다'), 'fb')).toBe('중복입니다');
  });

  it('5xx_403은_내부정보_노출_방지로_fallback을_쓴다', () => {
    expect(resolveApiMessage(build(500, '내부 스택 정보'), 'fb')).toBe('fb');
    expect(resolveApiMessage(build(403, '권한 상세'), 'fb')).toBe('fb');
    expect(resolveApiMessage(new Error('boom'), 'fb')).toBe('fb');
  });
});
