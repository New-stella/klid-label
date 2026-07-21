package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 프레임 리사이즈 동시 실행 상한 게이트 — 자원 고갈(DoS, API4:2023) 방지용 공유 컴포넌트.
 *
 * <p>Phase 2 해상도 파생영상 확정({@code ResolutionDerivativeFinalizer})이 CPU/메모리를 크게 쓰는
 * 이미지 리스케일 루프를 실행하기 전에 슬롯을 획득한다. 단일 빈으로 공유하여 동시 리사이즈 총량을
 * 하나의 상한으로 통제한다(별도 세마포어를 서비스마다 두면 상한이 배수로 무력화됨).
 *
 * <p>공정(fair) Semaphore + 획득 타임아웃으로 무한 대기(스레드 점유)를 방지하고, 초과 시
 * {@link ErrorCode#TOO_MANY_REQUESTS}(429) 로 거부한다.
 */
@Slf4j
@Component
public class ResizeConcurrencyGate {

    private final Semaphore semaphore;
    private final long acquireTimeoutSec;
    private final int maxConcurrent;

    public ResizeConcurrencyGate(
            @Value("${authoring.resolution.resize-max-concurrent:2}") int maxConcurrent,
            @Value("${authoring.resolution.resize-acquire-timeout-sec:5}") long acquireTimeoutSec) {
        this.maxConcurrent = Math.max(maxConcurrent, 1);
        this.acquireTimeoutSec = acquireTimeoutSec;
        this.semaphore = new Semaphore(this.maxConcurrent, true);
    }

    /** 슬롯 1개 획득(블로킹, 타임아웃). 실패 시 429 로 거부. */
    public void acquire() {
        try {
            boolean acquired = semaphore.tryAcquire(acquireTimeoutSec, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[Video][ResizeGate] slot busy, rejected (max={})", maxConcurrent);
                throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                        "동시 처리 가능한 리사이즈 작업 수를 초과했습니다. 잠시 후 다시 시도해 주세요.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "리사이즈 작업 처리가 중단되었습니다.");
        }
    }

    /** 슬롯 반환. acquire 성공 후 반드시 finally 에서 호출. */
    public void release() {
        semaphore.release();
    }
}
