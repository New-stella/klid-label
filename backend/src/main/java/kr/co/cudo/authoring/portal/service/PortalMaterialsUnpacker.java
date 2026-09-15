package kr.co.cudo.authoring.portal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 배포 압축본을 <b>우리 자리로 복사한 뒤 그 사본을 푼다</b>.
 *
 * <h3>★ 원본을 건드리지 않는다 (INT-014 「우리 쪽 규칙 4」)</h3>
 * <p>조달처의 압축본은 포털이 소유·관리하는 원본이다. 이 클래스는 원본을 <b>읽기로만</b> 열고
 * 이동·수정·삭제·덮어쓰기를 하지 않는다. 원본을 직접 풀지 않는 이유도 같다 — 읽는 자리와 푸는
 * 자리를 가른다.
 *
 * <h3>★ 압축 해제는 신뢰 경계다 — 두 부류를 함께 막는다</h3>
 * <ul>
 *   <li><b>경로 탈출(zip slip, CWE-22)</b> — 항목 이름이 {@code ../} 나 절대경로를 담아 대상 밖으로
 *       나가는 것. 정규화 후 대상 하위인지 확인하고, 아니면 <b>그 자리에서 중단</b>한다(그 항목만
 *       건너뛰지 않는다 — 그런 압축본은 통째로 신뢰할 수 없다).</li>
 *   <li><b>자원 고갈(zip bomb, CWE-409/770)</b> — 작은 압축본이 거대하게 부푸는 것. <b>항목 수</b>와
 *       <b>해제 총 바이트</b>에 상한을 두고 초과하면 중단한다. 총량은 <b>실제로 쓴 바이트</b>로만
 *       판정하고 항목이 선언한 크기는 보지 않는다 — 그 값은 압축본이 스스로 적어 온 메타라 거짓일
 *       수 있고, 선언값 검사를 앞에 덧대면 정상 경로에서 그쪽이 먼저 걸려 <b>진짜 방어선이 한 번도
 *       실행되지 않는다</b>(그 상태에서는 방어선을 지워도 아무 시험이 죽지 않는다).</li>
 * </ul>
 * <p>중단 시 정리는 호출자가 작업 중 디렉터리를 통째로 버리는 것으로 끝난다.
 *
 * <h3>심링크 항목을 만들지 않는다</h3>
 * <p>압축 형식은 심링크를 실을 수 있지만 우리는 <b>항상 정규 파일로</b> 쓴다. 그래서 해제본 안에는
 * 우리가 만든 링크가 존재하지 않고, 이후 이 자리를 읽는 판정이 링크를 따라갈 일이 없다(CWE-59).
 *
 * <p>경로 원문·항목 이름을 로그에 남기지 않는다(CWE-209/117).
 *
 * @design INT-014
 */
@Slf4j
@Component
public class PortalMaterialsUnpacker {

    /** 복사해 온 압축본의 사본 이름 — 해제가 끝나면 지운다. */
    public static final String LOCAL_COPY_NAME = ".source.zip";

    /**
     * 해제본이 놓이는 하위 디렉터리 이름.
     *
     * <p>공개된 자리의 배치는 <b>다음 라운드의 적재 경로가 읽어야 할 계약</b>이라 공개 상수로 둔다.
     */
    public static final String CONTENT_DIR = "content";

    private final int maxEntries;
    private final long maxTotalBytes;

    /**
     * @param maxEntries    해제 항목 수 상한. ⚠ 설계에 확정된 값이 아니라 <b>자원 고갈 방어의
     *                      안전 상한</b>이다 — 사양이 값을 정하면 그 값으로 바꾼다
     * @param maxTotalBytes 해제 총 바이트 상한. 같은 성격의 안전 상한이다
     */
    public PortalMaterialsUnpacker(
            @Value("${authoring.portal.materials.max-entries:200000}") int maxEntries,
            @Value("${authoring.portal.materials.max-total-bytes:21474836480}") long maxTotalBytes) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxTotalBytes = Math.max(1L, maxTotalBytes);
    }

    /**
     * 원본 압축본을 작업 중 자리로 복사하고 그 사본을 푼다.
     *
     * @param sourceRealPath 경로 재검증을 통과한 <b>실경로</b>. 이 값 그대로 연다(TOCTOU 차단)
     * @param stagingDir     작업 중 디렉터리
     * @return 해제 요약
     * @throws UnpackException 경로 탈출·상한 초과·압축본 손상
     * @throws IOException     입출력 실패
     */
    public UnpackResult copyAndUnpack(Path sourceRealPath, Path stagingDir) throws IOException {
        Path localCopy = stagingDir.resolve(LOCAL_COPY_NAME);
        // ★ 원본은 읽기로만 연다. NOFOLLOW — 판정에 쓴 그 대상을 열고, 그 사이에 링크로 바뀌었으면
        //   따라가지 않고 실패한다(CWE-59/367).
        try (InputStream in = Files.newInputStream(sourceRealPath, LinkOption.NOFOLLOW_LINKS);
             OutputStream out = Files.newOutputStream(localCopy,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            copyBounded(in, out);
        }
        Path contentDir = stagingDir.resolve(CONTENT_DIR);
        Files.createDirectory(contentDir);
        UnpackResult result = unpack(localCopy, contentDir);
        // 사본은 해제가 끝나면 쓸모가 없다 — 압축본 크기만큼 두 번 차지할 이유가 없다.
        Files.deleteIfExists(localCopy);
        return result;
    }

    /**
     * 원본을 사본으로 옮기되 <b>흘러간 바이트를 세어 상한에서 끊는다</b>.
     *
     * <h3>왜 {@code transferTo} 로는 안 되는가</h3>
     * <p>그것은 끝까지 옮긴다. 해제 상한({@code maxTotalBytes})은 그 복사가 <b>끝난 뒤</b>에야
     * 적용되므로, 조달처가 가리킨 소재가 거대하면 <b>방어선에 닿기 전에 작업영역 디스크가 찬다</b>
     * (CWE-770). 경로 축은 막으면서 크기 축만 열어 두는 것은 같은 위협 모델 안에서의 비대칭이다.
     *
     * <h3>⚠ 선언 크기를 「먼저」 보지 않는다</h3>
     * <p>응답의 파일 크기나 압축본이 선언한 크기로 <b>사전 차단을 앞에 덧대지 말 것.</b> 이 클래스가
     * 바로 그 함정을 한 번 겪었다 — 선언값 사전 검사가 앞에 있으면 정상 입력에서 그것이 먼저 걸려
     * <b>실제 바이트를 세는 이 방어선이 한 번도 실행되지 않고</b>, 그것을 지워도 시험이 죽지 않는다.
     * 믿을 수 없는 값(상대가 말한 크기)이 믿을 수 있는 값(우리가 센 바이트)을 시험에서 가린다.
     *
     * @throws UnpackException 상한을 넘으면 {@link Reason#TOO_LARGE}. 호출부가 staging 을 지운다
     */
    private void copyBounded(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        long copied = 0L;
        int n;
        while ((n = in.read(buf)) != -1) {
            copied += n;
            if (copied > maxTotalBytes) {
                // 넘긴 분까지 쓰지 않는다 — 쓰고 나서 재는 것과 다르다.
                throw new UnpackException(Reason.TOO_LARGE,
                        "조달처 소재의 크기가 허용 상한을 넘었습니다.");
            }
            out.write(buf, 0, n);
        }
    }

    /** 사본을 대상 디렉터리로 푼다 — 이 메서드가 두 부류의 방어를 소유한다. */
    UnpackResult unpack(Path zipPath, Path targetDir) throws IOException {
        Path base = targetDir.toAbsolutePath().normalize();
        int entries = 0;
        long totalBytes = 0L;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (++entries > maxEntries) {
                    throw new UnpackException(Reason.TOO_MANY_ENTRIES,
                            "압축본의 항목 수가 허용 상한을 넘었습니다.");
                }
                Path target = safeTarget(base, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                // ★ 선언 크기(entry.getSize())를 <보지 않는다>. 그 값은 압축본이 스스로 적어 온
                //   메타라 거짓일 수 있고, 그것을 앞에 덧대면 정상 경로에서 그쪽이 먼저 걸려
                //   <진짜 방어선인 아래 실제 바이트 계수가 한 번도 실행되지 않는다>. 그러면 그 계수를
                //   지워도 어떤 시험도 죽지 않는다(실측으로 확인했다). 방어선은 하나로 둔다.
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                totalBytes += writeEntry(zip, entry, target, totalBytes);
            }
        } catch (ZipException e) {
            throw new UnpackException(Reason.CORRUPT, "압축본을 읽을 수 없습니다.");
        }
        return new UnpackResult(entries, totalBytes);
    }

    /**
     * 항목 이름을 대상 하위 경로로 접는다 — <b>탈출하면 그 자리에서 중단</b>한다(zip slip).
     *
     * <p>절대경로 항목({@code /etc/passwd})과 상위 이동({@code ../../x}) 둘 다 정규화 후 대상 하위가
     * 아니게 되므로 같은 판정에 걸린다. 항목 이름을 예외·로그에 싣지 않는다(CWE-117/209).
     */
    private static Path safeTarget(Path base, String entryName) {
        if (entryName == null || entryName.isBlank()) {
            throw new UnpackException(Reason.PATH_ESCAPE, "압축본에 이름 없는 항목이 있습니다.");
        }
        Path target;
        try {
            target = base.resolve(entryName).normalize();
        } catch (InvalidPathException e) {
            throw new UnpackException(Reason.PATH_ESCAPE, "압축본에 해석할 수 없는 항목 경로가 있습니다.");
        }
        if (!target.startsWith(base)) {
            log.warn("[PortalMaterials] 압축 해제 중 대상 밖을 가리키는 항목을 발견해 중단합니다.");
            throw new UnpackException(Reason.PATH_ESCAPE, "압축본에 허용되지 않은 경로의 항목이 있습니다.");
        }
        return target;
    }

    /**
     * 항목 하나를 <b>정규 파일로</b> 쓰면서 실제 바이트를 센다.
     *
     * <p>{@code CREATE_NEW} 라 같은 이름이 두 번 오면 실패한다 — 나중 항목이 앞 항목을 덮어써
     * 조용히 내용이 바뀌는 것을 막는다.
     *
     * @param written 지금까지 쓴 총 바이트 — 상한 판정의 기준
     * @return 이 항목이 쓴 바이트
     */
    private long writeEntry(ZipFile zip, ZipEntry entry, Path target, long written) throws IOException {
        long remaining = maxTotalBytes - written;
        long produced = 0L;
        byte[] buffer = new byte[8192];
        try (InputStream in = zip.getInputStream(entry);
             OutputStream out = Files.newOutputStream(target,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                produced += read;
                if (produced > remaining) {
                    // ★ 선언 크기가 아니라 <실제로 나온 바이트>로 막는다 — 선언값은 거짓일 수 있다.
                    throw new UnpackException(Reason.TOO_LARGE,
                            "압축 해제 총량이 허용 상한을 넘었습니다.");
                }
                out.write(buffer, 0, read);
            }
        }
        return produced;
    }

    /** 해제 요약 — 응답에 실을 수 있는 값만 담는다(경로 없음). */
    public record UnpackResult(int entryCount, long totalBytes) {
    }

    /** 해제 중단 사유. */
    public enum Reason {
        /** 항목이 대상 디렉터리 밖을 가리킨다(zip slip). */
        PATH_ESCAPE,
        /** 항목 수가 상한을 넘었다. */
        TOO_MANY_ENTRIES,
        /** 해제 총량이 상한을 넘었다. */
        TOO_LARGE,
        /** 압축본을 읽을 수 없다. */
        CORRUPT
    }

    /** 해제 중단 — 사유를 <b>값</b>으로 보존한다(메시지 파싱 금지). */
    public static class UnpackException extends RuntimeException {

        private final Reason reason;

        public UnpackException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }
}
