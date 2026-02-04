package ru.agimate.common.buildinfo;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;

import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class BuildInfoService {

    private final BuildProperties buildProperties;

    private final ApplicationContext context;

    public BuildInfo getBuildInfo() {
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();

        long days = TimeUnit.MILLISECONDS.toDays(uptime);
        long hours = TimeUnit.MILLISECONDS.toHours(uptime) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptime) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(uptime) % 60;
        long milliseconds = uptime % 1000;

        final String uptimeString = String.format("Up %d days %02d:%02d:%02d.%03d",
                days, hours, minutes, seconds, milliseconds
        );

        return new BuildInfo(
                context.getId(),
                buildProperties.getVersion(),
                uptimeString
        );
    }

    public record BuildInfo(String appId,
                            String version,
                            String uptime) {
    }
}
