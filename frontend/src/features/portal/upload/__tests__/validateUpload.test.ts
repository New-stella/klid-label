import { describe, expect, it } from 'vitest';

import { MAX_UPLOAD_SIZE_BYTES, sanitizeFilename, validateUpload } from '../validateUpload';

function makeFile(name: string, size: number, type: string): File {
  // Vitest jsdom: File constructor 지원
  const blob = new Blob([new Uint8Array(0)], { type });
  // 실제 size는 Blob 길이 기반이므로 Object.defineProperty로 가짜 size 주입
  Object.defineProperty(blob, 'size', { value: size });
  const file = new File([blob], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

describe('validateUpload', () => {
  it('허용_확장자_mp4_mov_avi_외_업로드시_400_거부', () => {
    const exe = makeFile('virus.exe', 100, 'application/octet-stream');
    expect(validateUpload(exe)).toEqual({ valid: false, error: 'EXTENSION_NOT_ALLOWED' });

    const zip = makeFile('movie.zip', 100, 'application/zip');
    expect(validateUpload(zip)).toEqual({ valid: false, error: 'EXTENSION_NOT_ALLOWED' });
  });

  it('허용_확장자_mp4_mov_avi_대소문자_무시', () => {
    const upMp4 = makeFile('a.MP4', 100, 'video/mp4');
    expect(validateUpload(upMp4).valid).toBe(true);
    const upMov = makeFile('a.MOV', 100, 'video/quicktime');
    expect(validateUpload(upMov).valid).toBe(true);
    const upAvi = makeFile('a.AVI', 100, 'video/x-msvideo');
    expect(validateUpload(upAvi).valid).toBe(true);
  });

  it('5GB_초과_파일_업로드시_FE_차단', () => {
    const giant = makeFile('big.mp4', MAX_UPLOAD_SIZE_BYTES + 1, 'video/mp4');
    expect(validateUpload(giant)).toEqual({ valid: false, error: 'FILE_TOO_LARGE' });
  });

  it('5GB_정확히_경계값은_허용', () => {
    const exact = makeFile('exact.mp4', MAX_UPLOAD_SIZE_BYTES, 'video/mp4');
    expect(validateUpload(exact).valid).toBe(true);
  });

  it('빈_파일_거부', () => {
    const empty = makeFile('empty.mp4', 0, 'video/mp4');
    expect(validateUpload(empty)).toEqual({ valid: false, error: 'EMPTY_FILE' });
  });

  it('MIME_타입_video_외_거부', () => {
    const notVideo = makeFile('a.mp4', 100, 'application/octet-stream');
    expect(validateUpload(notVideo)).toEqual({ valid: false, error: 'MIME_NOT_ALLOWED' });
  });

  it('sanitizeFilename_path_traversal_차단', () => {
    expect(sanitizeFilename('../../../etc/passwd.mp4')).toBe('etcpasswd.mp4');
    expect(sanitizeFilename('a/b/c.mp4')).toBe('abc.mp4');
    expect(sanitizeFilename('..\\windows\\evil.mp4')).toBe('windowsevil.mp4');
  });

  it('sanitizeFilename_정상_파일명은_유지', () => {
    expect(sanitizeFilename('my-video_01.mp4')).toBe('my-video_01.mp4');
  });
});
