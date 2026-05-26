package org.tauasa.apps.rtlsdr;

import org.tauasa.apps.rtlsdr.config.RtlSdrProperties;
import org.tauasa.apps.rtlsdr.service.RtlSdrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(RtlSdrProperties.class)
public class RtlSdrApplication {

    private static final Logger log = LoggerFactory.getLogger(RtlSdrApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(RtlSdrApplication.class, args);
    }

    /**
     * If {@code rtlsdr.device-index} is set to 0 or above, automatically open
     * the device on startup. Set to {@code -1} (default) to skip auto-open.
     */
    @Bean
    CommandLineRunner autoOpen(RtlSdrService sdrService, RtlSdrProperties props) {
        return args -> {
            int deviceCount = sdrService.listDevices().size();
            log.info("Detected {} RTL-SDR device(s)", deviceCount);

            if (props.deviceIndex() >= 0) {
                if (deviceCount == 0) {
                    log.warn("No RTL-SDR devices found — skipping auto-open");
                } else {
                    log.info("Auto-opening device index {}", props.deviceIndex());
                    try {
                        sdrService.open(props.deviceIndex());
                    } catch (Exception e) {
                        log.error("Failed to auto-open device: {}", e.getMessage());
                    }
                }
            } else {
                log.info("Auto-open disabled (rtlsdr.device-index=-1). " +
                         "Use POST /api/device/open to open a device.");
            }
        };
    }
}
