import { HttpResponse } from 'msw';
import type { ApiResponse, Page } from '../../api/types';

export function ok<T>(data: T) {
  return HttpResponse.json({ success: true, data } satisfies ApiResponse<T>);
}

export function fail(code: string, message: string, status = 400) {
  return HttpResponse.json(
    { success: false, data: null, message, errorCode: code } as ApiResponse<null>,
    { status },
  );
}

export function paginate<T>(arr: T[], page: number, size: number): Page<T> {
  const totalElements = arr.length;
  const totalPages = Math.max(1, Math.ceil(totalElements / size));
  const safePageNum = Math.max(0, Math.min(page, totalPages - 1));
  const start = safePageNum * size;
  const content = arr.slice(start, start + size);
  return { content, totalElements, totalPages, number: safePageNum, size };
}

export function parsePageParams(url: URL): { page: number; size: number } {
  const page = parseInt(url.searchParams.get('page') ?? '0', 10);
  const size = parseInt(url.searchParams.get('size') ?? '20', 10);
  return { page: isNaN(page) ? 0 : page, size: isNaN(size) ? 20 : size };
}
