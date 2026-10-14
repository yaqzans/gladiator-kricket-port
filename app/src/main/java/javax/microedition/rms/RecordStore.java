package javax.microedition.rms;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import gk.Host;

/** A record store is one file in the app's private storage; record ids start at 1. */
public class RecordStore {
    private static final HashMap<String, RecordStore> open = new HashMap<>();

    private final File file;
    private final ArrayList<byte[]> records = new ArrayList<>();

    private RecordStore(File file) {
        this.file = file;
    }

    public static synchronized RecordStore openRecordStore(String name, boolean create)
            throws RecordStoreException {
        RecordStore rs = open.get(name);
        if (rs != null) {
            return rs;
        }
        File f = new File(Host.filesDir(), "rms_" + name + ".dat");
        if (!f.exists() && !create) {
            throw new RecordStoreException("no record store " + name);
        }
        rs = new RecordStore(f);
        if (f.exists()) {
            rs.load();
        }
        open.put(name, rs);
        return rs;
    }

    public synchronized void closeRecordStore() {
    }

    public synchronized int getNumRecords() {
        return records.size();
    }

    public synchronized int addRecord(byte[] data, int offset, int len) throws RecordStoreException {
        byte[] r = new byte[len];
        System.arraycopy(data, offset, r, 0, len);
        records.add(r);
        save();
        return records.size();
    }

    public synchronized void setRecord(int id, byte[] data, int offset, int len) throws RecordStoreException {
        check(id);
        byte[] r = new byte[len];
        System.arraycopy(data, offset, r, 0, len);
        records.set(id - 1, r);
        save();
    }

    public synchronized byte[] getRecord(int id) throws RecordStoreException {
        check(id);
        return records.get(id - 1).clone();
    }

    private void check(int id) throws RecordStoreException {
        if (id < 1 || id > records.size()) {
            throw new RecordStoreException("invalid record id " + id);
        }
    }

    private void load() throws RecordStoreException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                byte[] r = new byte[in.readInt()];
                in.readFully(r);
                records.add(r);
            }
        } catch (IOException e) {
            throw new RecordStoreException(e.toString());
        }
    }

    /** Write to a temp file then rename, so a kill mid-save cannot corrupt progress. */
    private void save() throws RecordStoreException {
        File tmp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp))) {
            out.writeInt(records.size());
            for (byte[] r : records) {
                out.writeInt(r.length);
                out.write(r);
            }
        } catch (IOException e) {
            throw new RecordStoreException(e.toString());
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            if (!tmp.renameTo(file)) {
                throw new RecordStoreException("cannot save " + file);
            }
        }
    }
}
