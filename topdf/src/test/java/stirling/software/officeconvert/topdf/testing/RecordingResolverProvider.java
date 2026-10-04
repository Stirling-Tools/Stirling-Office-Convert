package stirling.software.officeconvert.topdf.testing;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.Locale;
import java.util.stream.Stream;

public final class RecordingResolverProvider extends InetAddressResolverProvider {

    private static final byte[] V4 = {127, 0, 0, 1};

    private static final byte[] V6 = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

    @Override
    public InetAddressResolver get(Configuration configuration) {
        NoNetwork.resolverInstalled = true;
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
                if (h.equals("localhost") || h.endsWith(".localhost")) {
                    return loopback(host, policy);
                }
                NoNetwork.LOOKUPS.add("DNS lookup of " + h);
                throw new UnknownHostException(host + " was not looked up: tests never use DNS");
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                InetAddress a = InetAddress.getByAddress(addr);
                if (a.isLoopbackAddress()) {
                    return "localhost";
                }
                NoNetwork.LOOKUPS.add("reverse DNS lookup of " + a.getHostAddress());
                throw new UnknownHostException(a.getHostAddress() + " was not looked up: tests never use DNS");
            }
        };
    }

    private static Stream<InetAddress> loopback(String host, InetAddressResolver.LookupPolicy policy)
            throws UnknownHostException {
        int c = policy.characteristics();
        InetAddress v4 = InetAddress.getByAddress(host, V4);
        InetAddress v6 = InetAddress.getByAddress(host, V6);
        boolean four = (c & InetAddressResolver.LookupPolicy.IPV4) != 0;
        boolean six = (c & InetAddressResolver.LookupPolicy.IPV6) != 0;
        if (four && six) {
            return (c & InetAddressResolver.LookupPolicy.IPV6_FIRST) != 0 ? Stream.of(v6, v4) : Stream.of(v4, v6);
        }
        return six ? Stream.of(v6) : Stream.of(v4);
    }

    @Override
    public String name() {
        return "no-network-recorder";
    }
}
