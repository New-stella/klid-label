package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * 폐기 확정된 <b>파생영상 생성 파일</b> 삭제 — Phase 7 (H5 · H6).
 *
 * <h2>삭제 대상은 파생 전용 서브트리뿐이다</h2>
 * <pre>
 *   {deidBase}/frames/deid/{파생 rawSn}/**                              ← 파생 프레임(증강·해상도 공통 규약)
 *   {deidBase}/videos/{augment|resolution}/{부모}/{파생 rawSn}/*.mp4     ← 파생 비디오(부모 비식별본의 사본)
 * </pre>
 * <p>{@code frames/raw/**}(원본 프레임)와 관제 NAS 원본 영상은 <b>경로 계산 대상에조차 넣지 않는다</b> —
 * 파생은 그곳에 아무것도 쓰지 않으므로 지울 것도 없다.
 *
 * <h2>심링크 · TOCTOU 방어 (CWE-59 / CWE-367)</h2>
 * <p>운영은 {@code STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH}({@code /nas-storage})라, 파생 디렉터리
 * 안의 심링크가 {@code frames/raw/**} 를 가리키면 문자열 접두 검사는 그대로 통과한다. 그래서
 * <ol>
 *   <li>비디오는 {@link StorageSubtreePolicy#verifyDeidentifiedFile}(실경로 판정)이 돌려준 <b>실경로</b>를
 *       그대로 지우고, 그 실경로가 <b>이 파생 전용 디렉터리</b>({@code videos/…/{rawSn}/})인지 세그먼트
 *       단위로 다시 확인한다 — 파생 RAW 의 경로 값이 (레거시·오적재로) 부모 비식별본이나 원본을
 *       가리키면 지우지 않는다.</li>
 *   <li>프레임 디렉터리는 <b>심링크를 따라가지 않고</b> 순회하며({@code walkFileTree} 기본 동작), 만난
 *       심링크는 <b>지우지도 따라가지도 않고 skip + WARN</b> 한다. 우리가 만들지 않은 항목을 지우지
 *       않는다는 원칙이며, 결과적으로 링크가 가리키던 원본 파일은 온전히 남는다.</li>
 * </ol>
 * <p>판정이 서지 않으면 <b>그 파생의 파일 삭제를 skip</b> 한다 — 원본 삭제보다 고아 파일 존치가 안전하다.
 *
 * <h2>부분 실패 처리 — <b>결과는 3값</b>이다 (FIX-3)</h2>
 * <p>{@link #remove}는 예외를 던지지 않고 {@link Outcome} 을 돌려준다. 구 구현은 boolean 이었는데, 그러면
 * <b>재시도로 언젠가 풀리는 실패</b>(NAS 순단·권한 일시 오류)와 <b>재시도해도 영원히 같은 잔존</b>(심링크·
 * 비정규 항목 — 우리가 <b>의도적으로</b> 지우지 않는다)이 구분되지 않는다. 그 결과 후자가 재시도 큐에
 * 영구히 남아 <b>오래된 순 배치의 앞자리를 점유</b>하고, 그런 비석이 batch-size 만큼 쌓이면 이후 생성되는
 * 모든 비석의 파일 정리가 전면 정지한다(head-of-line blocking).
 * <p>그래서 "지우지 않는다" 는 원칙은 그대로 두고 <b>사유 축만 분리</b>한다 —
 * {@link Outcome#UNRESOLVABLE} 는 호출측이 즉시 데드레터로 종결해 큐에서 빼고,
 * {@link Outcome#RETRYABLE} 만 재시도 상한 안에서 다시 시도한다.
 * 경로 원문은 로그에 남기지 않는다(CWE-117/209/359).
 *
 * <h2>판정 → 결과 매핑은 <b>단일 판정기</b>다 (FIX-A)</h2>
 * <p>구 구현은 두 축이 <b>같은 원인을 정반대로</b> 분류했다 — 비디오 축은 {@code MISSING} 이 아닌
 * 모든 판정을 {@link Outcome#UNRESOLVABLE} 로 접어 넣어 <b>일시적</b> 실패인
 * {@link StorageSubtreePolicy.Verdict#REALPATH_FAILED}(권한 순단·NFS ESTALE·마운트 flap)까지
 * 시도 1회 만에 데드레터로 종결했고, 프레임 축은 같은 {@code IOException} 을
 * {@link Outcome#RETRYABLE} 로 봤다. 한 원인이 축마다 다르게 판정되는 것 자체가 결함이므로 매핑을
 * {@link #classify(StorageSubtreePolicy.Verdict, boolean)} <b>한 표</b>로 모은다:
 * <ul>
 *   <li><b>재시도해도 결과가 같은 것</b>(규약 밖 경로·심링크·비정규 항목) → {@code UNRESOLVABLE}</li>
 *   <li><b>환경이 나아지면 달라질 수 있는 것</b>(실경로 해석 실패·I/O·권한) → {@code RETRYABLE}</li>
 * </ul>
 *
 * <h2>"이미 없음" 과 "저장소에 닿지 못함" 은 다르다 (FIX-B)</h2>
 * <p>마운트가 빠진 마운트포인트 스텁은 <b>디렉터리는 존재하되 비어 있다</b>. 그러면 대상 파일은
 * "없음"({@code MISSING}) 으로 관측되고, 구 구현은 그것을 <b>멱등 성공</b>으로 읽어 아무것도 지우지
 * 않은 채 비석을 "정리 완료" 로 닫았다 — 마운트가 돌아오면 파일은 그대로인데 재시도 큐에도
 * 데드레터에도 없어 <b>사람이 알 근거가 0</b>이다(데드레터보다 나쁘다). 그래서 "없음" 을 성공으로
 * 단정하기 전에 <b>대상이 놓이는 부모 디렉터리가 실제로 보이는지</b>({@link #targetLocationObservable})
 * 확인하고, 보이지 않으면 성공이 아니라 {@code RETRYABLE} 로 둔다. 진짜로 이미 지워진 경우
 * (정상 멱등 재시도)에는 그 부모 디렉터리가 그대로 남아 있으므로 계속 {@code COMPLETE} 다.
 */
@Slf4j
@Component
public class DerivativeArtifactRemover {

    /** 파일 정리 결과 축 — "지웠는가" 와 "다시 시도하면 달라지는가" 를 함께 표현한다. */
    public enum Outcome {
        /** 잔존 없음(완전 정리). */
        COMPLETE,
        /** 일시적 실패(I/O·판정 실패) — 다음 tick 이 재시도하면 풀릴 수 있다. */
        RETRYABLE,
        /**
         * 재시도해도 동일한 구조적 잔존 — 심링크·비정규 항목처럼 <b>우리가 지우지 않기로 한</b> 항목이
         * 남아 있다. 사람이 확인해 수동 정리해야 하며, 자동 재시도는 자원만 태운다.
         */
        UNRESOLVABLE;

        /** 두 미완료 사유를 합칠 때 "사람 개입 필요" 가 우선한다(한 항목이라도 걸리면 자동 수렴 불가). */
        static Outcome merge(Outcome a, Outcome b) {
            if (a == UNRESOLVABLE || b == UNRESOLVABLE) {
                return UNRESOLVABLE;
            }
            if (a == RETRYABLE || b == RETRYABLE) {
                return RETRYABLE;
            }
            return COMPLETE;
        }
    }

    /** 파생 비디오가 놓일 수 있는 종류 세그먼트 — 그 외 위치는 삭제 대상이 아니다. */
    private static final Set<String> DERIVATIVE_VIDEO_KINDS =
            Set.of(StorageSubtreePolicy.SEG_AUGMENT, StorageSubtreePolicy.SEG_RESOLUTION);

    /** 파생 비디오 축의 부모 위치(관측성 프로브 대상) — {@code {base}/videos}. */
    private static final String VIDEO_PARENT_DIR = StorageSubtreePolicy.SEG_VIDEOS;

    /** 파생 프레임 축의 부모 위치(관측성 프로브 대상) — {@code {base}/frames/deid}. */
    private static final String FRAME_PARENT_DIR =
            StorageSubtreePolicy.SEG_FRAMES + "/" + StorageSubtreePolicy.SEG_DEID;

    private final String deidentifiedPath;

    public DerivativeArtifactRemover(
            @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String deidentifiedPath) {
        this.deidentifiedPath = deidentifiedPath;
    }

    /**
     * 파생 1건의 생성 파일을 지운다.
     *
     * @param rawSn       파생 RAW_SN (프레임 디렉터리 {@code frames/deid/{rawSn}} 산정 기준)
     * @param videoPath   삭제 직전 비석에 기록한 파생 비디오 경로(nullable — 없으면 비디오 삭제 대상 없음)
     * @return {@link Outcome} — 완전 정리 / 재시도 가능 / 사람 개입 필요
     */
    public Outcome remove(long rawSn, String videoPath) {
        Path base;
        Path realBase;
        try {
            base = Paths.get(deidentifiedPath).toAbsolutePath().normalize();
            realBase = base.toRealPath();
        } catch (IOException | RuntimeException e) {
            log.warn("[Augment][Discard] 저장소 base 해석 실패 — 파일 삭제 skip rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
            return Outcome.RETRYABLE; // 마운트 복구로 풀릴 수 있다.
        }
        Outcome video = removeVideo(base, realBase, rawSn, videoPath);
        Outcome frames = removeFrames(base, realBase, rawSn);
        return Outcome.merge(video, frames);
    }

    /**
     * <b>판정 → 결과 단일 매핑</b> (FIX-A · FIX-B). 두 축(비디오·프레임)이 이 표 하나만 쓴다.
     *
     * <p>축은 단 하나 — <b>"재시도하면 달라질 수 있는가"</b>. 경로 값·파일시스템 구조가 그대로인 한
     * 같은 답이 나오는 판정은 자동 재시도가 자원만 태우므로 사람 개입 축으로 보내고, 환경(마운트·권한)이
     * 나아지면 달라질 수 있는 판정만 재시도 큐에 남긴다.
     *
     * @param verdict            {@link StorageSubtreePolicy} 판정 코드
     * @param locationObservable 대상이 놓이는 부모 디렉터리가 실제로 보이는가 — {@code MISSING} 을
     *                           "이미 지워짐(성공)" 으로 읽어도 되는지의 유일한 근거(FIX-B)
     */
    static Outcome classify(StorageSubtreePolicy.Verdict verdict, boolean locationObservable) {
        return switch (verdict) {
            case OK -> Outcome.COMPLETE;
            // 부모 위치가 보일 때만 "이미 없음 = 멱등 성공". 안 보이면 마운트 이탈일 수 있어 성공이 아니다.
            case MISSING -> locationObservable ? Outcome.COMPLETE : Outcome.RETRYABLE;
            // 일시적 — 권한 순단·NFS ESTALE·마운트 flap 로 실경로 해석만 실패한 상태.
            case REALPATH_FAILED -> Outcome.RETRYABLE;
            // 구조적 — 규약 밖 경로·심링크·비정규 항목. 우리가 <b>의도적으로</b> 지우지 않는다.
            // (BLANK 는 이 클래스에서 호출 전에 걸러지지만, 표를 전수로 유지해 새 판정 추가 시
            //  컴파일 단계에서 드러나게 한다.)
            case BLANK, OUTSIDE_BASE, NOT_REGULAR_FILE, OUTSIDE_DEID_SUBTREE -> Outcome.UNRESOLVABLE;
        };
    }

    /**
     * 원시 {@link IOException} 도 <b>같은 표</b>를 통과시킨다 — 디렉터리 실경로 해석 실패·삭제 실패·순회
     * 실패는 모두 {@link StorageSubtreePolicy.Verdict#REALPATH_FAILED} 과 동일한 "환경이 나아지면 달라질
     * 수 있는" 축이다. 축마다 따로 판단하면 형제 경로가 같은 원인을 정반대로 분류한다(FIX-A 의 원인).
     */
    static Outcome classifyIoFailure(boolean locationObservable) {
        return classify(StorageSubtreePolicy.Verdict.REALPATH_FAILED, locationObservable);
    }

    /**
     * 이 축의 대상을 "이미 없다" 고 단정해도 되는가 — 대상이 놓이는 <b>부모 디렉터리</b>가 실제로
     * 디렉터리로 보이는가 (FIX-B).
     *
     * <p>마운트가 빠지면 스텁 디렉터리만 남아 하위가 통째로 비고, 부모 권한이 막히면 하위 존재 확인이
     * 통째로 false 가 된다 — 두 경우 모두 "비어 보인다" 를 성공으로 단정하면 안 되는 상황이다.
     * 반대로 정상 삭제 후 재시도에서는 이 부모 디렉터리가 그대로 남아 있어 멱등 성공으로 닫힌다
     * (우리는 파생별 디렉터리까지만 지우고 그 부모는 건드리지 않는다).
     */
    private boolean targetLocationObservable(Path base, String relativeParent) {
        try {
            return Files.isDirectory(base.resolve(relativeParent));
        } catch (RuntimeException e) {
            return false; // 판정 불가 = 관측 불가(fail-safe: 성공으로 닫지 않는다)
        }
    }

    /** 파생 비디오 파일 1개 삭제 — 실경로 판정 + 파생 전용 위치 재확인 후에만 지운다. */
    private Outcome removeVideo(Path base, Path realBase, long rawSn, String videoPath) {
        if (videoPath == null || videoPath.isBlank()) {
            return Outcome.COMPLETE; // 기록이 없으면 지울 대상도 없다(그랜드퍼더링/미생성).
        }
        boolean observable = targetLocationObservable(realBase, VIDEO_PARENT_DIR);
        StorageSubtreePolicy.Verification verification =
                StorageSubtreePolicy.verifyDeidentifiedFile(base, videoPath);
        if (!verification.ok()) {
            Outcome outcome = classify(verification.verdict(), observable);
            if (outcome != Outcome.COMPLETE) {
                log.warn("[Augment][Discard] 비디오 삭제 미완료 rawSn={} verdict={} outcome={} "
                        + "locationObservable={}", rawSn, verification.verdict(), outcome, observable);
            }
            return outcome;
        }
        Path real = verification.path();
        if (!isDerivativeVideo(realBase, real, rawSn)) {
            log.warn("[Augment][Discard] 파생 전용 비디오 경로가 아님 — 삭제 skip(사람 확인 필요) rawSn={}", rawSn);
            return classify(StorageSubtreePolicy.Verdict.OUTSIDE_DEID_SUBTREE, observable);
        }
        try {
            Files.deleteIfExists(real);
        } catch (IOException e) {
            log.warn("[Augment][Discard] 비디오 삭제 실패(재시도 대상) rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
            return classifyIoFailure(observable);
        }
        // 파생 전용 디렉터리({parent}/{rawSn})가 비었으면 함께 정리한다(실패는 무시 — 잔존 판정 대상 아님).
        deleteEmptyDirQuietly(real.getParent());
        return Outcome.COMPLETE;
    }

    /**
     * 실경로가 {@code {base}/videos/{augment|resolution}/{부모}/{rawSn}/…} 인지 <b>세그먼트 단위</b>로 확인.
     * 문자열 접두 비교를 쓰지 않는다(부분 일치로 이웃 디렉터리가 통과한다).
     */
    private boolean isDerivativeVideo(Path realBase, Path real, long rawSn) {
        if (real == null || !real.startsWith(realBase)) {
            return false;
        }
        Path rel = realBase.relativize(real);
        if (rel.getNameCount() < 5) {
            return false; // videos/{kind}/{parent}/{rawSn}/{file}
        }
        return StorageSubtreePolicy.SEG_VIDEOS.equals(rel.getName(0).toString())
                && DERIVATIVE_VIDEO_KINDS.contains(rel.getName(1).toString())
                && String.valueOf(rawSn).equals(rel.getName(3).toString());
    }

    /** 파생 프레임 디렉터리 재귀 삭제 — 심링크는 따라가지도 지우지도 않는다. */
    private Outcome removeFrames(Path base, Path realBase, long rawSn) {
        boolean observable = targetLocationObservable(realBase, FRAME_PARENT_DIR);
        Path dir = base.resolve(StorageSubtreePolicy.deidFramesDir(rawSn)).normalize();
        if (!Files.exists(dir)) {
            // FIX-B — "이미 없음" 인지 "부모(frames/deid)에 닿지 못하는 것" 인지 구분한다.
            Outcome outcome = classify(StorageSubtreePolicy.Verdict.MISSING, observable);
            if (outcome != Outcome.COMPLETE) {
                log.warn("[Augment][Discard] 프레임 부모 디렉터리가 관측되지 않음(마운트 이탈 의심) — "
                        + "정리 완료로 닫지 않고 재시도 rawSn={}", rawSn);
            }
            return outcome;
        }
        if (!StorageSubtreePolicy.isDeidentifiedArtifact(base, dir)) {
            log.warn("[Augment][Discard] 프레임 디렉터리가 허용 서브트리 밖 — 삭제 skip(사람 확인 필요) rawSn={}",
                    rawSn);
            return classify(StorageSubtreePolicy.Verdict.OUTSIDE_DEID_SUBTREE, observable);
        }
        Path realDir;
        try {
            realDir = dir.toRealPath();
        } catch (IOException e) {
            log.warn("[Augment][Discard] 프레임 디렉터리 실경로 해석 실패 — 삭제 skip rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
            return classifyIoFailure(observable); // 마운트 순단이면 다음 tick 에 풀린다.
        }
        // 디렉터리 자체가 다른 곳(원본 프레임 등)을 가리키는 심링크면 지우지 않는다.
        if (!StorageSubtreePolicy.isDeidentifiedArtifact(realBase, realDir)
                || !String.valueOf(rawSn).equals(realDir.getFileName().toString())) {
            log.warn("[Augment][Discard] 프레임 디렉터리 실경로가 파생 전용 위치가 아님 — "
                    + "삭제 skip(사람 확인 필요) rawSn={}", rawSn);
            return classify(StorageSubtreePolicy.Verdict.OUTSIDE_DEID_SUBTREE, observable);
        }
        return deleteTree(realDir, rawSn, observable);
    }

    /**
     * 실경로 기준 하위 트리 삭제. 심링크·비정규 항목은 <b>지우지 않고</b> 사람 개입 필요로 표시하며,
     * 그로 인해 비지 않은 디렉터리도 그대로 남긴다(재시도해도 결과가 같으므로 큐에 두지 않는다).
     */
    private Outcome deleteTree(Path realDir, long rawSn, boolean observable) {
        final Outcome[] result = {Outcome.COMPLETE};
        // 트리 안에서 나오는 판정도 같은 표를 통과한다(FIX-A) — 축·위치마다 매핑이 갈리지 않게 한다.
        final Outcome structural =
                classify(StorageSubtreePolicy.Verdict.NOT_REGULAR_FILE, observable);
        final Outcome transientIo = classifyIoFailure(observable);
        try {
            Files.walkFileTree(realDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) {
                        // 우리가 만들지 않은 항목(심링크·특수 파일)은 건드리지 않는다 — 링크 대상(원본
                        // 프레임)이 이 트리 밖에 있을 수 있고, 링크를 지우는 것도 우리 책임이 아니다.
                        // 재시도해도 같은 판정이므로 재시도 큐가 아니라 사람 개입 축으로 보낸다.
                        result[0] = Outcome.merge(result[0], structural);
                        log.warn("[Augment][Discard] 파생 프레임 트리에 비정규 항목 — "
                                + "삭제 skip(사람 확인 필요) rawSn={}", rawSn);
                        return FileVisitResult.CONTINUE;
                    }
                    if (!file.startsWith(realDir)) {
                        result[0] = Outcome.merge(result[0], structural);
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        result[0] = Outcome.merge(result[0], transientIo);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    result[0] = Outcome.merge(result[0], transientIo);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exc) {
                    try {
                        Files.deleteIfExists(directory);
                    } catch (IOException e) {
                        // 남은 항목 때문에 비지 않은 디렉터리 — 잔존 사유는 위에서 이미 기록됐다.
                        // 사유가 없는데 여기서만 실패했다면 권한·경합이므로 재시도 대상이다.
                        result[0] = Outcome.merge(result[0], transientIo);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[Augment][Discard] 프레임 트리 순회 실패 rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
            return classifyIoFailure(observable);
        }
        return result[0];
    }

    private void deleteEmptyDirQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // 비어있지 않으면 남긴다 — 잔존 판정 대상 아님(파생 비디오 자체는 지워졌다).
        }
    }
}
