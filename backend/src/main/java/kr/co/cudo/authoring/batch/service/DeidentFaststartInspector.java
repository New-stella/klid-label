package kr.co.cudo.authoring.batch.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 비식별 산출물의 <b>재생 인덱스(moov) 위치</b> 판정기 — 최상위 박스 헤더만 순차로 읽는다.
 *
 * <h3>판정 규칙</h3>
 * <ul>
 *   <li>첫 박스가 {@code ftyp} 가 아니면 판정하지 않는다(ISO-BMFF 로 확인되지 않은 파일은 건드리지 않는다).</li>
 *   <li>{@code moov} 가 {@code mdat} 보다 먼저 나오면 {@link Layout#FASTSTART} — 아무것도 하지 않는다(멱등).</li>
 *   <li>{@code mdat} 뒤에 {@code moov} 가 나오면 {@link Layout#MOOV_AT_END} — 재배치 대상.</li>
 *   <li>박스 크기가 비정상(8 미만·파일 끝 초과)이거나 {@code moov} 를 찾지 못하면
 *       {@link Layout#UNDETERMINED} — 건드리지 않는다.</li>
 * </ul>
 *
 * <p>파일 전체를 읽지 않는다 — 박스마다 헤더(8바이트, 64비트 largesize 면 16바이트)만 읽고 크기만큼
 * 건너뛴다. 박스 수에는 상한을 둔다(비정상 파일이 판정을 붙잡지 않게 — CWE-400).
 *
 * <p>심볼릭 링크는 따라가지 않는다 — 우리가 교체할 산출물은 일반 파일이어야 한다.
 *
 * @design ADR-072
 * @design AC-1063
 * @design AC-1064
 */
public final class DeidentFaststartInspector {

    /** 재생 인덱스 위치 판정 결과. */
    public enum Layout {
        /** 재생 인덱스가 미디어 데이터보다 앞에 있다 — 재배치 불필요. */
        FASTSTART,
        /** 재생 인덱스가 미디어 데이터 뒤에 있다 — 재배치 대상. */
        MOOV_AT_END,
        /** 판정할 수 없다(ISO-BMFF 아님·박스 손상·잘린 파일·인덱스 부재) — 건드리지 않는다. */
        UNDETERMINED
    }

    /** 순회할 최상위 박스 수 상한 — 정상 mp4 는 한 자릿수다. */
    static final int MAX_TOP_LEVEL_BOXES = 4_096;

    private static final int BASIC_HEADER = 8;
    private static final int LARGE_HEADER = 16;

    private DeidentFaststartInspector() {
    }

    /**
     * 파일의 재생 인덱스 위치를 판정한다. 입출력 오류·링크·비정규 파일은 {@link Layout#UNDETERMINED}.
     */
    public static Layout inspect(Path file) {
        if (file == null) {
            return Layout.UNDETERMINED;
        }
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Layout.UNDETERMINED;
            }
            try (SeekableByteChannel channel = Files.newByteChannel(file,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                return inspect(channel);
            }
        } catch (IOException | RuntimeException e) {
            return Layout.UNDETERMINED;
        }
    }

    /** 채널에서 판정한다(단위 시험 seam). 채널 위치는 바꾼다. */
    static Layout inspect(SeekableByteChannel channel) throws IOException {
        long size = channel.size();
        long pos = 0;
        boolean first = true;
        boolean sawMdat = false;
        int boxes = 0;
        ByteBuffer header = ByteBuffer.allocate(LARGE_HEADER);
        while (pos < size) {
            if (++boxes > MAX_TOP_LEVEL_BOXES) {
                return Layout.UNDETERMINED;
            }
            if (size - pos < BASIC_HEADER) {
                return Layout.UNDETERMINED;
            }
            if (!readFully(channel, pos, header, BASIC_HEADER)) {
                return Layout.UNDETERMINED;
            }
            long boxSize = Integer.toUnsignedLong(header.getInt(0));
            String type = boxType(header);
            if (type == null) {
                return Layout.UNDETERMINED;
            }
            if (boxSize == 1) {
                if (size - pos < LARGE_HEADER || !readFully(channel, pos, header, LARGE_HEADER)) {
                    return Layout.UNDETERMINED;
                }
                boxSize = header.getLong(BASIC_HEADER);
                if (boxSize < LARGE_HEADER) {
                    return Layout.UNDETERMINED;
                }
            } else if (boxSize == 0) {
                // 크기 0 = 파일 끝까지 이어지는 마지막 박스.
                boxSize = size - pos;
            } else if (boxSize < BASIC_HEADER) {
                return Layout.UNDETERMINED;
            }
            if (boxSize > size - pos) {
                // 잘린 파일 — 박스가 파일 끝을 넘는다.
                return Layout.UNDETERMINED;
            }
            if (first) {
                if (!"ftyp".equals(type)) {
                    return Layout.UNDETERMINED;
                }
                first = false;
            }
            if ("moov".equals(type)) {
                return sawMdat ? Layout.MOOV_AT_END : Layout.FASTSTART;
            }
            if ("mdat".equals(type)) {
                sawMdat = true;
            }
            pos += boxSize;
        }
        return Layout.UNDETERMINED;
    }

    private static boolean readFully(SeekableByteChannel channel, long pos, ByteBuffer buf, int len)
            throws IOException {
        buf.clear();
        buf.limit(len);
        channel.position(pos);
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) {
                return false;
            }
        }
        return true;
    }

    /** 박스 타입 4바이트 — 출력 가능한 ASCII 가 아니면 null(손상 판정). */
    private static String boxType(ByteBuffer header) {
        char[] chars = new char[4];
        for (int i = 0; i < 4; i++) {
            int b = header.get(4 + i) & 0xFF;
            if (b < 0x20 || b > 0x7E) {
                return null;
            }
            chars[i] = (char) b;
        }
        return new String(chars);
    }
}
