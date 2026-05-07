import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

import { UploadDropzone } from '../UploadDropzone';

function makeFile(name: string, size: number, type: string): File {
  const blob = new Blob([new Uint8Array(0)], { type });
  Object.defineProperty(blob, 'size', { value: size });
  const file = new File([blob], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

describe('UploadDropzone', () => {
  it('허용_확장자_mp4_선택시_onAccept_호출', () => {
    const onAccept = vi.fn();
    const onReject = vi.fn();
    render(<UploadDropzone onAccept={onAccept} onReject={onReject} />);

    const input = screen.getByLabelText(/영상 파일 선택/i) as HTMLInputElement;
    const file = makeFile('a.mp4', 1000, 'video/mp4');
    fireEvent.change(input, { target: { files: [file] } });

    expect(onAccept).toHaveBeenCalledWith(file);
    expect(onReject).not.toHaveBeenCalled();
  });

  it('허용_확장자_외_선택시_onReject_EXTENSION_NOT_ALLOWED', () => {
    const onAccept = vi.fn();
    const onReject = vi.fn();
    render(<UploadDropzone onAccept={onAccept} onReject={onReject} />);

    const input = screen.getByLabelText(/영상 파일 선택/i) as HTMLInputElement;
    const file = makeFile('a.exe', 1000, 'application/octet-stream');
    fireEvent.change(input, { target: { files: [file] } });

    expect(onAccept).not.toHaveBeenCalled();
    expect(onReject).toHaveBeenCalledWith('EXTENSION_NOT_ALLOWED');
  });

  it('5GB_초과_파일은_onReject_FILE_TOO_LARGE', () => {
    const onAccept = vi.fn();
    const onReject = vi.fn();
    render(<UploadDropzone onAccept={onAccept} onReject={onReject} />);

    const input = screen.getByLabelText(/영상 파일 선택/i) as HTMLInputElement;
    const tooBig = makeFile('a.mp4', 6 * 1024 * 1024 * 1024, 'video/mp4');
    fireEvent.change(input, { target: { files: [tooBig] } });

    expect(onAccept).not.toHaveBeenCalled();
    expect(onReject).toHaveBeenCalledWith('FILE_TOO_LARGE');
  });

  it('업로드_가이드에_5GB_및_허용_확장자_표기', () => {
    render(<UploadDropzone onAccept={() => {}} onReject={() => {}} />);
    expect(screen.getByText(/5GB/)).toBeInTheDocument();
    expect(screen.getByText(/mp4/i)).toBeInTheDocument();
  });
});
