package ru.agimate.common.net;

import okhttp3.Dns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * {@link PublicTargets} wired into OkHttp's name resolution, so a client built with it can reach
 * only vetted addresses — see the class javadoc there for why checking the URL separately is not
 * enough.
 *
 * <p>A refusal leaves as an {@link UnknownHostException} because that is the only failure the
 * interface allows; the message deliberately carries no address, since on some paths it reaches the
 * user who supplied the URL.
 */
public class PublicOnlyDns implements Dns {

    private final PublicTargets targets;

    public PublicOnlyDns(PublicTargets targets) {
        this.targets = targets;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        try {
            return Arrays.asList(targets.resolve(hostname));
        } catch (TargetNotAllowedException e) {
            throw new UnknownHostException("Target address is not allowed");
        }
    }
}
