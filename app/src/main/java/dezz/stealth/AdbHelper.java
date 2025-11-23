package dezz.stealth;

import android.util.Base64;
import android.util.Log;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class AdbHelper {
    private static final int ADB_PORT = 5555;

    public interface AdbCallback {
        void onSuccess(String message);

        void onError(String error);
    }

    private final Executor executor = Executors.newSingleThreadExecutor();

    public void disableApps(List<String> packageNames, AdbCallback callback) {
        executor.execute(
            new RunCommandsTask(packageNames.stream()
                .map(packageName -> "pm disable-user --user 0 " + packageName)
                .collect(Collectors.toList()),
                callback
            )
        );
    }

    public void enableApps(Set<String> packageNames, AdbCallback callback) {
        executor.execute(
                new RunCommandsTask(packageNames.stream()
                        .map(packageName -> "pm enable " + packageName)
                        .collect(Collectors.toList()),
                        callback
                )
        );
    }

    private static class RunCommandsTask implements Runnable {
        private final List<String> commands;
        private final AdbCallback callback;

        RunCommandsTask(List<String> commands, AdbCallback callback) {
            this.commands = commands;
            this.callback = callback;
        }

        @Override
        public void run() {
            try (Socket socket = new Socket()) {
                // Connect to local ADB server
                socket.connect(new InetSocketAddress("127.0.0.1", ADB_PORT), 1000);

                AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(new AdbBase64() {
                    @Override
                    public String encodeToString(byte[] data) {
                        return Base64.encodeToString(data, Base64.DEFAULT);
                    }
                });

                AdbConnection connection = AdbConnection.create(socket, crypto);
                connection.connect();

                for (String command : commands) {
                    Log.d("AdbHelper", ">> " + command);
                    AdbStream stream = connection.open("shell:" + command);
                    byte[] response = stream.read();
                    String responseText = new String(response, StandardCharsets.UTF_8);
                    Log.d("AdbHelper", "<< " + responseText);
                }

                callback.onSuccess(String.format(Locale.getDefault(), "Successfully executed %d commands", commands.size()));
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        }
    }
}
