package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.storage.DeidentArtifactIntegrity;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * R3 — 비식별 신고 해소 시 고를 수 있는 <b>재비식별 산출물 후보</b>를 열거하는 단일 지점.
 *
 * <h3>왜 필요한가 (이 컴포넌트가 없으면 해소가 영영 불가능한 신고가 있다)</h3>
 * <p>구 해소 판정은 원장({@code LS_DEIDENT_PROC_LOG})에 <b>기록된 경로 1개</b>만 보고 그 파일의
 * mtime 이 신고시각 이후인지로 "재비식별됐다"를 판단했다. 즉 외부 솔루션이 <b>같은 이름으로 제자리
 * 덮어쓰기</b> 하는 것을 전제한다. 그런데 실제 외부 솔루션(KPST)은 {@code {원본stem}-mask{ext}} 처럼
 * <b>다른 이름</b>으로 산출하므로, 그런 경우 기록된 경로의 파일은 바뀌지 않아 그 신고는 영원히
 * 해소되지 않는다(작업락 + {@code DE_IDNTF_YN='F'} 영구 고착).
 * <p>그래서 서버가 <b>산출 디렉터리를 열거해 후보 목록</b>을 만들고, 사람이 그중 실제 재비식별
 * 산출물을 고른다.
 *
 * <h3>목록이 곧 허용목록이다 (CWE-22)</h3>
 * <p>선택값({@code fileName})으로 <b>경로를 조립하지 않는다</b>. 조회(후보 목록)와 수락(해소)이
 * {@link #find(LsDeidentReport)} <b>같은 열거 코드</b>를 쓰고, 수락은 그 결과 목록에 이름이 있을 때만
 * 성립한다({@link #select(LsDeidentReport, String)}). 목록의 키는 언제나 <b>basename</b> 이므로
 * {@code ../x}·절대경로·심링크명 같은 입력은 구조적으로 어떤 항목과도 같아질 수 없다.
 *
 * <h3>후보 디렉터리 — 판정 축을 복제하지 않는다</h3>
 * <p>비식별 <b>영상</b>의 위치 판정은 {@link VideoArtifactRootResolver} 한 곳이며, 여기서도 그것을
 * 그대로 쓴다({@code KpstDeidentService} 의 회수 폴백과 <b>같은 순서</b> — 현 전략의 쓰기 대상을 먼저,
 * 그다음 읽기 허용 후보 전체):
 * <ol>
 *   <li>{@link VideoArtifactRootResolver#deidVideoDirQuietly} — 현 전략의 비식별 영상 디렉터리</li>
 *   <li>{@link VideoArtifactRootResolver#readableDeidVideoDirs} — 구 위치({@code {deid_base}/videos/{rawSn}})
 *       + co-locate 위치({@code dirname(원본)/{rawSn}/deid}) 2-way allowlist</li>
 * </ol>
 * <p>⚠ {@code StorageSubtreePolicy.verifyDeidentifiedFile} 은 여기에 쓸 수 없다 — 그것은 비식별
 * <b>프레임 이미지</b>({@code {deid_base}} 하위 {@code frames/deid/**}·{@code videos/**}) 판정기라,
 * co-locate 전략(기본값)의 비식별 <b>영상</b>({@code dirname(원본)/{rawSn}/deid/})은 그 서브트리 밖이라
 * <b>모든 후보가 거부</b>된다. 대신 같은 DB 값을 읽는 형제 소비자
 * ({@code VideoStreamService}·{@code FfmpegFrameExtractor})가 쓰는
 * {@link VideoArtifactRootResolver#resolveRealPathUnder}(실경로 판정 + <b>실경로 반환</b>)를 쓴다.
 *
 * <h3>TOCTOU / 심링크 (CWE-59 / CWE-367)</h3>
 * <p>열거는 {@code NOFOLLOW_LINKS} 정규 파일만 담으므로 심링크 엔트리는 후보가 되지 않는다. 그리고
 * 판정이 돌려준 <b>실경로</b>를 후보에 실어 호출측(무결성 검사 · 원장 적재)이 그대로 쓰게 한다 —
 * lexical 경로로 판정하고 lexical 경로로 여는 형태를 만들지 않는다.
 *
 * <h3>현재 원장 경로는 열거 결과와 무관하게 항상 후보다</h3>
 * <p>{@code DE_IDNTF_FILE_PATH_NM} 은 <b>DB 적재값</b>(사용자 입력 아님)이며 구 판정이 유일하게 쓰던
 * 값이다. 열거 디렉터리 밖에 있어도(전략 전환 이전 산출물 등) 목록에서 빠지면 <b>제자리 덮어쓰기
 * 정상 해소</b>가 막히므로, 이름이 중복되지 않는 한 항상 {@code current=true} 로 덧붙인다. 이 항목의
 * 자격 판정만은 구 규약을 그대로 유지한다(아래 시간 조건 참조).
 *
 * <h3>자원 상한 — <b>둘</b>이다 (CWE-770)</h3>
 * <p>이 디렉터리는 <b>외부 비식별 솔루션이 결과를 쓰는 위치</b>라 오작동·오설정으로 항목이 대량으로
 * 쌓일 수 있고(그 가정이 곧 이 기능의 존재 이유다), 조회 API 는 반복 호출된다. 그래서 상한을 둘 둔다:
 * <ol>
 *   <li>{@link #MAX_CANDIDATES} — <b>결과 후보 수</b> 상한. 디렉터리를 넘나들며 누적 적용된다.</li>
 *   <li>{@link #MAX_SCAN_ENTRIES} — <b>디렉터리 1개당 살펴보는 항목 수</b> 하드 상한. 열거 자체를
 *       여기서 끊으므로 적재 메모리·{@code isRegularFile} syscall·정렬 비용이 모두 이 값 이하로
 *       묶인다(디렉터리 크기에 비례해 커지지 않는다).</li>
 * </ol>
 * <p>⚠ ②가 없으면 ①은 <b>선언만 하고 걸리지 않는다</b> — 디렉터리 항목을 전량 적재·정렬한 <i>뒤</i>
 * 순회 루프에서야 잘리기 때문이다(2026-08-10 정정. 구 서술 "열거는 {@code MAX_CANDIDATES} 건에서
 * 멈춘다"는 사실과 달랐다).
 * <p>⚠ <b>②가 걸리면 이름순 정렬은 "스캔 창 안에서만" 성립한다</b> — 창에 담긴 항목은 파일시스템
 * 순서(비결정적)로 정해지므로, 항목 수가 ②를 넘는 디렉터리에서는 이름이 앞서는 파일이 창에 못 들어와
 * 목록에서 빠질 수 있다. 다만 <b>현재 원장이 가리키는 산출물은 열거와 무관하게 항상 후보</b>이므로
 * (아래 절) 정상 해소 동선(제자리 덮어쓰기)은 절단과 무관하게 보존된다.
 * <p>어느 상한이든 걸리면 <b>WARN 으로 남긴다</b> — 응답에 절단 사실을 알릴 필드가 없어 로그가 유일한
 * 관측 수단이다(조용한 절단 금지). 로그에는 {@code rawSn}·상한값만 싣고 <b>내부 경로는 싣지 않는다</b>
 * (CWE-117 / CWE-209).
 */
@Slf4j
@Component
public class DeidentArtifactCandidateFinder {

    /** 결과 후보 수 상한 — 무제한 나열은 자원 소모 경로다(CWE-770 / OWASP API4). */
    public static final int MAX_CANDIDATES = 200;

    /**
     * 디렉터리 1개당 살펴보는 항목 수 하드 상한 — <b>열거를 여기서 끊는다</b>.
     *
     * <p>{@link #MAX_CANDIDATES} 보다 넉넉히 잡는다: 열거 항목 중 심링크·중복 이름·실경로 검증 탈락분은
     * 후보가 되지 못하므로, 두 값을 같게 두면 정상 디렉터리에서도 후보가 상한보다 일찍 마른다.
     */
    public static final int MAX_SCAN_ENTRIES = 1_000;

    private final VideoRepository videoRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final VideoArtifactRootResolver artifactRootResolver;
    private final int maxCandidates;
    private final int maxScanEntries;

    @Autowired
    public DeidentArtifactCandidateFinder(VideoRepository videoRepository,
                                          LsDeidentProcLogRepository procLogRepository,
                                          VideoArtifactRootResolver artifactRootResolver) {
        this(videoRepository, procLogRepository, artifactRootResolver, MAX_CANDIDATES, MAX_SCAN_ENTRIES);
    }

    /**
     * 상한 주입 생성자 — <b>테스트 전용</b>(package-private).
     *
     * <p>절단 경로를 검증하려면 상한을 넘는 항목이 필요한데, 운영 기본값({@link #MAX_SCAN_ENTRIES})
     * 만큼 실제 파일을 만드는 테스트는 느리고 <b>"전량 적재하지 않는다"를 단언할 수도 없다</b>
     * (전량 적재해도 통과한다). 상한을 낮게 주입하면 "만든 파일 수보다 적게 담겼다"로 그 성질을
     * 직접 단언할 수 있다.
     */
    DeidentArtifactCandidateFinder(VideoRepository videoRepository,
                                   LsDeidentProcLogRepository procLogRepository,
                                   VideoArtifactRootResolver artifactRootResolver,
                                   int maxCandidates, int maxScanEntries) {
        this.videoRepository = videoRepository;
        this.procLogRepository = procLogRepository;
        this.artifactRootResolver = artifactRootResolver;
        this.maxCandidates = maxCandidates;
        this.maxScanEntries = maxScanEntries;
    }

    /**
     * 재비식별 산출물 후보 1건.
     *
     * <p>{@code path} 는 <b>내부 실경로</b>라 응답 DTO 로 나가지 않는다 — 내부 저장 경로 노출 금지
     * (CWE-209, {@code deidentNotVerified} 가 경로를 감추는 것과 같은 축). 화면·API 에는
     * {@code fileName}/{@code sizeBytes}/{@code modifiedAt}/{@code eligible}/{@code current} 만 나간다.
     *
     * @param fileName   파일명(basename) — 선택 키
     * @param path       실경로(내부 전용)
     * @param sizeBytes  파일 크기
     * @param modifiedAt 파일 수정 시각
     * @param eligible   무결성 + 시간조건을 모두 통과했는가(해소에 쓸 수 있는가)
     * @param current    현재 원장({@code DE_IDNTF_FILE_PATH_NM})이 가리키는 그 파일인가
     */
    public record Candidate(String fileName, Path path, long sizeBytes,
                            LocalDateTime modifiedAt, boolean eligible, boolean current) {
    }

    /**
     * 이 신고의 영상에 대응하는 재비식별 산출물 후보 목록(파일명 오름차순, 디렉터리 순서 유지).
     *
     * <p>디렉터리가 없거나 비어 있으면 <b>빈 목록</b>을 반환한다(예외 아님 — 호출측이 200 으로 응답).
     * <p>⚠ 항목이 {@link #MAX_SCAN_ENTRIES} 를 넘는 디렉터리에서는 정렬이 <b>스캔 창 안에서만</b>
     * 성립한다(위 「자원 상한」 절).
     */
    public List<Candidate> find(LsDeidentReport report) {
        Long rawSn = report.getRawSn();
        String rawFilePathNm = videoRepository.findById(rawSn)
                .map(LsDataRaw::getRawFilePathNm)
                .orElse(null);
        LsDeidentProcLog latest = procLogRepository.findLatestSuccessByDataRawSn(rawSn).orElse(null);
        Path currentPath = currentArtifactPath(latest);
        boolean currentProcAfterReport = procTimeAfterReport(latest, report);

        LinkedHashMap<String, Candidate> byName = new LinkedHashMap<>();
        for (Path dir : candidateDirs(rawSn, rawFilePathNm)) {
            if (byName.size() >= maxCandidates) {
                log.warn("[DeidentReport] candidate scan truncated rawSn={} limit={}", rawSn, maxCandidates);
                break;
            }
            scanDir(dir, report, currentPath, currentProcAfterReport, byName);
        }

        // 현재 원장 경로는 열거 밖이어도 후보에서 빠지지 않는다(제자리 덮어쓰기 정상 해소 보존).
        if (currentPath != null && !byName.containsKey(fileNameOf(currentPath))) {
            describe(fileNameOf(currentPath), currentPath, report, true, currentProcAfterReport)
                    .ifPresent(c -> byName.put(c.fileName(), c));
        }
        return List.copyOf(byName.values());
    }

    /**
     * 후보 목록에서 {@code fileName} 과 <b>정확히 일치</b>하는 항목을 고른다 — 목록이 곧 허용목록이다.
     *
     * <p>목록 키는 basename 이므로 경로 순회 시도({@code ../…}·절대경로)는 어떤 항목과도 일치하지
     * 않아 자연히 빈 결과가 된다(예외를 던지지 않는다 — 호출측이 400 으로 정규화).
     */
    public Optional<Candidate> select(LsDeidentReport report, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        return find(report).stream()
                .filter(c -> c.fileName().equals(fileName))
                .findFirst();
    }

    // ---------- 내부 ----------

    /** 열거 대상 디렉터리 — 현 전략 쓰기 대상 우선, 그다음 읽기 허용 후보 전체(중복 제거). */
    private List<Path> candidateDirs(Long rawSn, String rawFilePathNm) {
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        if (rawSn == null) {
            return List.of();
        }
        try {
            artifactRootResolver.deidVideoDirQuietly(rawSn, rawFilePathNm).ifPresent(dirs::add);
            dirs.addAll(artifactRootResolver.readableDeidVideoDirs(rawSn, rawFilePathNm));
        } catch (RuntimeException e) {
            // 후보 도출 실패(rawSn 불량·base 검증 위반)는 그 후보만 빠진다(fail-secure).
            log.warn("[DeidentReport] candidate dir resolution partially failed rawSn={} errType={}",
                    rawSn, e.getClass().getSimpleName());
        }
        return List.copyOf(dirs);
    }

    /**
     * 디렉터리 바로 아래 정규 파일만(재귀 금지 · 심링크 제외) 후보로 담는다.
     *
     * <p><b>열거는 {@link #MAX_SCAN_ENTRIES} 항목에서 끊는다</b> — 적재·정렬 <i>이전에</i> 끊어야
     * 상한이 실제로 자원을 묶는다(전량 적재 후 자르면 메모리·syscall·정렬이 디렉터리 크기에 비례한다,
     * CWE-770). 정렬은 그렇게 확보한 <b>스캔 창 안에서만</b> 적용된다.
     */
    private void scanDir(Path dir, LsDeidentReport report, Path currentPath,
                         boolean currentProcAfterReport,
                         LinkedHashMap<String, Candidate> byName) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> entries = new ArrayList<>();
        boolean scanTruncated = false;
        try (Stream<Path> stream = Files.list(dir)) {
            // 스트림 지연 평가 위에서 직접 센다 — filter/forEach 로 흘리면 디렉터리 전체를 통과시킨 뒤에야
            // 멈출 수 있어(그게 이 결함이었다) 항목 수에 비례한 비용이 그대로 발생한다.
            Iterator<Path> it = stream.iterator();
            int examined = 0;
            while (it.hasNext()) {
                Path entry = it.next();
                if (++examined > maxScanEntries) {
                    scanTruncated = true;
                    break;
                }
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    entries.add(entry);
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // 순회 실패 — 그 디렉터리만 건너뛴다(경로 원문 미노출, CWE-209).
            log.warn("[DeidentReport] candidate dir list failed rawSn={} errType={}",
                    report.getRawSn(), e.getClass().getSimpleName());
            return;
        }
        if (scanTruncated) {
            // 절단은 "사용자가 필요한 후보를 못 보는" 상황이라 반드시 관측 가능해야 한다(조용한 절단 금지).
            // 경로는 싣지 않는다(CWE-117/209) — rawSn 과 상한값만.
            log.warn("[DeidentReport] candidate dir scan truncated rawSn={} scanLimit={}",
                    report.getRawSn(), maxScanEntries);
        }
        // 파일시스템 순서는 비결정적이라 파일명 오름차순으로 고정한다(같은 입력 = 같은 목록).
        // ⚠ 절단된 경우 이 정렬은 스캔 창 안에서만 성립한다(창 밖 항목은 애초에 담기지 않았다).
        entries.sort(Comparator.comparing(p -> p.getFileName().toString()));

        for (Path entry : entries) {
            if (byName.size() >= maxCandidates) {
                log.warn("[DeidentReport] candidate scan truncated rawSn={} limit={}",
                        report.getRawSn(), maxCandidates);
                return;
            }
            String name = entry.getFileName().toString();
            if (byName.containsKey(name)) {
                continue; // 앞선 디렉터리(현 전략 쓰기 대상)가 우선한다.
            }
            Path real;
            try {
                // 판정한 그 경로(실경로)를 후보에 싣는다 — lexical 로 열지 않는다(CWE-59/367).
                real = VideoArtifactRootResolver.resolveRealPathUnder(entry, dir);
            } catch (RuntimeException e) {
                continue; // 실경로가 디렉터리 밖(심링크 우회 등) — fail-secure 로 후보에서 제외.
            }
            boolean current = isSamePath(real, currentPath);
            describe(name, real, report, current, current && currentProcAfterReport)
                    .ifPresent(c -> byName.put(name, c));
        }
    }

    /**
     * 원장 완료시각이 신고시각 이후인가 — <b>현재 원장이 가리키는 후보에만</b> 적용하는 구 규약 보존.
     *
     * <p>구 판정은 시간 조건을 OR 로 묶었다: ① 원장 완료시각({@code RSPNS_DT}, 없으면 {@code REQ_DT})
     * &gt; 신고시각(신고 후 <b>자동</b> 재비식별이 성공한 경우) 또는 ② 파일 mtime &gt; 신고시각(외부
     * 도구의 제자리 교체). ②만 남기면 배치가 재비식별을 성공시킨 건이 수동 해소에서 거부되므로 ①을
     * 유지한다. 열거로 발견한 <b>다른</b> 후보에는 대응 원장이 없어 ②만 적용된다.
     */
    private boolean procTimeAfterReport(LsDeidentProcLog latest, LsDeidentReport report) {
        LocalDateTime reportTime = report.getReportDt();
        if (latest == null || reportTime == null) {
            return false;
        }
        LocalDateTime procTime = latest.getResDt() != null ? latest.getResDt() : latest.getReqDt();
        return procTime != null && procTime.isAfter(reportTime);
    }

    /**
     * 파일 1건을 후보로 기술한다. 정규 파일이 아니거나 속성을 읽을 수 없으면 후보가 아니다(빈 결과).
     *
     * <p>{@code eligible} = 무결성({@link DeidentArtifactIntegrity} <b>단일 판정기 위임</b>)
     * &amp;&amp; (mtime &gt; 신고시각 <b>엄격</b> || {@code procAfterReport}).
     * 경계값(mtime == 신고시각)은 거부다 — 동일 시각 파일은 신고 시점에 이미 존재하던 산출물이라
     * "신고 이후 교체" 증거가 아니며, 증거 없음은 fail-closed 로 거부에 수렴한다.
     */
    private Optional<Candidate> describe(String fileName, Path path, LsDeidentReport report,
                                         boolean current, boolean procAfterReport) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        long size;
        LocalDateTime modifiedAt;
        try {
            size = Files.size(path);
            modifiedAt = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            return Optional.empty();
        }
        LocalDateTime reportTime = report.getReportDt();
        // 신고시각을 모르면 시간 판정이 불가능하므로 보수적으로 부적격(fail-closed).
        boolean timeOk = reportTime != null && (modifiedAt.isAfter(reportTime) || procAfterReport);
        boolean eligible = timeOk && DeidentArtifactIntegrity.isValidVideoArtifact(path.toString());
        return Optional.of(new Candidate(fileName, path, size, modifiedAt, eligible, current));
    }

    /** 원장에 적재된 비식별 산출물 경로(없거나 손상이면 null) — 문자열 조합·추측 금지, 적재값 그대로. */
    private Path currentArtifactPath(LsDeidentProcLog latest) {
        if (latest == null) {
            return null;
        }
        String recorded = latest.getDeIdntfFilePathNm();
        if (recorded == null || recorded.isBlank()) {
            return null;
        }
        try {
            return Paths.get(recorded);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String fileNameOf(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    /**
     * 같은 파일인가 — 표시용({@code current} 배지) 판정.
     *
     * <p><b>실경로로 비교한다</b>: 열거 후보는 {@code toRealPath} 를 거친 값이고 원장 경로는 DB 적재
     * 문자열이라, 중간 경로에 심링크가 있으면(예: macOS {@code /var}→{@code /private/var}, 운영 NAS
     * 마운트 별칭) 같은 파일인데도 정규화 비교가 어긋나 <b>같은 파일이 후보 목록에 두 번</b> 뜨거나
     * "현재 사용 중" 표시가 붙지 않는다. 실경로 해석에 실패하면 정규화 비교로 물러선다.
     */
    private static boolean isSamePath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        if (realOrNull(a) != null && realOrNull(a).equals(realOrNull(b))) {
            return true;
        }
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /** 실경로(해석 실패 시 null) — 비교 전용. */
    private static Path realOrNull(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
