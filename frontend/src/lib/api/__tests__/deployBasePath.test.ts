/**
 * 배포 접두 결합 — 판정기 단위 가드. [@design API-114] [@design SCREEN-006] [@design SEQ-036]
 *
 * ★ <b>루트 배포 조건만으로는 이 축을 검증할 수 없다</b> — `/api/v1` 은 접두가 빈 문자열이라
 *   결합해도 안 해도 결과가 같다. 그래서 접두가 있는 조건을 반드시 함께 둔다. 실제 결함이
 *   개발 내내 드러나지 않은 이유가 정확히 이것이다.
 */
import { afterEach, describe, expect, it } from 'vitest';

import { apiClient } from '@/lib/api/client';
import { deployBasePath, toDeployedApiUrl } from '@/lib/api/deployBasePath';

const ORIGINAL_BASE_URL = apiClient.defaults.baseURL;

afterEach(() => {
  apiClient.defaults.baseURL = ORIGINAL_BASE_URL;
});

describe('deployBasePath', () => {
  it('루트_배포는_접두가_없다', () => {
    expect(deployBasePath('/api/v1')).toBe('');
  });

  it('컨텍스트_경로_아래_배포는_그_경로가_접두다', () => {
    expect(deployBasePath('/label-studio/api/v1')).toBe('/label-studio');
  });

  it('절대_주소로_설정된_배포는_오리진까지가_접두다', () => {
    expect(deployBasePath('https://host.example/api/v1')).toBe('https://host.example');
  });

  it('끝의_슬래시는_판정에_영향을_주지_않는다', () => {
    expect(deployBasePath('/label-studio/api/v1/')).toBe('/label-studio');
  });

  it('★꼴이_다르면_접두를_지어내지_않는다_fail_safe', () => {
    // 접두를 알 수 없는 상태에서 무언가를 붙이면 지금 동작하던 배포까지 깨뜨린다.
    expect(deployBasePath('/gateway/v2')).toBe('');
    expect(deployBasePath(undefined)).toBe('');
  });

  it('판정의_기본_입력은_실제_요청이_쓰는_baseURL_이다', () => {
    // 다른 곳에서 값을 다시 읽으면 요청 주소와 재생 주소가 갈릴 수 있다.
    apiClient.defaults.baseURL = '/label-studio/api/v1';
    expect(deployBasePath()).toBe('/label-studio');
  });
});

describe('toDeployedApiUrl', () => {
  const SIGNED = '/api/v1/videos/26/stream?exp=1&u=2001&sig=abc';

  it('★배포_접두가_있으면_서버가_준_경로_앞에_붙는다', () => {
    apiClient.defaults.baseURL = '/label-studio/api/v1';
    expect(toDeployedApiUrl(SIGNED)).toBe(
      '/label-studio/api/v1/videos/26/stream?exp=1&u=2001&sig=abc',
    );
  });

  it('루트_배포에서는_서버가_준_값이_그대로다', () => {
    apiClient.defaults.baseURL = '/api/v1';
    expect(toDeployedApiUrl(SIGNED)).toBe(SIGNED);
  });

  it('이미_절대_주소로_온_값에는_접두를_두_번_붙이지_않는다', () => {
    apiClient.defaults.baseURL = '/label-studio/api/v1';
    expect(toDeployedApiUrl('https://cdn.example/v/26.mp4')).toBe(
      'https://cdn.example/v/26.mp4',
    );
    expect(toDeployedApiUrl('//cdn.example/v/26.mp4')).toBe('//cdn.example/v/26.mp4');
  });

  it('값이_없으면_빈_문자열로_정규화한다_빈_요청_방지', () => {
    apiClient.defaults.baseURL = '/label-studio/api/v1';
    expect(toDeployedApiUrl(undefined)).toBe('');
    expect(toDeployedApiUrl(null)).toBe('');
    expect(toDeployedApiUrl('')).toBe('');
  });
});
