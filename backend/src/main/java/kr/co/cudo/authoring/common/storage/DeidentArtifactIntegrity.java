package kr.co.cudo.authoring.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 비식별 산출 <b>영상 파일</b>의 무결성 판정 단일 지점 (B-ISSUE-01).
 *
 * <p><b>왜 존재하나</b>: 기존 판정은 "존재 + 0바이트 초과"뿐이라, 원본이 없어 목/외부 솔루션이 남긴
 * 18바이트 텍스트 스텁({@code MOCK_DEIDENTIFIED\n})이 <b>비식별 완료('Y')</b> 로 승인됐다. 'Y' 와
 * {@code MARKING_READY} 는 "원본이 실제로 있었고 그것을 비식별한 유효 영상이 회수됐다"를 뜻해야 하며,
 * 마킹 화면 스트리밍이 이 신호를 신뢰하므로 거짓 'Y' 는 후속 전 단계를 오염시킨다
 * (CWE-345 불충분한 데이터 진정성 검증 / CWE-754).
 *
 * <p><b>판정</b>(모두 통과해야 유효):
 * <ol>
 *   <li>심링크가 아닌 <b>정규 파일</b>({@code NOFOLLOW_LINKS}).</li>
 *   <li>크기 ≥ {@link #MIN_VIDEO_BYTES}.</li>
 *   <li>선두 바이트가 <b>알려진 영상 컨테이너 시그니처</b> 중 하나와 일치.</li>
 * </ol>
 *
 * <h3>오탐(false reject) 방지 설계 — 이 판정은 관대해야 한다</h3>
 * 판정 실패는 {@code failPolling}/{@code failRedeidentCompletion} 으로 <b>terminal 'F'</b> 를 만들고
 * 자동 재시도 큐가 없다(정책상 외부 솔루션 수동 재비식별). 즉 정상 파일을 거부하면 운영 사고다. 따라서:
 * <ul>
 *   <li><b>크기 하한 512바이트</b> — 근거: ① 구조적으로 단일 비디오 트랙 ISO-BMFF 는 {@code ftyp}(32B) +
 *       {@code moov}(mvhd/tkhd/mdhd/hdlr/stbl … 실측 789B) 만으로도 800B 를 넘는다. ② 실측 최소 산출물
 *       (ffmpeg H.264 16x16 <b>1프레임</b> mp4 = 1,546B / Matroska 1,345B / AVI 5,852B). 하한을 그 1/2 이하인
 *       512B 로 두어 "짧은 정상 영상"에 넉넉한 여유를 주면서 18바이트 스텁은 확실히 걸러낸다.</li>
 *   <li><b>컨테이너 시그니처만</b> 확인 — 코덱/무결 파싱은 하지 않는다(엄격 검사 금지). ISO-BMFF 계열은
 *       박스 타입이 {@code ftyp} 가 아니어도({@code moov}/{@code mdat}/{@code free}/{@code wide} 선두 등
 *       QuickTime 변형) 허용하고, Matroska/WebM·RIFF(AVI)·MPEG-TS/PS·FLV·Ogg·ASF 도 화이트리스트에 둔다.</li>
 * </ul>
 *
 * <p><b>보안</b>: 파일 스트림은 try-with-resources 로 닫는다. 로그/예외 메시지에 경로 원문을 남기지
 * 않는다(CWE-209). 판정 실패는 예외 없이 {@code false} 로 수렴한다(fail-closed).
 */
public final class DeidentArtifactIntegrity {

    /**
     * 유효 영상으로 인정하는 최소 크기(바이트). 클래스 주석의 구조적 하한(≈800B)·실측 최소 산출물
     * (1,345~1,546B)보다 낮게 잡은 보수적 값 — 오탐 거부 방지가 우선이다.
     */
    public static final long MIN_VIDEO_BYTES = 512L;

    /** 시그니처 판정에 필요한 최소 선두 바이트 수(ISO-BMFF 박스 타입이 4~8바이트에 위치). */
    private static final int HEADER_BYTES = 12;

    /** MPEG-TS 패킷 길이(바이트) — 동기 바이트 {@code 0x47} 가 이 간격으로 반복된다. */
    private static final int TS_PACKET_SIZE = 188;

    /** MPEG-TS 판정에 요구하는 동기 바이트 연속 관측 횟수(선두 포함). */
    private static final int TS_SYNC_REPEATS = 3;

    /**
     * 판독하는 선두 바이트 수 — MPEG-TS 동기 바이트 3회 확인({@code 0 / 188 / 376})에 필요한 377B.
     * 크기 하한({@value #MIN_VIDEO_BYTES}B)보다 작으므로 정상 파일은 항상 이만큼 읽을 수 있다.
     */
    private static final int PROBE_BYTES = TS_PACKET_SIZE * (TS_SYNC_REPEATS - 1) + 1;

    /**
     * ISO-BMFF/QuickTime 선두 박스 타입 allowlist — 코덱 무관, 컨테이너 여부만 본다.
     *
     * <p>{@code "junk"} 는 제외했다(DEV_FIX MEDIUM-1). 실제 영상 컨테이너의 <b>선두</b> 박스로
     * 쓰이지 않는 관례적 패딩 이름이라, 허용하면 임의 텍스트/바이너리가 "유효 비식별본" 으로 오판될
     * 표면만 넓힌다.
     */
    private static final java.util.Set<String> ISO_BOX_TYPES = java.util.Set.of(
            "ftyp", "moov", "mdat", "free", "skip", "wide", "pnot", "styp");

    private DeidentArtifactIntegrity() {
    }

    /**
     * 비식별 산출 영상으로 사용 가능한 파일인지 판정한다.
     *
     * @param filePath 회수 경로(null/blank 면 false)
     * @return 정규 파일 + 크기 하한 + 알려진 컨테이너 시그니처를 모두 만족하면 true
     */
    public static boolean isValidVideoArtifact(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        Path file;
        try {
            file = Paths.get(filePath);
        } catch (InvalidPathException e) {
            return false;
        }
        try {
            // 심볼릭 링크는 정규파일로 통과시키지 않는다(공급망 방어심도).
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (Files.size(file) < MIN_VIDEO_BYTES) {
                return false;
            }
            return hasKnownContainerSignature(readHeader(file));
        } catch (IOException e) {
            return false;
        }
    }

    /** 선두 {@value #PROBE_BYTES} 바이트를 읽는다(짧으면 읽은 만큼). */
    private static byte[] readHeader(Path file) throws IOException {
        byte[] header = new byte[PROBE_BYTES];
        int read = 0;
        try (InputStream in = Files.newInputStream(file)) {
            while (read < header.length) {
                int n = in.read(header, read, header.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        }
        if (read == header.length) {
            return header;
        }
        byte[] partial = new byte[read];
        System.arraycopy(header, 0, partial, 0, read);
        return partial;
    }

    /**
     * 알려진 영상 컨테이너 시그니처인지(관대한 화이트리스트).
     *
     * <p>패키지 프라이빗 — 단위 테스트가 바이트 배열로 직접 검증한다.
     */
    static boolean hasKnownContainerSignature(byte[] header) {
        if (header == null || header.length < HEADER_BYTES) {
            return false;
        }
        // ISO-BMFF(mp4/mov/m4v/3gp) 및 QuickTime 변형 — 4~8바이트의 박스 타입으로 판정.
        String boxType = new String(header, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1)
                .toLowerCase(java.util.Locale.ROOT);
        if (ISO_BOX_TYPES.contains(boxType)) {
            return true;
        }
        // Matroska / WebM (EBML)
        if (matches(header, 0x1A, 0x45, 0xDF, 0xA3)) {
            return true;
        }
        // RIFF 컨테이너(AVI 등) — 서브타입까지 강제하지 않는다.
        if (matches(header, 'R', 'I', 'F', 'F')) {
            return true;
        }
        // MPEG-PS(0x000001BA) / MPEG 비디오 ES(0x000001B3) 등 스타트코드 프리픽스
        if (matches(header, 0x00, 0x00, 0x01)) {
            return true;
        }
        // MPEG-TS — 188바이트 간격 동기 바이트를 연속 관측해야 인정한다(DEV_FIX MEDIUM-1).
        if (hasMpegTsSyncPattern(header)) {
            return true;
        }
        // FLV
        if (matches(header, 'F', 'L', 'V')) {
            return true;
        }
        // Ogg
        if (matches(header, 'O', 'g', 'g', 'S')) {
            return true;
        }
        // ASF / WMV
        return matches(header, 0x30, 0x26, 0xB2, 0x75);
    }

    /**
     * MPEG-TS 인가 — 선두 바이트 하나가 {@code 0x47}('G') 라는 이유만으로 인정하지 않는다.
     *
     * <p>구 판정은 {@code header[0]==0x47} 단독이라 {@code "GET /... "} 로 시작하는 <b>텍스트 로그
     * 파일</b>이 512B 만 넘으면 유효 비식별 영상으로 오판됐다(CWE-345). 그 오판은 재비식별 완료 검증을
     * 통과시켜 {@code DE_IDNTF_YN 'F'→'Y'} 복원을 유발하고, 그 결과 라벨 조회·영상 스트리밍·export
     * 게이트가 <b>동시에</b> 열린다. 그래서 규격의 구조적 성질(188바이트 패킷 정렬)을 실제로 확인한다.
     *
     * <p>{@value #TS_SYNC_REPEATS} 회 연속 정렬을 요구하므로 우연 일치 확률은 사실상 0 이며, 정상 TS
     * 파일은 첫 패킷부터 정렬돼 있어 오탐이 없다(선두 정렬을 어긴 파일은 애초에 스트림 시작이 아니다).
     */
    private static boolean hasMpegTsSyncPattern(byte[] header) {
        for (int i = 0; i < TS_SYNC_REPEATS; i++) {
            int offset = i * TS_PACKET_SIZE;
            if (offset >= header.length || (header[offset] & 0xFF) != 0x47) {
                return false;
            }
        }
        return true;
    }

    /** 선두 바이트가 주어진 시퀀스와 일치하는지. */
    private static boolean matches(byte[] header, int... expected) {
        if (header.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((header[i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
