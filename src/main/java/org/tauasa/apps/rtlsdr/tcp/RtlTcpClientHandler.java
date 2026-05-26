package org.tauasa.apps.rtlsdr.tcp;

import org.tauasa.apps.rtlsdr.model.SdrState;
import org.tauasa.apps.rtlsdr.service.RtlSdrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Handles a single connected rtl_tcp client.
 *
 * <h2>Threading model</h2>
 * <ul>
 *   <li>The {@link Runnable#run()} method drives the <em>command reader</em> on one thread.</li>
 *   <li>A second daemon thread drains a bounded queue and writes IQ bytes to the socket.</li>
 * </ul>
 * This separation keeps the command reader responsive even when the write side is slow.
 */
public class RtlTcpClientHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RtlTcpClientHandler.class);

    /** Maximum number of IQ byte-arrays to buffer per client. Drops oldest when full. */
    private static final int QUEUE_CAPACITY = 64;

    // rtl_tcp command IDs (1-byte, from rtl-sdr.h)
    private static final int CMD_SET_FREQUENCY        = 0x01;
    private static final int CMD_SET_SAMPLE_RATE      = 0x02;
    private static final int CMD_SET_GAIN_MODE        = 0x03;
    private static final int CMD_SET_GAIN             = 0x04;
    private static final int CMD_SET_FREQ_CORRECTION  = 0x05;
    private static final int CMD_SET_IF_STAGE_GAIN    = 0x06;
    private static final int CMD_SET_TEST_MODE        = 0x07;
    private static final int CMD_SET_AGC_MODE         = 0x08;
    private static final int CMD_SET_DIRECT_SAMPLING  = 0x09;
    private static final int CMD_SET_OFFSET_TUNING    = 0x0A;
    private static final int CMD_SET_RTL_CRYSTAL      = 0x0B; // not implemented in service; log only
    private static final int CMD_SET_TUNER_CRYSTAL    = 0x0C; // not implemented in service; log only
    private static final int CMD_SET_TUNER_GAIN_INDEX = 0x0D;
    private static final int CMD_SET_BIAS_TEE         = 0x0E;

    private final Socket              socket;
    private final RtlSdrService       sdrService;
    private final Consumer<RtlTcpClientHandler> onDisconnect;
    private final AtomicBoolean       active = new AtomicBoolean(true);
    private final BlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private Consumer<byte[]>          iqConsumer;

    public RtlTcpClientHandler(Socket socket,
                                RtlSdrService sdrService,
                                Consumer<RtlTcpClientHandler> onDisconnect) {
        this.socket       = socket;
        this.sdrService   = sdrService;
        this.onDisconnect = onDisconnect;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            OutputStream out = socket.getOutputStream();

            // 1. Send dongle-info header
            SdrState state = sdrService.getState();
            byte[] header  = RtlTcpServer.buildDongleInfo(
                    state.tunerType(),
                    state.availableGains() != null ? state.availableGains().length : 0
            );
            out.write(header);
            out.flush();

            // 2. Register as an IQ consumer
            iqConsumer = data -> {
                if (!writeQueue.offer(data)) {
                    writeQueue.poll(); // drop oldest; keep newest
                    writeQueue.offer(data);
                }
            };
            sdrService.addIqConsumer(iqConsumer);

            // 3. Start IQ streaming if not already running
            if (!sdrService.isStreaming()) {
                sdrService.startStreaming();
            }

            // 4. Spin up write thread
            Thread writeThread = new Thread(
                    () -> writeLoop(out), "rtltcp-write-" + socket.getRemoteSocketAddress());
            writeThread.setDaemon(true);
            writeThread.start();

            // 5. Read 5-byte command packets until EOF
            DataInputStream in = new DataInputStream(socket.getInputStream());
            while (active.get()) {
                int cmd   = in.read();            // blocks
                if (cmd == -1) break;             // EOF / client disconnected
                int param = in.readInt();         // 4-byte big-endian
                handleCommand(cmd & 0xFF, param);
            }

        } catch (IOException e) {
            if (active.get()) {
                log.debug("Client I/O error: {}", e.getMessage());
            }
        } finally {
            cleanup();
        }
    }

    private void writeLoop(OutputStream out) {
        try {
            while (active.get()) {
                byte[] data = writeQueue.poll(200, TimeUnit.MILLISECONDS);
                if (data != null) {
                    out.write(data);
                    // Drain any additional buffered items before flushing
                    byte[] more;
                    while ((more = writeQueue.poll()) != null) {
                        out.write(more);
                    }
                    out.flush();
                }
            }
        } catch (IOException e) {
            if (active.get()) log.debug("Write loop error: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            active.set(false);
        }
    }

    private void handleCommand(int cmd, int param) {
        log.debug("TCP command 0x{} param={}", Integer.toHexString(cmd), param);
        try {
            switch (cmd) {
                case CMD_SET_FREQUENCY        -> sdrService.setFrequency(Integer.toUnsignedLong(param));
                case CMD_SET_SAMPLE_RATE      -> sdrService.setSampleRate(Integer.toUnsignedLong(param));
                case CMD_SET_GAIN_MODE        -> sdrService.setAutoGain(param == 0);
                case CMD_SET_GAIN             -> sdrService.setGain(param);
                case CMD_SET_FREQ_CORRECTION  -> sdrService.setFreqCorrection(param);
                case CMD_SET_IF_STAGE_GAIN    -> {
                    int stage = (param >> 16) & 0xFF;
                    int gain  = param & 0xFFFF;
                    sdrService.setTunerIfGain(stage, gain);
                }
                case CMD_SET_TEST_MODE        -> log.warn("Test mode not supported via TCP");
                case CMD_SET_AGC_MODE         -> sdrService.setAgcMode(param != 0);
                case CMD_SET_DIRECT_SAMPLING  -> sdrService.setDirectSampling(param);
                case CMD_SET_OFFSET_TUNING    -> sdrService.setOffsetTuning(param != 0);
                case CMD_SET_TUNER_GAIN_INDEX -> {
                    int[] gains = sdrService.getState().availableGains();
                    if (gains != null && param >= 0 && param < gains.length) {
                        sdrService.setGain(gains[param]);
                    }
                }
                case CMD_SET_BIAS_TEE         -> sdrService.setBiasTee(param != 0);
                case CMD_SET_RTL_CRYSTAL,
                     CMD_SET_TUNER_CRYSTAL    -> log.warn("Crystal adjustment (cmd 0x{}) not supported",
                                                         Integer.toHexString(cmd));
                default -> log.warn("Unknown TCP command: 0x{}", Integer.toHexString(cmd));
            }
        } catch (Exception e) {
            log.warn("Command 0x{} failed: {}", Integer.toHexString(cmd), e.getMessage());
        }
    }

    private void cleanup() {
        active.set(false);
        if (iqConsumer != null) {
            sdrService.removeIqConsumer(iqConsumer);
        }
        try { socket.close(); } catch (IOException ignored) {}
        onDisconnect.accept(this);
    }
}
