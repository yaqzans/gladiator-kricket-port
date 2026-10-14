package javax.microedition.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Only declared so the score-upload code compiles; Connector never returns one. */
public interface HttpConnection {
    void setRequestMethod(String method) throws IOException;

    void setRequestProperty(String key, String value) throws IOException;

    OutputStream openOutputStream() throws IOException;

    InputStream openInputStream() throws IOException;

    void close() throws IOException;
}
