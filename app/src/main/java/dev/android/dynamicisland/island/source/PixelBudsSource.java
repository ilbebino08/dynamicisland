package dev.android.dynamicisland.island.source;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.SystemClock;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.XLog;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The island card and RFCOMM sessions for paired, connected Pixel Buds 2a. */
@SuppressLint("MissingPermission") // Runs with the SystemUI host's Bluetooth permissions.
final class PixelBudsSource {
    private static final String MODEL_NAME = "Pixel Buds 2a";
    private static final long COMPANION_GRACE_MS = 2_500;
    private static final long CONNECT_TIMEOUT_MS = 8_000;
    private static final long WRITE_TIMEOUT_MS = 2_000;
    private static final long RETRY_MS = 3_000;
    private static final long STABLE_SESSION_MS = 20_000;
    private static final int MAX_RETRIES = 2;
    // BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY through UNTETHERED_CASE_BATTERY (System APIs).
    private static final int[] BATTERY_METADATA_KEYS = {10, 11, 12};

    private final Context context;
    private final Handler main;
    private final Runnable onChange;
    private final Map<String, Buds> devices = new LinkedHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> new Thread(task, "Pixel Buds writes"));

    PixelBudsSource(Context context, Handler main, Runnable onChange) {
        this.context = context;
        this.main = main;
        this.onChange = onChange;
    }

    void startBatteryRelay() {
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    if (!PixelBudsBatteryBridge.COMPANION.equals(getSentFromPackage())) return;
                    try {
                        onBatteryRelay(intent.getStringExtra(PixelBudsBatteryBridge.ADDRESS),
                                intent.getIntArrayExtra(PixelBudsBatteryBridge.LEVELS));
                    } catch (RuntimeException error) {
                        XLog.once("Pixel Buds battery relay input", error);
                    }
                }
            }, new IntentFilter(PixelBudsBatteryBridge.ACTION), "android.permission.BLUETOOTH_CONNECT",
                    main, Context.RECEIVER_EXPORTED);
        } catch (RuntimeException error) {
            XLog.once("Pixel Buds battery receiver", error);
        }
    }

    void onBatteryRelay(String address, int[] levels) {
        Buds buds = devices.get(address);
        if (buds == null || !ModuleSettings.eventBluetooth()) return;
        if (levels != null && (levels.length != 3 || Arrays.stream(levels).anyMatch(level -> level < -1 || level > 100))) return;
        if (!buds.companionActive) {
            buds.companionActive = true;
            buds.stop();
            buds.mode = -1;
            buds.pendingMode = 0;
            Arrays.fill(buds.lastSeen, true);
            XLog.d("Pixel Buds 2a: using companion battery reports");
        }
        if (levels != null) {
            buds.updateBatteries(levels);
            XLog.d("Pixel Buds 2a: companion batteries received");
        }
        onChange.run();
    }

    static boolean matches(BluetoothDevice device) {
        try {
            String name = device.getName();
            int modelEnd = MODEL_NAME.length();
            return name != null && name.regionMatches(true, 0, MODEL_NAME, 0, modelEnd)
                    && (name.length() == modelEnd || Character.isWhitespace(name.charAt(modelEnd)));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    void syncSettings() {
        if (!ModuleSettings.eventBluetooth()) {
            clear();
            return;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return;
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (matches(device) && Boolean.TRUE.equals(Refl.call(device, "isConnected"))) connect(device);
            }
        } catch (RuntimeException ignored) {
            // An inaccessible Bluetooth service must not affect the other event cards.
        }
    }

    void connect(BluetoothDevice device) {
        if (device.getBondState() != BluetoothDevice.BOND_BONDED || devices.containsKey(device.getAddress())) return;
        Buds buds = new Buds(device);
        devices.put(device.getAddress(), buds);
        buds.refreshBatteryMetadata();
        main.postDelayed(buds.retry, COMPANION_GRACE_MS);
        XLog.d("Pixel Buds 2a: persistent card available");
        onChange.run();
    }

    void refreshBatteryMetadata(BluetoothDevice device) {
        Buds buds = devices.get(device.getAddress());
        if (buds != null && buds.refreshBatteryMetadata()) onChange.run();
    }

    void disconnect(BluetoothDevice device) {
        if (Boolean.TRUE.equals(Refl.call(device, "isConnected"))) return;
        Buds buds = devices.remove(device.getAddress());
        if (buds != null) {
            buds.stop();
            onChange.run();
        }
    }

    void clear() {
        if (devices.isEmpty()) return;
        for (Buds buds : devices.values()) buds.stop();
        devices.clear();
        onChange.run();
    }

    void collect(List<IslandItem> items) {
        if (!ModuleSettings.eventBluetooth()) return;
        for (Buds buds : devices.values()) items.add(buds.card());
    }

    static int nextMode(int mode) {
        return switch (mode) {
            case 2 -> 1; // Noise cancellation → Off
            case 1 -> 3; // Off → Transparency
            case 3 -> 2; // Transparency → Noise cancellation
            default -> 0;
        };
    }

    private static String modeLabel(int mode) {
        return switch (mode) {
            case 1 -> "island_off";
            case 2 -> "island_buds_noise_cancellation";
            case 3 -> "island_buds_transparency";
            default -> "island_buds_noise_unavailable";
        };
    }

    private final class Buds {
        final BluetoothDevice device;
        final long connectedSince = SystemClock.uptimeMillis();
        final int[] battery = {-1, -1, -1};
        final boolean[] lastSeen = new boolean[3];
        final Runnable retry = this::open;
        Session session;
        int failures;
        int mode = -1;
        int pendingMode;
        boolean writeRejected;
        boolean companionActive;
        boolean modePushedDuringWrite;

        Buds(BluetoothDevice device) {
            this.device = device;
        }

        void open() {
            if (devices.get(device.getAddress()) != this || session != null || companionActive) return;
            // shortcut: only the Maestro protocol observed on Pro 2 is implemented, extend after capturing 2a firmware variants.
            Session opened = new Session(this);
            session = opened;
            main.postDelayed(opened.connectTimeout, CONNECT_TIMEOUT_MS);
            new Thread(opened::read, "Pixel Buds RFCOMM").start();
        }

        void stop() {
            main.removeCallbacks(retry);
            if (session != null) session.close();
            session = null;
        }

        IslandItem card() {
            IslandItem item = new IslandItem("event:bt:" + device.getAddress());
            item.kind = IslandItem.Kind.EVENT;
            item.postTime = connectedSince;
            item.title = MODEL_NAME;
            item.icon = GlyphIcon.of(GlyphIcon.Kind.HEADPHONES);
            item.accent = 0xff0a84ff;
            item.trailing = IslandItem.Trailing.TEXT;
            item.trailingText = item.title;
            item.description = text("island_buds_battery", level(0), level(1), level(2));
            item.descriptionMaxLines = 5;
            int next = nextMode(mode);
            if (next != 0 && session != null) {
                item.actionType = 1;
                item.actions.add(new IslandItem.Action(text(modeLabel(mode)), null, null, () -> setMode(next), false));
                if (writeRejected) item.detail = text("island_buds_noise_failed");
            } else {
                item.detail = text(modeLabel(mode));
            }
            return item;
        }

        String level(int index) {
            if (battery[index] < 0) return "—";
            NumberFormat format = NumberFormat.getPercentInstance(context.getResources().getConfiguration().getLocales().get(0));
            String level = format.format(battery[index] / 100.0);
            return lastSeen[index] ? text("island_buds_last_seen", level) : level;
        }

        void updateBatteries(int[] levels) {
            for (int index = 0; index < levels.length; index++) {
                lastSeen[index] = levels[index] < 0;
                if (levels[index] >= 0) battery[index] = levels[index];
            }
        }

        boolean refreshBatteryMetadata() {
            boolean changed = false;
            for (int index = 0; index < battery.length; index++) {
                if (battery[index] >= 0 && !lastSeen[index]) continue;
                Object raw = Refl.call(device, "getMetadata", BATTERY_METADATA_KEYS[index]);
                if (!(raw instanceof byte[] bytes) || bytes.length == 0 || bytes.length > 3) continue;
                try {
                    int percentage = Integer.parseInt(new String(bytes, StandardCharsets.US_ASCII));
                    if (percentage >= 0 && percentage <= 100 && percentage != battery[index]) {
                        battery[index] = percentage;
                        lastSeen[index] = true;
                        changed = true;
                    }
                } catch (NumberFormatException ignored) { }
            }
            return changed;
        }

        void setMode(int next) {
            if (session == null || pendingMode != 0 || next != nextMode(mode)) return;
            pendingMode = next;
            modePushedDuringWrite = false;
            main.postDelayed(session.writeTimeout, WRITE_TIMEOUT_MS);
            session.send(PixelBudsMaestro.setMode(session.channel, next));
        }

        void failed(Session failed) {
            if (session != failed) return;
            failed.close();
            session = null;
            mode = -1;
            pendingMode = 0;
            writeRejected = false;
            Arrays.fill(lastSeen, true);
            if (failed.readySince != 0 && SystemClock.uptimeMillis() - failed.readySince >= STABLE_SESSION_MS) {
                failures = 0;
            }
            if (failures++ < MAX_RETRIES) main.postDelayed(retry, RETRY_MS);
            onChange.run();
        }
    }

    private final class Session {
        final Buds buds;
        final Runnable connectTimeout;
        final Runnable writeTimeout;
        volatile BluetoothSocket socket;
        volatile boolean closed;
        int channel;
        boolean modeStreamSeen;
        long readySince;

        Session(Buds buds) {
            this.buds = buds;
            connectTimeout = () -> buds.failed(this);
            writeTimeout = () -> buds.failed(this);
        }

        void read() {
            try (BluetoothSocket connection = buds.device.createRfcommSocketToServiceRecord(PixelBudsBatteryBridge.MAESTRO)) {
                socket = connection;
                if (closed) return;
                connection.connect();
                BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                while (!closed) {
                    PixelBudsMaestro.Packet packet = PixelBudsMaestro.read(input);
                    if (packet != null) main.post(() -> receive(packet));
                }
            } catch (IOException | RuntimeException ignored) {
                if (!closed) XLog.d("Pixel Buds 2a: Maestro connection ended (" + ignored.getClass().getSimpleName() + ")");
            } finally {
                main.post(() -> buds.failed(this));
            }
        }

        void send(byte[] frame) {
            writer.execute(() -> {
                if (closed) return;
                try {
                    OutputStream output = socket.getOutputStream();
                    output.write(frame);
                    output.flush();
                } catch (IOException | RuntimeException ignored) {
                    main.post(() -> buds.failed(this));
                }
            });
        }

        void receive(PixelBudsMaestro.Packet packet) {
            if (closed || (packet.status() != 0 && packet.method() != PixelBudsMaestro.WRITE_MODE)) return;
            if (channel == 0) {
                if (packet.method() != PixelBudsMaestro.SOFTWARE || packet.type() != PixelBudsMaestro.RESPONSE
                        || packet.callId() != -1 || !PixelBudsMaestro.supportsChannel(packet.channel())) return;
                channel = packet.channel();
                XLog.d("Pixel Buds 2a: Maestro channel confirmed");
                send(PixelBudsMaestro.request(channel, PixelBudsMaestro.SETTINGS));
                send(PixelBudsMaestro.request(channel, PixelBudsMaestro.READ_MODE, (byte) 0x20, (byte) 13));
                send(PixelBudsMaestro.request(channel, PixelBudsMaestro.BATTERY));
                return;
            }
            if (packet.channel() != channel) return;
            if (packet.method() == PixelBudsMaestro.BATTERY && packet.type() == PixelBudsMaestro.STREAM) {
                ready();
                buds.updateBatteries(packet.batteries());
            } else if ((packet.method() == PixelBudsMaestro.SETTINGS && packet.type() == PixelBudsMaestro.STREAM)
                    || (packet.method() == PixelBudsMaestro.READ_MODE && packet.type() == PixelBudsMaestro.RESPONSE
                    && !modeStreamSeen)) {
                int mode = packet.mode();
                if (mode < 0) return;
                ready();
                if (packet.type() == PixelBudsMaestro.STREAM) {
                    modeStreamSeen = true;
                    buds.modePushedDuringWrite = buds.pendingMode != 0;
                }
                if (buds.mode == -1) XLog.d("Pixel Buds 2a: noise control state received");
                buds.mode = mode;
            } else if (packet.method() == PixelBudsMaestro.WRITE_MODE && packet.type() == PixelBudsMaestro.RESPONSE
                    && buds.pendingMode != 0) {
                main.removeCallbacks(writeTimeout);
                buds.writeRejected = packet.status() != 0;
                if (!buds.writeRejected && !buds.modePushedDuringWrite) buds.mode = buds.pendingMode;
                buds.pendingMode = 0;
            } else {
                return;
            }
            onChange.run();
        }

        void ready() {
            if (readySince != 0) return;
            readySince = SystemClock.uptimeMillis();
            main.removeCallbacks(connectTimeout);
        }

        void close() {
            closed = true;
            main.removeCallbacks(connectTimeout);
            main.removeCallbacks(writeTimeout);
            BluetoothSocket connection = socket;
            if (connection != null) {
                try {
                    connection.close();
                } catch (IOException ignored) { }
            }
        }
    }

    private String text(String key, Object... args) {
        return IslandText.get(context, key, args);
    }
}
