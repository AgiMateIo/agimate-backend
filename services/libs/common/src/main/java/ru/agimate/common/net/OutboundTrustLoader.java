package ru.agimate.common.net;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds an {@link OutboundTrust} from configured resource locations — one loader for both services,
 * so they cannot end up with different trust sets. Spring resources rather than paths: the
 * application containers carry no volumes, and a path would differ between compose, the chart and a
 * {@code bootRun} on the host.
 *
 * <p>A location that cannot be read stops the start — carrying on with the platform set costs an
 * hour of reading {@code PKIX path building failed} while being sure the certificate is loaded.
 */
@Slf4j
@UtilityClass
public class OutboundTrustLoader {

    public static OutboundTrust load(ResourceLoader resourceLoader, String locations) {
        List<String> configured = split(locations);
        if (configured.isEmpty()) {
            log.info("Outbound TLS trust: platform anchors only");
            return OutboundTrust.systemDefault();
        }

        List<InputStream> streams = new ArrayList<>();
        try {
            for (String location : configured) {
                Resource resource = resourceLoader.getResource(location);
                if (!resource.exists()) {
                    throw new IllegalStateException("Trusted CA resource not found: " + location);
                }
                streams.add(resource.getInputStream());
            }
            OutboundTrust trust = OutboundTrust.with(streams);
            log.info("Outbound TLS trust: {} anchors, {} added from {}",
                    trust.anchorCount(),
                    trust.addedAnchors().size(),
                    configured);
            trust.addedAnchors().forEach(anchor ->
                    log.info("Outbound TLS trust: added anchor {}", anchor.getSubjectX500Principal()));
            return trust;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read a trusted CA resource of " + configured, e);
        } finally {
            streams.forEach(OutboundTrustLoader::closeQuietly);
        }
    }

    private static List<String> split(String locations) {
        if (locations == null || locations.isBlank()) {
            return List.of();
        }
        return Arrays.stream(locations.split(","))
                .map(String::trim)
                .filter(location -> !location.isEmpty())
                .collect(Collectors.toList());
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            log.debug("Closing a trusted CA resource failed (ignored): {}", e.getMessage());
        }
    }
}
