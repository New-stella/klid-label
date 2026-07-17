// Phase 5 — 포털 이미지 업로드 클라이언트 사전 검증(UX용) 단위 테스트.
// 서버(BE PortalUploadService)가 매직바이트/크기/개수 최종 검증의 진실원이며, 여기 검증은
// 사용자에게 즉시 안내(불필요한 업로드 왕복 회피)를 위한 것이다.
import { describe, expect, it } from 'vitest';

import {
  MAX_IMAGE_BYTES,
  MAX_IMAGE_COUNT,
  validateImageFiles,
} from '../validation';

function file(name: string, bytes: number, type = 'image/jpeg'): File {
  const blob = new Blob([new Uint8Array(Math.min(bytes, 8))], { type });
  const f = new File([blob], name, { type });
  // jsdom Blob size 는 실제 바이트만 반영하므로 size 를 강제 정의.
  Object.defineProperty(f, 'size', { value: bytes });
  return f;
}

describe('validateImageFiles', () => {
  it('허용_확장자_jpg_jpeg_png_는_통과', () => {
    const files = [file('a.jpg', 100), file('b.jpeg', 100), file('c.PNG', 100)];
    const res = validateImageFiles(files);
    expect(res.valid).toHaveLength(3);
    expect(res.errors).toHaveLength(0);
  });

  it('허용외_확장자_는_차단되고_서버정책_안내_메시지', () => {
    const res = validateImageFiles([file('evil.gif', 100), file('doc.pdf', 100)]);
    expect(res.valid).toHaveLength(0);
    expect(res.errors.length).toBeGreaterThan(0);
    // 서버 정책(허용 확장자)이 메시지에 명시되어야 한다.
    expect(res.errors.join(' ')).toMatch(/jpg|jpeg|png/i);
  });

  it('개당_20MB_초과_는_차단', () => {
    const res = validateImageFiles([file('big.jpg', MAX_IMAGE_BYTES + 1)]);
    expect(res.valid).toHaveLength(0);
    expect(res.errors.join(' ')).toMatch(/20|MB|크기/i);
  });

  it('요청당_50장_초과_는_초과분_차단', () => {
    const files = Array.from({ length: MAX_IMAGE_COUNT + 5 }, (_, i) => file(`f${i}.jpg`, 10));
    const res = validateImageFiles(files);
    expect(res.valid).toHaveLength(MAX_IMAGE_COUNT);
    expect(res.errors.join(' ')).toMatch(/50/);
  });

  it('빈_선택_은_valid_빈배열', () => {
    const res = validateImageFiles([]);
    expect(res.valid).toHaveLength(0);
    expect(res.errors).toHaveLength(0);
  });
});
