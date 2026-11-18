package dev.android.dynamicisland.island.source;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.xposed.XLog;

/** Google artwork stays in the companion APK. */
final class PixelBudsIcons {
    private static final String[] COMPONENTS = {
            "gs_tws_left_bud_vd_theme_24", "gs_tws_right_bud_vd_theme_24", "gs_tws_case_vd_theme_24"
    };
    private static final char FIRST_MARKER = '\ue000';
    final Drawable pair;
    private final Drawable[] components = new Drawable[COMPONENTS.length];

    PixelBudsIcons(Context context) {
        Resources resources;
        try {
            resources = context.getPackageManager().getResourcesForApplication(PixelBudsBatteryBridge.COMPANION);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            pair = null;
            return;
        }
        pair = load(resources, "gs_tws_buds_vd_theme_24");
        int size = Math.round(16 * context.getResources().getDisplayMetrics().density);
        int loaded = pair == null ? 0 : 1;
        for (int i = 0; i < COMPONENTS.length; i++) {
            Drawable icon = load(resources, COMPONENTS[i]);
            if (icon != null) {
                icon.setBounds(0, 0, size, size);
                loaded++;
            }
            components[i] = icon;
        }
        XLog.d("Pixel Buds 2a: Google icons loaded " + loaded);
    }

    private static Drawable load(Resources resources, String name) {
        try {
            int id = resources.getIdentifier(name, "drawable", PixelBudsBatteryBridge.COMPANION);
            if (id == 0) return null;
            Drawable icon = resources.getDrawable(id, null).mutate();
            icon.setTint(Color.WHITE);
            return icon;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    CharSequence batteryDescription(Context context, String left, String right, String chargingCase) {
        return batteryDescription(context, components, left, right, chargingCase);
    }

    static CharSequence batteryDescription(Context context, Drawable[] icons, String... levels) {
        for (int i = 0; i < icons.length; i++) {
            if (icons[i] != null) levels[i] = (char) (FIRST_MARKER + i) + " " + levels[i];
        }
        String text = IslandText.get(context, "island_buds_battery", (Object[]) levels);
        SpannableString description = new SpannableString(text);
        for (int i = 0; i < icons.length; i++) {
            int index = text.indexOf(FIRST_MARKER + i);
            if (index >= 0) {
                description.setSpan(new ImageSpan(icons[i], ImageSpan.ALIGN_CENTER), index, index + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return description;
    }
}
