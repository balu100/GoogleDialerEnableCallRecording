import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Explicit, finite ADB helper. Only the selected package UID may request audio. No Shizuku. */
public final class ShellCallAudioServer {
    private static final int MAGIC = 0x47504131;
    private static volatile LocalSocket connected;

    private static void session(LocalSocket socket, Context shell) throws Exception {
        AudioRecord recorder = null;
        try {
            socket.setSoTimeout(3000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            if (input.readInt() != MAGIC) throw new IOException("Invalid protocol");
            int command = input.readInt();
            if (command == 0) { // Authenticated transport check; never creates or starts AudioRecord.
                output.writeInt(MAGIC); output.writeInt(0); output.flush();
                System.out.println("Transport check passed; no audio recorded");
                return;
            }
            if (command != 1) throw new IOException("Invalid START");
            AudioFormat format = new AudioFormat.Builder().setSampleRate(8000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            AudioRecord.Builder builder = new AudioRecord.Builder();
            AudioRecord.Builder.class.getMethod("setContext", Context.class).invoke(builder, shell);
            recorder = builder.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL).setAudioFormat(format)
                .setBufferSizeInBytes(16000).build();
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IOException("VOICE_CALL did not start");
            output.writeInt(MAGIC); output.writeInt(0); output.flush();
            System.out.println("Recording started by the connected app");
            byte[] buffer = new byte[65536];
            while (true) {
                if (input.readInt() != 2) throw new IOException("Invalid READ");
                int length = input.readInt();
                if (length <= 0 || length > buffer.length || (length & 1) != 0)
                    throw new IOException("Invalid PCM length");
                // Nonblocking preserves the Phone app's original ten-millisecond polling loop.
                int count = recorder.read(buffer, 0, length, AudioRecord.READ_NON_BLOCKING);
                if (count < 0) throw new IOException("AudioRecord error " + count);
                output.writeInt(count); output.write(buffer, 0, count); output.flush();
            }
        } finally {
            if (recorder != null) {
                recorder.release();
                System.out.println("Recording stopped; recorder released");
            }
            socket.close();
        }
    }

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 2000) throw new IllegalStateException("Run explicitly through adb shell");
            System.out.println("HELPER_PID=" + Process.myPid());
            if (args.length != 2 || !args[0].matches("[A-Za-z0-9_.]+"))
                throw new IllegalArgumentException("PACKAGE MINUTES");
            int minutes = Integer.parseInt(args[1]);
            if (minutes < 1 || minutes > 120) throw new IllegalArgumentException("Duration must be 1..120 minutes");
            Looper.prepare();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            final int targetUid = system.getPackageManager().getApplicationInfo(args[0], 0).uid;
            if (targetUid < 10000) throw new IllegalArgumentException("Select a regular installed app");
            Context shell = new ContextWrapper(system.createPackageContext("com.android.shell", 0)) {
                @Override public AttributionSource getAttributionSource() {
                    return new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build();
                }
            };
            if (shell.getApplicationInfo().uid != Process.myUid()) throw new IllegalStateException("Wrong shell UID");
            long deadline = SystemClock.elapsedRealtime() + minutes * 60000L;
            Thread expiry = new Thread(() -> {
                SystemClock.sleep(minutes * 60000L);
                LocalSocket active = connected;
                if (active != null) try { active.close(); } catch (IOException ignored) { }
                System.out.println("ADB helper expired");
                System.exit(0);
            }, "GooglePhone-ADB-expiry");
            expiry.setDaemon(true); expiry.start();
            System.out.println("Ready for " + args[0] + " (UID " + targetUid + "); expires in " + minutes + " minutes. No audio is being recorded.");
            while (SystemClock.elapsedRealtime() < deadline) {
                LocalSocket socket = new LocalSocket();
                try {
                    socket.connect(new LocalSocketAddress("local.dialer.callrecording." + targetUid,
                        LocalSocketAddress.Namespace.ABSTRACT));
                    if (socket.getPeerCredentials().getUid() != targetUid)
                        throw new SecurityException("Unexpected app socket owner");
                    connected = socket;
                    session(socket, shell);
                } catch (IOException error) {
                    // Missing socket means the Record button has not been pressed, or recording stopped.
                } finally {
                    connected = null;
                    socket.close();
                }
                SystemClock.sleep(100);
            }
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
}
