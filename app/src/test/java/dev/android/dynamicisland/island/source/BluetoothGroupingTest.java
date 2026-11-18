package dev.android.dynamicisland.island.source;

import static org.junit.Assert.*;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Spanned;
import android.text.style.ImageSpan;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.Refl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBluetoothDevice;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class BluetoothGroupingTest {
    private SharedPreferences prefs;
    private final Cache cache = new Cache();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Before public void prepare() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("bluetooth-grouping", 0);
        prefs.edit().clear().putBoolean(ModuleSettings.KEY_EVENT_BLUETOOTH, true).commit();
        ModuleSettings.configurePreview(prefs);
    }

    @After public void restore() {
        main.removeCallbacksAndMessages(null);
        prefs.edit().clear().commit();ModuleSettings.configurePreview(prefs);
    }

    private BluetoothDevice device(String address, String name, int battery) {
        BluetoothDevice device = ShadowBluetoothDevice.newInstance(address);
        Shadows.shadowOf(device).setName(name);
        Shadows.shadowOf(device).setBatteryLevel(battery);
        Shadows.shadowOf(device).setBluetoothClass(ReflectionHelpers.callConstructor(BluetoothClass.class,
                ReflectionHelpers.ClassParameter.from(int.class, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET)));
        return device;
    }

    private EventSource source() {
        BluetoothDeviceGroups groups = new BluetoothDeviceGroups(() -> cache);
        return new EventSource(RuntimeEnvironment.getApplication(), main, () -> {}, groups::identity);
    }

    private void connect(EventSource source, BluetoothDevice device) {
        source.onBluetooth(new Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, device));
    }

    private void battery(EventSource source, BluetoothDevice device, int level) {
        source.onBluetooth(new Intent(EventSource.BT_BATTERY_ACTION).putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra(EventSource.BT_BATTERY_EXTRA, level));
    }

    private List<IslandItem> shown(EventSource source) {
        List<IslandItem> out = new ArrayList<>();source.collect(out);return out;
    }

    @Test public void recognizesPixelBudsWithPersonalizedBluetoothNames() {
        assertTrue(PixelBudsSource.matches(device("3C:31:8A:46:D5:05", "Pixel Buds 2a di Test", 73)));
        assertTrue(PixelBudsSource.matches(device("3C:31:8A:46:D5:06", "pixel buds 2A", 73)));
        assertFalse(PixelBudsSource.matches(device("3C:31:8A:46:D5:07", "Pixel Buds 2abc", 73)));
        assertFalse(PixelBudsSource.matches(device("3C:31:8A:46:D5:08", "Pixel Buds Pro 2", 73)));
    }

    @Test public void pixelBudsCardStaysUntilDisconnectAndListsEachBattery() {
        BluetoothDevice earbuds = device("3C:31:8A:46:D5:05", "Pixel Buds 2a di Test", 73);
        Shadows.shadowOf(earbuds).setBondState(BluetoothDevice.BOND_BONDED);
        assertEquals(Boolean.TRUE, Refl.call(earbuds, "setMetadata", 10, new byte[] {'7', '3'}));
        EventSource source = source();
        try {
            connect(source, earbuds);
            IslandItem first = shown(source).get(0);
            assertTrue(first.description.toString().contains("73%"));
            assertFalse(first.transientPopup);
            source.setShadeOpen(true);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31));
            source.setShadeOpen(false);
            IslandItem stillConnected = shown(source).get(0);
            assertEquals(first.key, stillConnected.key);
            assertEquals(first.postTime, stillConnected.postTime);
            assertTrue(stillConnected.description.toString().contains("Case"));
        } finally {
            source.onBluetooth(new Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    .putExtra(BluetoothDevice.EXTRA_DEVICE, earbuds));
        }
        assertTrue(shown(source).isEmpty());
        assertEquals(1, PixelBudsSource.nextMode(2));
        assertEquals(3, PixelBudsSource.nextMode(1));
        assertEquals(2, PixelBudsSource.nextMode(3));
        assertEquals(0, PixelBudsSource.nextMode(0));
    }

    @Test public void companionModeMakesTheAncButtonAvailableWithoutADirectSocket() {
        BluetoothDevice earbuds = device("3C:31:8A:46:D5:05", "Pixel Buds 2a", 73);
        Shadows.shadowOf(earbuds).setBondState(BluetoothDevice.BOND_BONDED);
        PixelBudsSource source = new PixelBudsSource(RuntimeEnvironment.getApplication(), main, () -> {});
        source.connect(earbuds);
        try {
            source.onBatteryRelay(earbuds.getAddress(), new int[] {73, 72, 52}, 2, false);
            List<IslandItem> cards = new ArrayList<>();
            source.collect(cards);
            assertEquals(1, cards.get(0).actions.size());
            assertEquals("Noise cancellation", cards.get(0).actions.get(0).title);
            source.onBatteryRelay(earbuds.getAddress(), null, 1, false);
            cards.clear();
            source.collect(cards);
            assertEquals("Off", cards.get(0).actions.get(0).title);
            assertTrue(cards.get(0).description.toString().contains("52%"));
        } finally {
            source.clear();
        }
    }

    @Test public void availableGoogleIconsDecorateTheExistingBatteryLabels() {
        Drawable left = new ColorDrawable();
        Drawable chargingCase = new ColorDrawable();
        var description = (Spanned) PixelBudsIcons.batteryDescription(RuntimeEnvironment.getApplication(),
                new Drawable[] {left, null, chargingCase}, "73%", "72%", "52%");
        ImageSpan[] icons = description.getSpans(0, description.length(), ImageSpan.class);
        assertEquals(2, icons.length);
        assertSame(left, icons[0].getDrawable());
        assertSame(chargingCase, icons[1].getDrawable());
        assertTrue(description.toString().contains("Right 72%"));
    }

    @Test public void samsungMemberAndLeadCycleBecomeOneStablePopupAndEitherMemberUpdatesBattery() {
        BluetoothDevice left = device("3C:31:8A:46:D5:04", "Galaxy Buds3 Pro", 73);
        BluetoothDevice right = device("3C:31:8A:46:D6:BA", "LE-Galaxy Buds3 Pro", -1);
        SamsungCached root = new SamsungCached(left), member = new SamsungCached(right);
        root.mMemberDevices.add(member);member.mLeadDevice = root;cache.roots.add(root);
        EventSource source = source();connect(source, left);
        IslandItem first = shown(source).get(0);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        connect(source, right);
        List<IslandItem> cards = shown(source);assertEquals(1, cards.size());
        assertEquals(first.key, cards.get(0).key);assertEquals(first.postTime, cards.get(0).postTime);
        assertEquals(first.expiresAtUptime, cards.get(0).expiresAtUptime);
        assertEquals("Galaxy Buds3 Pro", cards.get(0).title.toString());
        assertEquals("unknown member level must not erase a known battery", 73, cards.get(0).batteryPercent);
        battery(source, right, 65);cards = shown(source);
        assertEquals(1, cards.size());assertEquals("65%", cards.get(0).trailingText.toString());
        assertEquals(first.expiresAtUptime, cards.get(0).expiresAtUptime);
        battery(source, left, 68);assertEquals(68, shown(source).get(0).batteryPercent);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertTrue(shown(source).isEmpty());battery(source, right, 60);
        assertTrue("battery broadcasts do not bring back expired popups", shown(source).isEmpty());
    }

    @Test public void lateCsipGroupInformationMergesExistingCardsWithoutRestartingTheirLifetime() {
        BluetoothDevice left = device("3C:31:8A:46:D5:04", "Buds3 Pro", -1);
        BluetoothDevice right = device("3C:31:8A:46:D6:BA", "Buds3 Pro", 81);
        AospCached a = new AospCached(left), b = new AospCached(right);cache.roots.addAll(List.of(a, b));
        EventSource source = source();connect(source, left);
        IslandItem first = shown(source).get(0);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        connect(source, right);assertEquals(2, shown(source).size());
        a.group = b.group = 0; // Zero is a valid CSIP group, -1 is unknown.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        List<IslandItem> cards = shown(source);assertEquals(1, cards.size());
        assertEquals(first.key, cards.get(0).key);assertEquals(first.postTime, cards.get(0).postTime);
        assertEquals(first.expiresAtUptime, cards.get(0).expiresAtUptime);
        assertEquals("the known member battery survives reconciliation", 81, cards.get(0).batteryPercent);
        battery(source, right, 79);assertEquals(79, shown(source).get(0).batteryPercent);
    }

    @Test public void sameNamesAndAdjacentAddressesDoNotMergeUnrelatedDevicesAndSingleHeadsetsStaySingle() {
        BluetoothDevice tour = device("10:20:30:40:50:60", "JBL Tour Pro 3", 90);
        BluetoothDevice other = device("10:20:30:40:50:61", "JBL Tour Pro 3", 85);
        AospCached a = new AospCached(tour), b = new AospCached(other);a.group = 10;b.group = 11;
        cache.roots.addAll(List.of(a, b));EventSource source = source();
        connect(source, tour);connect(source, tour);assertEquals(1, shown(source).size());
        connect(source, other);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals("distinct groups are not a debounce/name heuristic", 2, shown(source).size());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        connect(source, tour); // No collect between expiry and reconnect: the old popup must not swallow it.
        assertEquals("a later real reconnect gets a fresh popup", 1, shown(source).size());
    }

    @Test public void aMemberBatteryBroadcastWithoutItsOwnAclStillUpdatesTheSet() {
        BluetoothDevice left = device("3C:31:8A:46:D5:04", "Buds3 Pro", 70);
        BluetoothDevice right = device("3C:31:8A:46:D6:BA", "Buds3 Pro", 60);
        SamsungCached root = new SamsungCached(left);root.mMemberDevices.add(new SamsungCached(right));cache.roots.add(root);
        EventSource source = source();connect(source, left);battery(source, right, 60);
        assertEquals(1, shown(source).size());assertEquals(60, shown(source).get(0).batteryPercent);
    }

    @Test public void deferredGroupsStillReceiveTheirFullDisplayTimeAfterClosingTheShade() {
        BluetoothDevice left = device("3C:31:8A:46:D5:04", "Buds3 Pro", 70);
        BluetoothDevice right = device("3C:31:8A:46:D6:BA", "Buds3 Pro", 60);
        AospCached a = new AospCached(left), b = new AospCached(right);a.group = b.group = 1;
        cache.roots.addAll(List.of(a, b));EventSource source = source();source.setShadeOpen(true);
        connect(source, left);connect(source, right);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));assertTrue(shown(source).isEmpty());
        source.setShadeOpen(false);assertEquals(1, shown(source).size());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals(1, shown(source).size());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));assertTrue(shown(source).isEmpty());
    }

    @Test public void hearingAidSyncIdsGroupButMissingApisAndUnknownIdsKeepIndependentIdentities() {
        BluetoothDevice left = device("10:20:30:40:50:60", "Same name", 80);
        BluetoothDevice right = device("10:20:30:40:50:61", "Same name", 80);
        AospCached a = new AospCached(left), b = new AospCached(right);cache.roots.addAll(List.of(a, b));
        BluetoothDeviceGroups groups = new BluetoothDeviceGroups(() -> cache);
        assertTrue(Collections.disjoint(groups.identity(left), groups.identity(right)));
        a.hearing = b.hearing = 456;
        assertFalse(Collections.disjoint(groups.identity(left), groups.identity(right)));
        BluetoothDeviceGroups absent = new BluetoothDeviceGroups(() -> null);
        assertTrue(Collections.disjoint(absent.identity(left), absent.identity(right)));
        BluetoothDeviceGroups broken = new BluetoothDeviceGroups(() -> {throw new IllegalStateException("ROM cache unavailable");});
        assertTrue(Collections.disjoint(broken.identity(left), broken.identity(right)));
    }

    public static final class Cache {
        final List<Object> roots = new ArrayList<>();
        public List<Object> getCachedDevicesCopy() { return new ArrayList<>(roots); }
    }

    /** Samsung builds expose fields for member/lead links even where the AOSP accessors are absent. */
    public static final class SamsungCached {
        public final BluetoothDevice mDevice;
        public int mGroupId = -1;
        public final Set<Object> mMemberDevices = new LinkedHashSet<>();
        public SamsungCached mLeadDevice, mSubDevice;
        SamsungCached(BluetoothDevice device) { mDevice = device; }
    }

    public static final class AospCached {
        final BluetoothDevice device;
        int group = -1;
        long hearing;
        AospCached(BluetoothDevice device) { this.device = device; }
        public BluetoothDevice getDevice() { return device; }
        public int getGroupId() { return group; }
        public long getHiSyncId() { return hearing; }
        public Set<Object> getMemberDevice() { return Collections.emptySet(); }
        public Object getSubDevice() { return null; }
    }
}
