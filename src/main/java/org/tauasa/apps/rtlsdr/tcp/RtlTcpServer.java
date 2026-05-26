package org.tauasa.apps.rtlsdr.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.tauasa.apps.rtlsdr.config.RtlSdrProperties;
import org.tauasa.apps.rtlsdr.model.TunerType;
import org.tauasa.apps.rtlsdr.service.RtlSdrService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * rtl_tcp-compatible TCP server.
 *
 * <h2>Protocol</h2>
 * <ol>
 *   <li>On connect, send a 12-byte dongle-info header:
 *       {@code 'R','T','L','0'} + tuner-type (4 bytes BE) + gain-count (4 bytes BE).</li>
 *   <li>Then stream raw IQ bytes continuously.</li>
 *   <li>Accept 5-byte command packets from the client at any time:
 *       1-byte command ID + 4-byte big-endian parameter.</li>
 * </ol>
 *
 * <p>This is the same protocol used by the original {@code rtl_tcp} C binary, so any
 * SDR# / GQRX / SDR++ client can connect to this server without modification.
 */
@Component
public class RtlTcpServer {

    private static final Logger log = LoggerFactory.getLogger(RtlTcpServer.class);

    private final RtlSdrService     sdrService;
    private final RtlSdrProperties  props;
    private final AtomicBoolean     running = new AtomicBoolean(false);

    private ServerSocket   serverSocket;
    private ExecutorService executor;

    public RtlTcpServer(RtlSdrService sdrService, RtlSdrProperties props) {
        this.sdrService = sdrService;
        this.props      = props;
    }

    @PostConstruct
    public void init() {
        if (props.tcpAutoStart()) {
            start();
        } else {
            log.info("TCP server disabled (rtlsdr.tcp-auto-start=false). " +
                     "Call POST /api/tcp/start to enable.");
        }
    }

    /** Binds the server socket and starts accepting connections. Idempotent. */
    public synchronized void start() {
        if (running.get()) {
            log.warn("TCP server already running");
            return;
        }
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(props.tcpPort()));
            running.set(true);
            log.info("rtl_tcp server listening on port {}", props.tcpPort());

            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("rtltcp-client-" + t.getId());
                return t;
            });

            Thread acceptThread = new Thread(this::acceptLoop, "rtltcp-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

        } catch (IOException e) {
            log.error("Failed to start TCP server on port {}: {}", props.tcpPort(), e.getMessage());
        }
    }

    /** Stops the server and closes all connections. */
    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.warn("Error closing server socket", e);
        }
        if (executor != null) executor.shutdownNow();
        log.info("TCP server stopped");
    }

    public boolean isRunning() { return running.get(); }
    public int     getPort()   { return props.tcpPort(); }

    @PreDestroy
    public void shutdown() { stop(); }

    // =========================================================================
    // Accept loop
    // =========================================================================

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                log.info("TCP client connected: {}", client.getRemoteSocketAddress());
                executor.submit(new RtlTcpClientHandler(client, sdrService, this::onClientDisconnect));
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("Accept error: {}", e.getMessage());
                }
            }
        }
    }

    private void onClientDisconnect(RtlTcpClientHandler handler) {
        log.info("TCP client disconnected");
        // If this was the last client and we are streaming, stop
        // (optional: keep streaming for WebSocket consumers)
    }

    // =========================================================================
    // Dongle-info header builder (reused by RtlTcpClientHandler)
    // =========================================================================

    /**
     * Builds the 12-byte dongle-info header sent to every connecting TCP client.
     *
     * <pre>
     * Offset  Size  Description
     *  0       4    Magic: 'R','T','L','0'
     *  4       4    Tuner type (big-endian uint32)
     *  8       4    Number of gain levels (big-endian uint32)
     * </pre>
     */
    static byte[] buildDongleInfo(TunerType tunerType, int gainCount) {
        byte[] hdr = new byte[12];
        hdr[0] = 'R'; hdr[1] = 'T'; hdr[2] = 'L'; hdr[3] = '0';
        int t = tunerType.getCode();
        hdr[4] = (byte)(t >> 24); hdr[5] = (byte)(t >> 16);
        hdr[6] = (byte)(t >>  8); hdr[7] = (byte)(t);
        hdr[8]  = (byte)(gainCount >> 24); hdr[9]  = (byte)(gainCount >> 16);
        hdr[10] = (byte)(gainCount >>  8); hdr[11] = (byte)(gainCount);
        return hdr;
    }
}
