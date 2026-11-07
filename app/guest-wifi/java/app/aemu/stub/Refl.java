package app.aemu.stub;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Reflection helpers: the hidden framework API differs between Android 4.4 and 7.1, so nothing here is bound at compile time. */
final class Refl {
    private Refl() { }

    static void log(String s) {
        System.out.println("aemu-wifi: " + s);
        System.out.flush();
    }

    static Class<?> cls(String name) {
        try { return Class.forName(name); } catch (Throwable t) { return null; }
    }

    private static boolean accepts(Class<?> p, Object a) {
        if (a == null) return !p.isPrimitive();
        if (p.isInstance(a)) return true;
        if (p == int.class) return a instanceof Integer;
        if (p == long.class) return a instanceof Long;
        if (p == boolean.class) return a instanceof Boolean;
        return false;
    }

    private static boolean fits(Class<?>[] params, Object[] args) {
        if (params.length != args.length) return false;
        for (int i = 0; i < params.length; i++) if (!accepts(params[i], args[i])) return false;
        return true;
    }

    /** Calls the first method called [name] on [target] whose parameters take [args]; the result, or null when nothing fit or it threw. */
    static Object call(Object target, String name, Object... args) {
        if (target == null) return null;
        for (int pass = 0; pass < 2; pass++) {
            Method[] ms = pass == 0 ? target.getClass().getMethods() : target.getClass().getDeclaredMethods();
            for (Method m : ms) {
                if (!m.getName().equals(name) || Modifier.isStatic(m.getModifiers()) || !fits(m.getParameterTypes(), args)) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (Throwable t) {
                    log(name + " failed: " + t);
                }
            }
        }
        return null;
    }

    static Object callStatic(Class<?> c, String name, Object... args) {
        if (c == null) return null;
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || !Modifier.isStatic(m.getModifiers()) || !fits(m.getParameterTypes(), args)) continue;
            try {
                m.setAccessible(true);
                return m.invoke(null, args);
            } catch (Throwable t) {
                log(name + " failed: " + t);
            }
        }
        return null;
    }

    static boolean setField(Object target, String name, Object value) {
        if (target == null) return false;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return true;
            } catch (NoSuchFieldException e) {
                // look in the superclass
            } catch (Throwable t) {
                log("field " + name + ": " + t);
                return false;
            }
        }
        return false;
    }

    static Object getField(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // look in the superclass
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    static int intConst(Class<?> c, String name, int fallback) {
        if (c == null) return fallback;
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Instance made by the declared constructor with exactly these parameter types (null args allowed), or null. */
    static Object create(String className, Class<?>[] types, Object... args) {
        try {
            Constructor<?> k = Class.forName(className).getDeclaredConstructor(types);
            k.setAccessible(true);
            return k.newInstance(args);
        } catch (Throwable t) {
            log("new " + className + " failed: " + t);
            return null;
        }
    }

    static Object newNoArg(String className) {
        return create(className, new Class<?>[0]);
    }

    static Object defaultFor(Class<?> t) {
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return Integer.valueOf(0);
        if (t == long.class) return Long.valueOf(0);
        if (t == short.class) return Short.valueOf((short) 0);
        if (t == byte.class) return Byte.valueOf((byte) 0);
        if (t == float.class) return Float.valueOf(0);
        if (t == double.class) return Double.valueOf(0);
        if (t == char.class) return Character.valueOf((char) 0);
        return null;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
