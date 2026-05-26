package org.tauasa.apps.rtlsdr.web;

import org.tauasa.apps.rtlsdr.model.DeviceInfo;
import org.tauasa.apps.rtlsdr.model.SdrState;
import org.tauasa.apps.rtlsdr.service.RtlSdrService;
import org.tauasa.apps.rtlsdr.tcp.RtlTcpServer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for RTL-SDR control.
 *
 * <pre>
 * GET  /api/devices              — list all attached devices
 * GET  /api/device/state         — current device state snapshot
 * POST /api/device/open          — open device by index
 * POST /api/device/close         — close device
 * PUT  /api/device/frequency     — set center frequency
 * PUT  /api/device/sample-rate   — set sample rate
 * PUT  /api/device/gain          — set manual gain
 * PUT  /api/device/gain-mode     — set auto/manual gain mode
 * PUT  /api/device/agc           — set RTL2832 AGC
 * PUT  /api/device/freq-correction — set PPM correction
 * PUT  /api/device/direct-sampling — set direct sampling mode
 * PUT  /api/device/offset-tuning — toggle offset tuning
 * PUT  /api/device/bias-tee      — toggle bias-tee
 * PUT  /api/device/bandwidth     — set tuner IF bandwidth
 * POST /api/device/stream/start  — start IQ streaming
 * POST /api/device/stream/stop   — stop IQ streaming
 * POST /api/tcp/start            — start TCP server
 * POST /api/tcp/stop             — stop TCP server
 * GET  /api/tcp/status           — TCP server status
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class SdrController {

    private final RtlSdrService sdrService;
    private final RtlTcpServer  tcpServer;

    public SdrController(RtlSdrService sdrService, RtlTcpServer tcpServer) {
        this.sdrService = sdrService;
        this.tcpServer  = tcpServer;
    }

    // =========================================================================
    // Device enumeration
    // =========================================================================

    @GetMapping("/devices")
    public List<DeviceInfo> listDevices() {
        return sdrService.listDevices();
    }

    @GetMapping("/device/state")
    public SdrState getState() {
        return sdrService.getState();
    }

    // =========================================================================
    // Open / close
    // =========================================================================

    @PostMapping("/device/open")
    public ResponseEntity<Map<String, Object>> openDevice(
            @RequestBody @Valid OpenRequest req) {
        sdrService.open(req.index());
        return ok("Opened device " + req.index());
    }

    @PostMapping("/device/close")
    public ResponseEntity<Map<String, Object>> closeDevice() {
        sdrService.close();
        return ok("Device closed");
    }

    // =========================================================================
    // Configuration
    // =========================================================================

    @PutMapping("/device/frequency")
    public ResponseEntity<Map<String, Object>> setFrequency(
            @RequestBody @Valid FrequencyRequest req) {
        sdrService.setFrequency(req.hz());
        return ok("Center frequency set to " + req.hz() + " Hz");
    }

    @PutMapping("/device/sample-rate")
    public ResponseEntity<Map<String, Object>> setSampleRate(
            @RequestBody @Valid SampleRateRequest req) {
        sdrService.setSampleRate(req.hz());
        return ok("Sample rate set to " + req.hz() + " Hz");
    }

    @PutMapping("/device/gain")
    public ResponseEntity<Map<String, Object>> setGain(
            @RequestBody @Valid GainRequest req) {
        sdrService.setGain(req.tenthsDb());
        return ok("Gain set to " + req.tenthsDb() + " (tenths dB)");
    }

    @PutMapping("/device/gain-mode")
    public ResponseEntity<Map<String, Object>> setGainMode(
            @RequestBody @Valid BooleanParam req) {
        sdrService.setAutoGain(req.enabled());
        return ok("Auto-gain: " + req.enabled());
    }

    @PutMapping("/device/agc")
    public ResponseEntity<Map<String, Object>> setAgc(
            @RequestBody @Valid BooleanParam req) {
        sdrService.setAgcMode(req.enabled());
        return ok("AGC: " + req.enabled());
    }

    @PutMapping("/device/freq-correction")
    public ResponseEntity<Map<String, Object>> setFreqCorrection(
            @RequestBody @Valid PpmRequest req) {
        sdrService.setFreqCorrection(req.ppm());
        return ok("Frequency correction set to " + req.ppm() + " ppm");
    }

    @PutMapping("/device/direct-sampling")
    public ResponseEntity<Map<String, Object>> setDirectSampling(
            @RequestBody @Valid DirectSamplingRequest req) {
        sdrService.setDirectSampling(req.mode());
        return ok("Direct sampling mode: " + req.mode());
    }

    @PutMapping("/device/offset-tuning")
    public ResponseEntity<Map<String, Object>> setOffsetTuning(
            @RequestBody @Valid BooleanParam req) {
        sdrService.setOffsetTuning(req.enabled());
        return ok("Offset tuning: " + req.enabled());
    }

    @PutMapping("/device/bias-tee")
    public ResponseEntity<Map<String, Object>> setBiasTee(
            @RequestBody @Valid BooleanParam req) {
        sdrService.setBiasTee(req.enabled());
        return ok("Bias-tee: " + req.enabled());
    }

    @PutMapping("/device/bandwidth")
    public ResponseEntity<Map<String, Object>> setBandwidth(
            @RequestBody @Valid BandwidthRequest req) {
        sdrService.setTunerBandwidth(req.hz());
        return ok("Tuner bandwidth set to " + req.hz() + " Hz (0 = auto)");
    }

    // =========================================================================
    // Streaming
    // =========================================================================

    @PostMapping("/device/stream/start")
    public ResponseEntity<Map<String, Object>> startStream() {
        sdrService.startStreaming();
        return ok("IQ streaming started");
    }

    @PostMapping("/device/stream/stop")
    public ResponseEntity<Map<String, Object>> stopStream() {
        sdrService.stopStreaming();
        return ok("IQ streaming stopped");
    }

    // =========================================================================
    // TCP server control
    // =========================================================================

    @PostMapping("/tcp/start")
    public ResponseEntity<Map<String, Object>> startTcp() {
        tcpServer.start();
        return ok("TCP server started on port " + tcpServer.getPort());
    }

    @PostMapping("/tcp/stop")
    public ResponseEntity<Map<String, Object>> stopTcp() {
        tcpServer.stop();
        return ok("TCP server stopped");
    }

    @GetMapping("/tcp/status")
    public Map<String, Object> tcpStatus() {
        return Map.of(
                "running", tcpServer.isRunning(),
                "port",    tcpServer.getPort()
        );
    }

    // =========================================================================
    // Exception handling
    // =========================================================================

    @ExceptionHandler({ RtlSdrService.RtlSdrException.class, IllegalStateException.class })
    public ResponseEntity<Map<String, Object>> handleSdrError(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error",   ex.getClass().getSimpleName(),
                "message", ex.getMessage()
        ));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static ResponseEntity<Map<String, Object>> ok(String message) {
        return ResponseEntity.ok(Map.of("status", "ok", "message", message));
    }

    // =========================================================================
    // Request records
    // =========================================================================

    record OpenRequest(@Min(0) int index) {}

    record FrequencyRequest(
            @Min(100_000) @Max(1_766_000_000) long hz) {}

    record SampleRateRequest(
            @Min(225_001) @Max(3_200_000) long hz) {}

    record GainRequest(
            @Min(-100) @Max(500) int tenthsDb) {}

    record PpmRequest(
            @Min(-100) @Max(100) int ppm) {}

    record DirectSamplingRequest(
            @Min(0) @Max(2) int mode) {}

    record BandwidthRequest(
            @Min(0) int hz) {}

    record BooleanParam(boolean enabled) {}
}
