package kr.co.cudo.authoring.portal.tus;

/**
 * Phase 11 — TUS PATCH 청크 표현.
 *
 * @param offset 청크가 기록될 시작 오프셋
 * @param data   청크 바이트 (Content-Length 와 길이 일치 검증 필요)
 */
public record TusChunk(long offset, byte[] data) {
    public int length() {
        return data == null ? 0 : data.length;
    }
}
