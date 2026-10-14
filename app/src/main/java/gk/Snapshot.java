package gk;

import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;

import javax.microedition.lcdui.Image;

/**
 * The complete state of the game, written field by field with reflection, so a match can be
 * resumed exactly (mid-delivery, lions, fielders, animations and all) after Android kills the app.
 *
 * Covered: every instance field of the game objects reachable from the canvas (classes e, c, d,
 * f) and every static field of those classes. Images are stored by file name and reloaded.
 * Left alone (kept as they are in the new process): the sound manager, the record store,
 * the random generator, threads and the unused name-entry form.
 */
final class Snapshot {
    private static final int MAGIC = 0x474B5331;   // "GKS1"
    private static final String[] GAME_CLASSES = {"defpackage.e", "defpackage.c", "defpackage.d", "defpackage.f"};

    private static final java.util.Set<String> NOT_SAVED = new java.util.HashSet<>(
            java.util.Arrays.asList("l", "f138ab", "f139ac", "f140ad", "f141ae"));

    private static final byte NULL = 0, STRING = 1, IMAGE = 2, ARRAY = 3, OBJECT = 4, REF = 5, KEEP = 6;

    /** Restoring a static final field: its array must be refilled, not replaced. */
    private static boolean inPlace;

    private Snapshot() {
    }

    static boolean isGameClass(Class<?> k) {
        for (String n : GAME_CLASSES) {
            if (k.getName().equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** Fields that hold game state: everything except compile-time constants. */
    static ArrayList<Field> stateFields(Class<?> k, boolean statics) {
        ArrayList<Field> out = new ArrayList<>();
        for (Field f : k.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) != statics || f.isSynthetic()) {
                continue;
            }
            // static final primitives / strings are constants; static final arrays can still change
            if (statics && Modifier.isFinal(mod) && !f.getType().isArray()) {
                continue;
            }
            // e.l keeps the game loop running (choosing Exit clears it), and f138ab..f141ae are
            // the held / pressed key masks: a key held at save time must not be "held" after restore
            if (statics && k.getName().equals("defpackage.e") && NOT_SAVED.contains(f.getName())) {
                continue;
            }
            f.setAccessible(true);
            out.add(f);
        }
        return out;
    }

    // ---- writing ----------------------------------------------------------

    static void write(DataOutputStream out, Object canvas) throws IOException, IllegalAccessException {
        IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
        out.writeInt(MAGIC);
        writeObject(out, canvas, ids);
        for (String name : GAME_CLASSES) {
            Class<?> k = load(name);
            ArrayList<Field> fs = stateFields(k, true);
            out.writeUTF(name);
            out.writeInt(fs.size());
            for (Field f : fs) {
                out.writeUTF(f.getName());
                writeValue(out, f.getType(), f.get(null), ids);
            }
        }
    }

    private static void writeObject(DataOutputStream out, Object o, IdentityHashMap<Object, Integer> ids)
            throws IOException, IllegalAccessException {
        ids.put(o, ids.size());
        out.writeUTF(o.getClass().getName());
        ArrayList<Field> fs = stateFields(o.getClass(), false);
        out.writeInt(fs.size());
        for (Field f : fs) {
            out.writeUTF(f.getName());
            writeValue(out, f.getType(), f.get(o), ids);
        }
    }

    private static void writeValue(DataOutputStream out, Class<?> type, Object v,
                                   IdentityHashMap<Object, Integer> ids) throws IOException, IllegalAccessException {
        if (type.isPrimitive()) {
            writePrimitive(out, type, v);
            return;
        }
        if (v == null) {
            out.writeByte(NULL);
        } else if (ids.containsKey(v)) {
            out.writeByte(REF);
            out.writeInt(ids.get(v));
        } else if (v instanceof String) {
            out.writeByte(STRING);
            out.writeUTF((String) v);
        } else if (v instanceof Image) {
            out.writeByte(IMAGE);
            out.writeUTF(((Image) v).name());
        } else if (v.getClass().isArray()) {
            Class<?> ct = v.getClass().getComponentType();
            if (!ct.isPrimitive() && !isStorable(ct)) {
                out.writeByte(KEEP);
                return;
            }
            ids.put(v, ids.size());
            out.writeByte(ARRAY);
            int n = Array.getLength(v);
            out.writeInt(n);
            for (int i = 0; i < n; i++) {
                writeValue(out, ct, Array.get(v, i), ids);
            }
        } else if (isGameClass(v.getClass())) {
            out.writeByte(OBJECT);
            writeObject(out, v, ids);
        } else {
            out.writeByte(KEEP);   // sound manager, record store, Random, form widgets...
        }
    }

    private static boolean isStorable(Class<?> k) {
        return k == String.class || k == Image.class || k.isArray() || isGameClass(k) || k == Object.class;
    }

    private static void writePrimitive(DataOutputStream out, Class<?> t, Object v) throws IOException {
        if (t == int.class) out.writeInt((Integer) v);
        else if (t == byte.class) out.writeByte((Byte) v);
        else if (t == short.class) out.writeShort((Short) v);
        else if (t == boolean.class) out.writeBoolean((Boolean) v);
        else if (t == long.class) out.writeLong((Long) v);
        else if (t == char.class) out.writeChar((Character) v);
        else if (t == float.class) out.writeFloat((Float) v);
        else out.writeDouble((Double) v);
    }

    // ---- reading ----------------------------------------------------------

    /**
     * Writes the saved state into the live game. The live objects are reused wherever the saved
     * graph has an object of the same class in the same place, so the match should be loaded
     * (players built, images loaded) before this is called.
     */
    static void restore(DataInputStream in, Object canvas) throws IOException, ReflectiveOperationException {
        if (in.readInt() != MAGIC) {
            throw new IOException("not a snapshot");
        }
        ArrayList<Object> ids = new ArrayList<>();
        readObject(in, canvas, canvas, ids);
        for (int c = 0; c < GAME_CLASSES.length; c++) {
            String name = in.readUTF();
            Class<?> k = load(name);
            HashMap<String, Field> byName = index(stateFields(k, true));
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                Field f = byName.get(in.readUTF());
                if (f == null) {
                    throw new IOException("unknown static field in " + name);
                }
                Object cur = f.get(null);
                inPlace = Modifier.isFinal(f.getModifiers());
                Object v = readValue(in, f.getType(), cur, canvas, ids);
                inPlace = false;
                if (!Modifier.isFinal(f.getModifiers())) {
                    f.set(null, v);
                }
            }
        }
    }

    private static Object readObject(DataInputStream in, Object live, Object canvas, ArrayList<Object> ids)
            throws IOException, ReflectiveOperationException {
        Class<?> k = load(in.readUTF());
        Object o = live != null && live.getClass() == k ? live : instantiate(k, canvas);
        ids.add(o);
        HashMap<String, Field> byName = index(stateFields(k, false));
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            Field f = byName.get(in.readUTF());
            if (f == null) {
                throw new IOException("unknown field in " + k.getName());
            }
            f.set(o, readValue(in, f.getType(), f.get(o), canvas, ids));
        }
        return o;
    }

    private static Object readValue(DataInputStream in, Class<?> type, Object cur, Object canvas,
                                    ArrayList<Object> ids) throws IOException, ReflectiveOperationException {
        if (type.isPrimitive()) {
            return readPrimitive(in, type);
        }
        byte tag = in.readByte();
        switch (tag) {
            case NULL:
                return null;
            case REF:
                return ids.get(in.readInt());
            case STRING:
                return in.readUTF();
            case IMAGE:
                return Image.createImage(in.readUTF());
            case KEEP:
                return cur;
            case OBJECT:
                return readObject(in, cur, canvas, ids);
            case ARRAY: {
                int n = in.readInt();
                Class<?> ct = type.isArray() ? type.getComponentType() : Object.class;
                // a fresh array, so arrays shared in the new process can't overwrite each other;
                // only a static final array (cannot be reassigned) is refilled in place
                boolean refill = inPlace && cur != null && cur.getClass().isArray() && Array.getLength(cur) == n;
                inPlace = false;
                Object arr = refill ? cur : Array.newInstance(ct, n);
                ids.add(arr);
                for (int i = 0; i < n; i++) {
                    Object curElem = refill ? Array.get(arr, i) : null;
                    Array.set(arr, i, readValue(in, ct, curElem, canvas, ids));
                }
                return arr;
            }
            default:
                throw new IOException("bad tag " + tag);
        }
    }

    private static Object readPrimitive(DataInputStream in, Class<?> t) throws IOException {
        if (t == int.class) return in.readInt();
        if (t == byte.class) return in.readByte();
        if (t == short.class) return in.readShort();
        if (t == boolean.class) return in.readBoolean();
        if (t == long.class) return in.readLong();
        if (t == char.class) return in.readChar();
        if (t == float.class) return in.readFloat();
        return in.readDouble();
    }

    /** A game object that did not exist yet: build it with any constructor, then overwrite it. */
    private static Object instantiate(Class<?> k, Object canvas) throws ReflectiveOperationException {
        ReflectiveOperationException last = null;
        for (Constructor<?> c : k.getDeclaredConstructors()) {
            Class<?>[] ps = c.getParameterTypes();
            Object[] args = new Object[ps.length];
            for (int i = 0; i < ps.length; i++) {
                args[i] = ps[i].isInstance(canvas) ? canvas : defaultValue(ps[i]);
            }
            try {
                c.setAccessible(true);
                return c.newInstance(args);
            } catch (ReflectiveOperationException | RuntimeException e) {
                last = e instanceof ReflectiveOperationException ? (ReflectiveOperationException) e
                        : new InstantiationException(e.toString());
            }
        }
        Log.e("GK", "cannot build " + k.getName());
        throw last != null ? last : new InstantiationException(k.getName());
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == char.class) return (char) 0;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        return 0d;
    }

    private static HashMap<String, Field> index(ArrayList<Field> fs) {
        HashMap<String, Field> m = new HashMap<>();
        for (Field f : fs) {
            m.put(f.getName(), f);
        }
        return m;
    }

    private static Class<?> load(String name) throws IOException {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IOException(e.toString());
        }
    }
}
