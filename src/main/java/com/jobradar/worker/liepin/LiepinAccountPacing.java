package com.jobradar.worker.liepin;

/**
 * 猎聘账户级节奏状态。
 * 多条任务共用同一账户时，最近动作和成功发送批次必须连续计算。
 */
public final class LiepinAccountPacing {
    public enum AdaptiveMode {
        NORMAL,
        RISK_REST,
        PROBE,
        RECOVERY,
        MANUAL_REQUIRED
    }

    public enum RiskDisposition {
        LONG_REST,
        MANUAL_REQUIRED
    }

    private long lastActivityAt = -1L;
    private int successfulSendCount;
    private AdaptiveMode adaptiveMode = AdaptiveMode.NORMAL;
    private String riskReason = "";
    private int riskSignalCount;
    private int probeSuccessfulSends;
    private int recoverySuccessfulSends;
    private boolean riskRestPending;

    public synchronized long lastActivityAt() {
        return lastActivityAt;
    }

    public synchronized void recordActivity(long timestamp) {
        lastActivityAt = timestamp;
    }

    public synchronized int incrementSuccessfulSendCount() {
        successfulSendCount++;
        return successfulSendCount;
    }

    public synchronized int successfulSendCount() {
        return successfulSendCount;
    }

    public synchronized RiskDisposition recordRiskSignal(String reason, boolean automaticRecoveryAllowed) {
        riskReason = normalizeReason(reason);
        riskSignalCount++;
        riskRestPending = false;
        if (!automaticRecoveryAllowed || adaptiveMode != AdaptiveMode.NORMAL) {
            adaptiveMode = AdaptiveMode.MANUAL_REQUIRED;
            return RiskDisposition.MANUAL_REQUIRED;
        }
        adaptiveMode = AdaptiveMode.RISK_REST;
        probeSuccessfulSends = 0;
        recoverySuccessfulSends = 0;
        riskRestPending = true;
        return RiskDisposition.LONG_REST;
    }

    public synchronized void beginProbe() {
        if (adaptiveMode != AdaptiveMode.RISK_REST) {
            return;
        }
        adaptiveMode = AdaptiveMode.PROBE;
        riskRestPending = false;
        probeSuccessfulSends = 0;
        recoverySuccessfulSends = 0;
    }

    public synchronized void recordSuccessfulSend() {
        if (adaptiveMode == AdaptiveMode.PROBE) {
            probeSuccessfulSends++;
            if (probeSuccessfulSends >= LiepinConfig.ADAPTIVE_PROBE_MAX_SUCCESSFUL_SENDS) {
                adaptiveMode = AdaptiveMode.RECOVERY;
                recoverySuccessfulSends = 0;
            }
        } else if (adaptiveMode == AdaptiveMode.RECOVERY) {
            recoverySuccessfulSends++;
            if (recoverySuccessfulSends >= LiepinConfig.ADAPTIVE_RECOVERY_MAX_SUCCESSFUL_SENDS) {
                adaptiveMode = AdaptiveMode.NORMAL;
                riskReason = "";
                probeSuccessfulSends = 0;
                recoverySuccessfulSends = 0;
            }
        }
    }

    public synchronized void requireManual(String reason) {
        adaptiveMode = AdaptiveMode.MANUAL_REQUIRED;
        riskRestPending = false;
        riskReason = normalizeReason(reason);
    }

    /** 显式重新启动普通任务时清除自动恢复阶段，不影响账户批次计数和最近动作时间。 */
    public synchronized void resetForManualRun() {
        adaptiveMode = AdaptiveMode.NORMAL;
        riskReason = "";
        riskRestPending = false;
        probeSuccessfulSends = 0;
        recoverySuccessfulSends = 0;
    }

    public synchronized AdaptiveMode adaptiveMode() {
        return adaptiveMode;
    }

    public synchronized String riskReason() {
        return riskReason;
    }

    public synchronized int riskSignalCount() {
        return riskSignalCount;
    }

    public synchronized int probeSuccessfulSends() {
        return probeSuccessfulSends;
    }

    public synchronized int recoverySuccessfulSends() {
        return recoverySuccessfulSends;
    }

    public synchronized boolean isRiskRestPending() {
        return riskRestPending;
    }

    public synchronized boolean isManualRequired() {
        return adaptiveMode == AdaptiveMode.MANUAL_REQUIRED;
    }

    public synchronized java.util.Map<String, Object> snapshot() {
        return java.util.Map.ofEntries(
                java.util.Map.entry("mode", adaptiveMode.name()),
                java.util.Map.entry("riskReason", riskReason),
                java.util.Map.entry("riskSignalCount", riskSignalCount),
                java.util.Map.entry("probeSuccessfulSends", probeSuccessfulSends),
                java.util.Map.entry("probeLimit", LiepinConfig.ADAPTIVE_PROBE_MAX_SUCCESSFUL_SENDS),
                java.util.Map.entry("recoverySuccessfulSends", recoverySuccessfulSends),
                java.util.Map.entry("recoveryLimit", LiepinConfig.ADAPTIVE_RECOVERY_MAX_SUCCESSFUL_SENDS),
                java.util.Map.entry("successfulSendCount", successfulSendCount)
        );
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "平台风控信号" : reason.trim();
    }
}
