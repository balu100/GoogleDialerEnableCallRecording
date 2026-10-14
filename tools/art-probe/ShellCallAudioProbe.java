import android.content.Context;
import android.content.ContextWrapper;
import android.content.AttributionSource;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.RandomAccessFile;

/** Explicit ADB-only diagnostic. Probe mode never starts recording. No Shizuku dependency. */
public final class ShellCallAudioProbe {
    private static void little32(RandomAccessFile file, int value) throws Exception {
        file.writeInt(Integer.reverseBytes(value));
    }
    private static void little16(RandomAccessFile file, int value) throws Exception {
        file.writeShort(Short.reverseBytes((short) value));
    }
    private static void header(RandomAccessFile file, int bytes) throws Exception {
        file.seek(0);
        file.writeBytes("RIFF"); little32(file, bytes + 36); file.writeBytes("WAVEfmt ");
        little32(file, 16); little16(file, 1); little16(file, 1);
        little32(file, 8000); little32(file, 16000); little16(file, 2); little16(file, 16);
        file.writeBytes("data"); little32(file, bytes);
    }
    public static void main(String[] args) {
        AudioRecord recorder = null;
        int exitCode = 0;
        try {
            if (Process.myUid() != Process.SHELL_UID) throw new IllegalStateException("Run explicitly through adb shell");
            Looper.prepare();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            Context shell = new ContextWrapper(system.createPackageContext("com.android.shell", 0)) {
                @Override public AttributionSource getAttributionSource() {
                    return new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build();
                }
            };
            if (shell.getApplicationInfo().uid != Process.myUid()) throw new IllegalStateException("Unexpected shell attribution");
            AudioFormat format = new AudioFormat.Builder().setSampleRate(8000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            AudioRecord.Builder builder = new AudioRecord.Builder();
            // System API: identify the actual UID-2000 shell process, not the rejected clone.
            AudioRecord.Builder.class.getMethod("setContext", Context.class).invoke(builder, shell);
            recorder = builder.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL).setAudioFormat(format)
                .setBufferSizeInBytes(16000).build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("VOICE_CALL was not initialized");
            System.out.println("PASS: shell UID 2000 can initialize VOICE_CALL at 8000 Hz, PCM16, mono");
            if (args.length == 0 || args[0].equals("probe")) {
                System.out.println("Probe complete; recording was not started and no audio was read");
                return;
            }
            if (!args[0].equals("record") || args.length != 3) throw new IllegalArgumentException("record SECONDS OUTPUT.wav");
            int seconds = Integer.parseInt(args[1]);
            if (seconds < 1 || seconds > 120) throw new IllegalArgumentException("Duration must be 1..120 seconds");
            String destination = args[2];
            if (!destination.startsWith("/sdcard/Download/google-phone-audio-test-") || !destination.endsWith(".wav") || destination.contains(".."))
                throw new IllegalArgumentException("Use a google-phone-audio-test-*.wav file in Download");
            int bytes = 0;
            long energy = 0;
            int peak = 0;
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Recording did not start");
            System.out.println("Recording explicit test for " + seconds + " seconds to " + destination);
            try (RandomAccessFile file = new RandomAccessFile(destination, "rw")) {
                file.setLength(0); header(file, 0);
                byte[] buffer = new byte[1600];
                long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
                while (SystemClock.elapsedRealtime() < deadline) {
                    int count = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_NON_BLOCKING);
                    if (count < 0) throw new IllegalStateException("AudioRecord read error " + count);
                    if (count == 0) { SystemClock.sleep(10); continue; }
                    file.write(buffer, 0, count); bytes += count;
                    for (int i = 0; i + 1 < count; i += 2) {
                        int sample = (short) ((buffer[i] & 255) | ((buffer[i + 1] & 255) << 8));
                        energy += (long) sample * sample;
                        peak = Math.max(peak, Math.abs(sample));
                    }
                }
                header(file, bytes);
            }
            System.out.println("RESULT: bytes=" + bytes + " peak=" + peak + " rms=" + Math.sqrt(energy / Math.max(1.0, bytes / 2.0)));
            System.out.println("Playback must confirm both participants; nonzero samples alone do not prove two-sided audio");
        } catch (Throwable error) {
            error.printStackTrace();
            exitCode = 1;
        } finally {
            if (recorder != null) recorder.release();
            System.exit(exitCode);
        }
    }
}
