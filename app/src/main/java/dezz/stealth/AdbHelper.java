package dezz.stealth;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
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
                .map(packageName -> "shell pm disable-user --user 0 " + packageName)
                .collect(Collectors.toList()),
                callback
            )
        );
    }

    public void enableApps(Set<String> packageNames, AdbCallback callback) {
        executor.execute(
                new RunCommandsTask(packageNames.stream()
                        .map(packageName -> "shell pm enable " + packageName)
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

                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                for (String command : commands) {
                    Log.d("AdbHelper", ">> " + command);
                    out.println(command);

                    String line;
                    while ((line = in.readLine()) != null) {
                        Log.d("AdbHelper", "<< " + line);
                    }
                }

                callback.onSuccess(String.format(Locale.getDefault(), "Successfully executed %d commands", commands.size()));
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        }
    }
}
