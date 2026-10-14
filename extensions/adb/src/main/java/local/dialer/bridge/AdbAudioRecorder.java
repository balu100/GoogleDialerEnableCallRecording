package local.dialer.bridge;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Optional test transport. The actual recorder belongs to the explicitly started ADB shell. */
public final class AdbAudioRecorder {
    public static final int MAGIC = 0x47504131;
    private LocalSocket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private volatile boolean recording;

    public synchronized void start() {
        if (recording) throw new IllegalStateException("ADB recorder already started");
        // The app listens, so it does not need permission to connect to a shell-domain socket.
        // Abstract sockets have no public TCP endpoint or filesystem pathname.
        LocalServerSocket listener = null;
        FutureTask<LocalSocket> accept = null;
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicReference<LocalSocket> pending = new AtomicReference<LocalSocket>();
        try {
            listener = new LocalServerSocket("local.dialer.callrecording." + Process.myUid());
            final LocalServerSocket server = listener;
            accept = new FutureTask<LocalSocket>(new Callable<LocalSocket>() {
                @Override public LocalSocket call() throws IOException {
                    while (true) {
                        LocalSocket candidate = server.accept();
                        if (candidate.getPeerCredentials().getUid() == 2000) {
                            pending.set(candidate);
                            if (aborted.get()) { candidate.close(); throw new IOException("Accept expired"); }
                            return candidate;
                        }
                        candidate.close();
                    }
                }
            });
            Thread waiter = new Thread(accept, "GooglePhone-ADB-accept");
            waiter.setDaemon(true);
            waiter.start();
            socket = accept.get(3, TimeUnit.SECONDS);
            socket.setSoTimeout(1000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(MAGIC);
            output.writeInt(1); // Explicit START, not automatic capture on connect.
            output.flush();
            if (input.readInt() != MAGIC || input.readInt() != 0)
                throw new IOException("ADB audio helper rejected START");
            recording = true;
        } catch (Exception error) {
            close();
            throw new IllegalStateException("Start the authorized ADB audio helper before recording", error);
        } finally {
            aborted.set(true);
            if (listener != null) try { listener.close(); } catch (IOException ignored) { }
            if (accept != null && !accept.isDone()) accept.cancel(true);
            LocalSocket accepted = pending.getAndSet(null);
            if (!recording && accepted != null) try { accepted.close(); } catch (IOException ignored) { }
        }
    }

    public boolean isRecording() { return recording; }

    public synchronized int read(byte[] buffer, int length) {
        if (buffer == null || length <= 0 || length > buffer.length || length > 65536 || (length & 1) != 0)
            throw new IllegalArgumentException("Invalid PCM buffer");
        if (!recording) return -3; // AudioRecord.ERROR_INVALID_OPERATION
        try {
            output.writeInt(2);
            output.writeInt(length);
            output.flush();
            int count = input.readInt();
            if (count < 0 || count > length || (count & 1) != 0) throw new IOException("Invalid PCM reply");
            input.readFully(buffer, 0, count);
            return count;
        } catch (IOException error) {
            close();
            throw new IllegalStateException("ADB audio helper disconnected", error);
        }
    }

    public synchronized void close() {
        recording = false;
        // EOF is the stop signal. Closing also unblocks the helper and releases its AudioRecord.
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        socket = null;
        input = null;
        output = null;
    }
}
