package javax.microedition.io;

import java.io.IOException;

/** Score upload went to a server that no longer exists, so every connection fails. */
public final class Connector {
    private Connector() {
    }

    public static HttpConnection open(String url) throws IOException {
        throw new ConnectionNotFoundException("network disabled in port: " + url);
    }
}
