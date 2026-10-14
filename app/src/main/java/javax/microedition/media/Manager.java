package javax.microedition.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class Manager {
    private Manager() {
    }

    public static Player createPlayer(InputStream in, String type) throws IOException, MediaException {
        if (in == null) {
            throw new MediaException("no stream");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new Player(out.toByteArray());
    }
}
