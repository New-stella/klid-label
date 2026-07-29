import type { ApiResponse } from './types';

export class ApiError extends Error {
  readonly errorCode: string;
  readonly status: number;
  readonly userMessage: string;

  constructor(params: { errorCode: string; status: number; message: string; userMessage?: string }) {
    super(params.message);
    this.name = 'ApiError';
    this.errorCode = params.errorCode;
    this.status = params.status;
    this.userMessage = params.userMessage ?? params.message;
  }

  static fromBody(body: ApiResponse<unknown>, status: number): ApiError {
    const code = body.errorCode ?? 'INTERNAL_ERROR';
    const msg = body.message ?? '요청을 처리할 수 없습니다.';
    return new ApiError({ errorCode: code, status, message: msg, userMessage: msg });
  }

  static fromStatus(status: number, message?: string): ApiError {
    const code = inferErrorCode(status);
    const msg = message ?? defaultMessage(status);
    return new ApiError({ errorCode: code, status, message: msg, userMessage: msg });
  }
}

function inferErrorCode(status: number): string {
  if (status === 400) return 'INVALID_INPUT';
  if (status === 401) return 'UNAUTHORIZED';
  if (status === 403) return 'FORBIDDEN';
  if (status === 404) return 'NOT_FOUND';
  if (status === 409) return 'CONFLICT';
  // 412 = 비식별 재처리 대기 등 "전제 조건 미충족". 미매핑이면 INTERNAL_ERROR 로 떨어져
  // 서버가 준 안내문 대신 일반 오류 문구가 노출된다(사용자가 이유를 알 수 없음).
  if (status === 412) return 'PRECONDITION_FAILED';
  if (status === 413) return 'PAYLOAD_TOO_LARGE';
  if (status === 502) return 'EXTERNAL_API_ERROR';
  return 'INTERNAL_ERROR';
}

function defaultMessage(status: number): string {
  if (status === 401) return '인증이 필요합니다.';
  if (status === 403) return '권한이 없습니다.';
  if (status === 404) return '리소스를 찾을 수 없습니다.';
  if (status === 412) return '현재 상태에서는 수행할 수 없는 요청입니다.';
  return '요청을 처리할 수 없습니다.';
}
