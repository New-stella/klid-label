// 포털 TUS 업로드 사전 검증 (V1.5 보안 — Critical)
// - 확장자 allowlist (mp4/mov/avi)
// - 5GB 한도 (FE 즉시 차단 + BE 재검증)
// - MIME 검증 (video/mp4, video/quicktime, video/x-msvideo)
// - filename sanitize (path traversal 차단)

import type { UploadValidationResult } from '../types';

/** 5GB = 5 * 1024^3 bytes — BE TusUploadController와 동일 기준 */
export const MAX_UPLOAD_SIZE_BYTES = 5 * 1024 * 1024 * 1024;

/** 허용 확장자 — 대소문자 무시 */
export const ALLOWED_EXTENSIONS = ['mp4', 'mov', 'avi'] as const;

/** 허용 MIME 타입 (video/* 중 specific) */
export const ALLOWED_MIME_TYPES = ['video/mp4', 'video/quicktime', 'video/x-msvideo'] as const;

function getExtension(filename: string): string {
  const idx = filename.lastIndexOf('.');
  if (idx < 0) return '';
  return filename.slice(idx + 1).toLowerCase();
}

/**
 * 업로드 파일 사전 검증. valid=false인 경우 error 코드 반환.
 *
 * 보안: BE에서 동일 정책으로 재검증해야 한다 (FE 우회 방지).
 */
export function validateUpload(file: File): UploadValidationResult {
  if (file.size === 0) {
    return { valid: false, error: 'EMPTY_FILE' };
  }
  if (file.size > MAX_UPLOAD_SIZE_BYTES) {
    return { valid: false, error: 'FILE_TOO_LARGE' };
  }
  const ext = getExtension(file.name);
  if (!ALLOWED_EXTENSIONS.includes(ext as (typeof ALLOWED_EXTENSIONS)[number])) {
    return { valid: false, error: 'EXTENSION_NOT_ALLOWED' };
  }
  if (!ALLOWED_MIME_TYPES.includes(file.type as (typeof ALLOWED_MIME_TYPES)[number])) {
    return { valid: false, error: 'MIME_NOT_ALLOWED' };
  }
  return { valid: true };
}

/**
 * 파일명 sanitize. path 구분자(`/`, `\`) 및 `..` 시퀀스 제거.
 *
 * 보안 (CWE-22 Path Manipulation): TUS upload-metadata에 사용자 파일명을 그대로
 * 실으면 BE 저장 경로 조작 가능 — FE에서 1차 차단.
 */
export function sanitizeFilename(name: string): string {
  return name
    .replace(/\.\./g, '')
    .replace(/[/\\]/g, '')
    .trim();
}
