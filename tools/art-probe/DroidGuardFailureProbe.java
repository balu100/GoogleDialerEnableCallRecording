import android.content.Context;
import android.content.ContextWrapper;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Fixture for the inspected 241 APK. Uses a local rejecting Binder, never a real Google API. */
public final class DroidGuardFailureProbe {
    private static Object client;
    private static Object callback;
    private static Class<?> base;
    private static final AtomicInteger completed = new AtomicInteger();

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static void broker(String message) throws Exception {
        Binder denial = new Binder() {
            @Override protected boolean onTransact(int code, Parcel request, Parcel reply, int flags) {
                if (code != 46) throw new AssertionError("Unexpected transaction " + code);
                reply.writeException(new SecurityException(message));
                return true;
            }
        };
        Object proxy = Class.forName("aacm").getConstructor(android.os.IBinder.class).newInstance(denial);
        field(base, "p").set(client, proxy);
        field(base, "y").setInt(client, 2);
    }

    private static void request() throws Throwable {
        try {
            base.getMethod("s", Class.forName("aach"), java.util.Set.class)
                .invoke(client, null, Collections.emptySet());
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void mustRethrow(String message) throws Throwable {
        broker(message);
        try {
            request();
            throw new AssertionError("Unrelated SecurityException was swallowed");
        } catch (SecurityException expected) {
            if (!java.util.Objects.equals(expected.getMessage(), message))
                throw new AssertionError("A different security failure occurred", expected);
            System.out.println("PASS: unrelated security failure retained: " + message);
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        try {
            Looper.prepare();
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("systemMain").invoke(null);
            Context system = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
            Context context = new ContextWrapper(system.createPackageContext("com.google.android.dialer.morphe", 0)) {
                @Override public Context getApplicationContext() { return this; }
            };
            callback = Class.forName("aaey").getConstructor().newInstance();
            base = Class.forName("aabo");
            client = Class.forName("aaex").getConstructor(Context.class, Looper.class,
                Class.forName("zzp"), Class.forName("aaaq")).newInstance(context, Looper.myLooper(), callback, callback);
            Queue<Object> queue = (Queue<Object>) field(callback.getClass(), "a").get(callback);
            queue.add(new Consumer<Object>() {
                @Override public void accept(Object status) {
                    try {
                        int code = field(status.getClass(), "f").getInt(status);
                        Object connection = field(status.getClass(), "i").get(status);
                        int reason = field(connection.getClass(), "c").getInt(connection);
                        if (code != 17 || reason != 10)
                            throw new AssertionError("Expected Status 17 with ConnectionResult 10, got " + code + "/" + reason);
                        completed.incrementAndGet();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }
            });
            broker("PackageVerificationRslt: not allowed: pkg=com.google.android.dialer.morphe");
            if (args.length > 0 && args[0].equals("baseline")) {
                try {
                    request();
                    throw new AssertionError("Baseline unexpectedly handled the rejection");
                } catch (SecurityException expected) {
                    System.out.println("PASS: original APK rethrows the observed DroidGuard rejection");
                    System.exit(0);
                }
            }
            request();
            System.out.println("PASS: repaired broker returned without throwing");
            new Handler(Looper.myLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (completed.get() != 1 || !queue.isEmpty())
                            throw new AssertionError("Failure callback did not finish exactly once and drain its queue");
                        if (field(base, "y").getInt(client) != 1)
                            throw new AssertionError("Client did not return to DISCONNECTED");
                        if ((Boolean) base.getMethod("o").invoke(client) || (Boolean) base.getMethod("p").invoke(client))
                            throw new AssertionError("Client stayed connected or connecting");
                        System.out.println("PASS: Status 17/ConnectionResult 10; pending queue drained; client DISCONNECTED");
                        mustRethrow("Permission denied for unrelated operation");
                        mustRethrow(null);
                        Object settings = Class.forName("aabv").getField("u").get(client);
                        client = Class.forName("aacy").getConstructor(Context.class, Looper.class,
                            Class.forName("aabq"), Class.forName("zzp"), Class.forName("aaaq"))
                            .newInstance(context, Looper.myLooper(), settings, callback, callback);
                        mustRethrow("PackageVerificationRslt: not allowed: pkg=com.google.android.dialer.morphe");
                        System.out.println("PASS: same rejection retained for non-DroidGuard service client");
                        for (String permission : new String[] { "android.permission.CAPTURE_AUDIO_OUTPUT",
                            "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT", "android.permission.RECORD_AUDIO" }) {
                            System.out.println("PERMISSION: " + permission + " = " +
                                context.getPackageManager().checkPermission(permission, context.getPackageName()));
                        }
                        System.out.println("PASS: actual ART execution of the repaired broker");
                        System.exit(0);
                    } catch (Throwable e) {
                        e.printStackTrace();
                        System.exit(1);
                    }
                }
            }, 300);
            new Handler(Looper.myLooper()).postDelayed(() -> {
                System.err.println("FAIL: callback timed out");
                System.exit(2);
            }, 5000);
            Looper.loop();
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
