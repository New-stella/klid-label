package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.dto.PortalTusCreateCommand;
import kr.co.cudo.authoring.portal.entity.LsPortalTusUpload;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.event.PortalVideoUploadedEvent;
import kr.co.cudo.authoring.portal.repository.LsPortalTusUploadRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import kr.co.cudo.authoring.portal.service.PortalVideoProbe;
import kr.co.cudo.authoring.portal.service.PortalVideoUploadService;
import kr.co.cudo.authoring.portal.service.PortalVideoUploadTxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털 TUS 영상 업로드 서비스 단위 테스트 — 완료·멱등·IDOR·매직바이트·오디오전용 방어.
 *
 * <p>리포지토리는 in-memory Map, 파일시스템은 {@link TempDir} 실디스크, ffprobe 는 stub 으로
 * 대체해 외부 의존 없이 결정적으로 검증한다.
 */
class PortalVideoUploadServiceTest {

    private static final String OWNER = "portal-user-1";
    private static final long MAX_SIZE = 5_368_709_120L;
    /** mp4 ISO BMFF 시그니처(4~8바이트 ftyp, brand mp42). */
    private static final byte[] MP4_HEAD = new byte[]{
            0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0};

    @TempDir
    Path storageDir;

    private InMemoryTusRepo tusRepository;
    private LsPortalUldRepository uldRepository;
    private ApplicationEventPublisher eventPublisher;
    private PortalVideoUploadService service;
    private final AtomicLong uldSnSeq = new AtomicLong(500);

    @BeforeEach
    void setUp() {
        tusRepository = new InMemoryTusRepo();
        uldRepository = mock(LsPortalUldRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(uldRepository.save(any(LsPortalUld.class))).thenAnswer(inv -> {
            LsPortalUld uld = inv.getArgument(0);
            if (uld.getUldSn() == null) {
                setUldSn(uld, uldSnSeq.incrementAndGet());
            }
            return uld;
        });
        // 비디오 스트림 존재 + 60초 + 30fps 반환 stub.
        PortalVideoProbe probe = path -> new PortalVideoProbe.Result(true, 60.0, 30.0);
        service = build(probe);
    }

    private PortalVideoUploadService build(PortalVideoProbe probe) {
        PortalUploadProperties props = new PortalUploadProperties(
                MAX_SIZE, List.of("mp4", "mov", "avi"), storageDir.toString(),
                List.of("jpg", "jpeg", "png"), 20_971_520L, 50, 2000);
        PortalVideoUploadTxService txService = new PortalVideoUploadTxService(
                tusRepository, uldRepository, props, eventPublisher, 16L * 1024 * 1024);
        return new PortalVideoUploadService(tusRepository, props, probe, txService);
    }

    private PortalTusCreateCommand cmd(long len) {
        return new PortalTusCreateCommand(len, "myvideo.mp4");
    }

    // ======================== 완료 + 이벤트 ========================

    @Test
    @DisplayName("TUS_업로드_완료시_UPLOADED_상태와_이벤트_발행")
    void completesToUploadedAndPublishesEvent() {
        byte[] full = mp4(16);
        UUID id = service.createSession(OWNER, cmd(16));

        var r = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full), 16);

        assertThat(r.completed()).isTrue();
        assertThat(r.uldSn()).isNotNull();
        // LS_PORTAL_ULD 은 VIDEO + UPLOADED 로 생성됨 (createVideo 팩토리).
        verify(uldRepository).save(any(LsPortalUld.class));
        verify(eventPublisher).publishEvent(eq(new PortalVideoUploadedEvent(r.uldSn())));
        assertThat(tusRepository.findById(id).orElseThrow().isCompleted()).isTrue();
    }

    @Test
    @DisplayName("완료_PATCH_중복시_이벤트_1회만_발행")
    void duplicateCompletionPublishesEventOnce() {
        byte[] full = mp4(16);
        UUID id = service.createSession(OWNER, cmd(16));
        var first = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full), 16);
        assertThat(first.completed()).isTrue();

        // 마지막 청크 재전송 — 이미 COMPLETED → 멱등 응답, 이벤트 재발행 없음.
        var again = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full), 16);

        assertThat(again.completed()).isTrue();
        assertThat(again.uldSn()).isEqualTo(first.uldSn());
        verify(eventPublisher, times(1)).publishEvent(any(PortalVideoUploadedEvent.class));
    }

    // ======================== IDOR (#3) ========================

    @Test
    @DisplayName("타사용자_TUS_세션에_PATCH시_403")
    void nonOwnerPatchForbidden() {
        byte[] full = mp4(16);
        UUID id = service.createSession(OWNER, cmd(16));
        assertThatThrownBy(() -> service.appendChunk(id, "intruder", 0,
                new ByteArrayInputStream(full), 16))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("타사용자_TUS_세션에_HEAD_DELETE도_403")
    void nonOwnerHeadDeleteForbidden() {
        UUID id = service.createSession(OWNER, cmd(16));
        assertThatThrownBy(() -> service.getForOwner(id, "intruder"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.cancel(id, "intruder"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("취소된_세션에_PATCH시_거부")
    void cancelledSessionPatchRejected() {
        byte[] full = mp4(16);
        UUID id = service.createSession(OWNER, cmd(16));
        service.cancel(id, OWNER);

        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full), 16))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ======================== 완료 검증 (#6) ========================

    @Test
    @DisplayName("영상_매직바이트_불일치시_완료_거부되고_임시파일_삭제")
    void magicByteMismatchRejectsAndDeletes() {
        byte[] bogus = new byte[16];
        for (int i = 0; i < 16; i++) bogus[i] = 'X';
        UUID id = service.createSession(OWNER, cmd(16));

        // item 6: 입력 검증 실패 → 400(INVALID_INPUT).
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(bogus), 16))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        String filePath = tusRepository.findById(id).orElseThrow().getFilePathNm();
        assertThat(Files.exists(Path.of(filePath))).isFalse();
    }

    @Test
    @DisplayName("오디오만_있는_파일_완료시_400")
    void audioOnlyRejected() {
        // 매직바이트는 통과(mp4 컨테이너)하지만 비디오 스트림이 없는 파일 → 400(입력 검증 실패).
        PortalVideoProbe audioOnly = path -> new PortalVideoProbe.Result(false, 30.0, 0.0);
        service = build(audioOnly);
        byte[] full = mp4(16);
        UUID id = service.createSession(OWNER, cmd(16));

        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full), 16))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        String filePath = tusRepository.findById(id).orElseThrow().getFilePathNm();
        assertThat(Files.exists(Path.of(filePath))).isFalse();
    }

    @Test
    @DisplayName("검증_거부시_세션이_CANCELLED로_영속되고_파일_삭제")
    void rejectionPersistsCancelledAndDeletesFile() {
        // security M-2: 거부는 독립 tx 로 CANCELLED 를 커밋 — 400 이 롤백시켜 IN_PROGRESS 고착되지 않음.
        PortalVideoProbe audioOnly = path -> new PortalVideoProbe.Result(false, 30.0, 0.0);
        service = build(audioOnly);
        byte[] full = mp4(16);
        UUID id = service.createSession(OWNER, cmd(16));

        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full), 16))
                .isInstanceOf(CustomException.class);

        LsPortalTusUpload session = tusRepository.findById(id).orElseThrow();
        assertThat(session.isCancelled()).isTrue();
        assertThat(session.isCompleted()).isFalse();
        assertThat(Files.exists(Path.of(session.getFilePathNm()))).isFalse();
    }

    // ======================== 경계/상한 ========================

    @Test
    @DisplayName("UploadLength가_maxFileSize초과시_413")
    void uploadLengthTooLarge() {
        assertThatThrownBy(() -> service.createSession(OWNER, cmd(MAX_SIZE + 1)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    @DisplayName("허용안된_확장자_세션생성시_400")
    void invalidExtensionRejected() {
        assertThatThrownBy(() -> service.createSession(OWNER, new PortalTusCreateCommand(16, "evil.exe")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("경로순회_파일명이어도_저장은_UUID강제_storage내부")
    void pathTraversalForcedUuid() {
        UUID id = service.createSession(OWNER, new PortalTusCreateCommand(16, "../../../etc/passwd.mp4"));
        String filePath = tusRepository.findById(id).orElseThrow().getFilePathNm();
        assertThat(filePath).contains(id.toString());
        assertThat(filePath).doesNotContain("etc/passwd");
        assertThat(Path.of(filePath).normalize().startsWith(storageDir.toAbsolutePath().normalize())).isTrue();
    }

    // ======================== 헬퍼 ========================

    private static byte[] mp4(int size) {
        byte[] out = new byte[Math.max(size, MP4_HEAD.length)];
        System.arraycopy(MP4_HEAD, 0, out, 0, Math.min(MP4_HEAD.length, out.length));
        return out;
    }

    private static void setUldSn(LsPortalUld uld, long val) {
        try {
            Field f = LsPortalUld.class.getDeclaredField("uldSn");
            f.setAccessible(true);
            f.set(uld, val);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 최소 in-memory JpaRepository 구현 — 테스트에 필요한 메서드만. */
    static class InMemoryTusRepo implements LsPortalTusUploadRepository {
        final Map<UUID, LsPortalTusUpload> store = new HashMap<>();

        @Override
        public long countByPortalUserNoAndSttsCd(String portalUserNo, String sttsCd) {
            return store.values().stream()
                    .filter(u -> portalUserNo.equals(u.getPortalUserNo()) && sttsCd.equals(u.getSttsCd()))
                    .count();
        }

        @Override
        public Optional<LsPortalTusUpload> findByUldIdForUpdate(UUID uldId) {
            return Optional.ofNullable(store.get(uldId));
        }

        @Override
        public int markCompletedIfInProgress(UUID uldId, Long uldSn, LocalDateTime now) {
            LsPortalTusUpload u = store.get(uldId);
            if (u == null || !LsPortalTusUpload.STTS_IN_PROGRESS.equals(u.getSttsCd())) {
                return 0;
            }
            u.markCompleted(uldSn);
            return 1;
        }

        @Override
        public int deleteExpiredInProgress(UUID uldId) {
            LsPortalTusUpload u = store.get(uldId);
            if (u == null || !LsPortalTusUpload.STTS_IN_PROGRESS.equals(u.getSttsCd())) {
                return 0;
            }
            store.remove(uldId);
            return 1;
        }

        @Override
        public List<LsPortalTusUpload> findExpired(LocalDateTime now) {
            return store.values().stream()
                    .filter(u -> LsPortalTusUpload.STTS_IN_PROGRESS.equals(u.getSttsCd())
                            && u.getExpiresAt().isBefore(now))
                    .toList();
        }

        @Override
        public <S extends LsPortalTusUpload> S save(S entity) {
            store.put(entity.getUldId(), entity);
            return entity;
        }

        @Override
        public <S extends LsPortalTusUpload> S saveAndFlush(S entity) {
            store.put(entity.getUldId(), entity);
            return entity;
        }

        @Override
        public Optional<LsPortalTusUpload> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public void delete(LsPortalTusUpload entity) {
            store.remove(entity.getUldId());
        }

        // ----- 미사용 JpaRepository 메서드 -----
        @Override public List<LsPortalTusUpload> findAll() { throw new UnsupportedOperationException(); }
        @Override public List<LsPortalTusUpload> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public List<LsPortalTusUpload> findAllById(Iterable<UUID> ids) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsPortalTusUpload> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void flush() { }
        @Override public <S extends LsPortalTusUpload> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<LsPortalTusUpload> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public LsPortalTusUpload getOne(UUID id) { throw new UnsupportedOperationException(); }
        @Override public LsPortalTusUpload getById(UUID id) { throw new UnsupportedOperationException(); }
        @Override public LsPortalTusUpload getReferenceById(UUID id) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsPortalTusUpload> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsPortalTusUpload> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsPortalTusUpload> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsPortalTusUpload> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsPortalTusUpload> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsPortalTusUpload> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsPortalTusUpload, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<LsPortalTusUpload> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(UUID id) { return store.containsKey(id); }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(UUID id) { store.remove(id); }
        @Override public void deleteAllById(Iterable<? extends UUID> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends LsPortalTusUpload> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { store.clear(); }
    }
}
