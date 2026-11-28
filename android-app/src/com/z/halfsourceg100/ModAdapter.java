package com.z.halfsourceg100;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** List adapter for one mod section (TC or regular). */
final class ModAdapter extends BaseAdapter {

    interface State {
        boolean isTcSelected(String dirName);
        boolean isRegularEnabled(ModScanner.Entry e);
    }

    private final Context ctx;
    private final boolean tcMode;
    private final State state;
    private List<ModScanner.Entry> items;

    ModAdapter(Context ctx, boolean tcMode, State state) {
        this.ctx = ctx;
        this.tcMode = tcMode;
        this.state = state;
    }

    void setItems(List<ModScanner.Entry> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items == null ? 0 : items.size(); }
    @Override public Object getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override public View getView(int i, View cv, ViewGroup parent) {
        if (cv == null)
            cv = LayoutInflater.from(ctx).inflate(R.layout.item_mod, parent, false);
        ModScanner.Entry e = items.get(i);

        TextView name = cv.findViewById(R.id.name);
        TextView badges = cv.findViewById(R.id.badges);
        TextView path = cv.findViewById(R.id.path);
        CheckBox check = cv.findViewById(R.id.check);

        String title = e.info.title;
        name.setText(title != null && !title.isEmpty() ? title : e.dirName);

        StringBuilder b = new StringBuilder(classLabel(e));
        if (e.isTc && e.needsRename)
            b.append(" \u00b7 WILL RENAME ON LAUNCH");
        if (e.mapCount > 0)
            b.append(" \u00b7 ").append(e.mapCount).append(e.mapCount > 1 ? " maps" : " map");
        if (e.sizeBytes > 0) b.append(" \u00b7 ").append(humanSize(e.sizeBytes));
        if (e.disabled) b.append(" \u00b7 DISABLED");
        badges.setText(b.toString());
        badges.setTextColor(ctx.getColor(colorForClass(e)));

        path.setText((e.isTc ? "srceng/" : "hl2/custom" + (e.disabled ? "_disabled/" : "/"))
                + e.dirName);

        check.setChecked(tcMode ? state.isTcSelected(e.dirName) : state.isRegularEnabled(e));
        return cv;
    }

    static String classLabel(ModScanner.Entry e) {
        if (e.isVpkFile) return "VPK ADDON";
        if (e.isTc) return "TC \u00b7 " + e.info.modClass;
        return e.info.modClass + " ADDON";
    }

    static int colorForClass(ModScanner.Entry e) {
        switch (e.info.modClass) {
            case "SOURCE_MOD":
            case "CONTENT_ONLY":
                return R.color.ok;
            case "DLL_ONLY":
            case "STANDALONE_TC":
                return R.color.warn;
            case "BROKEN_GAMEINFO":
            case "EMPTY":
                return R.color.err;
            default:
                return R.color.warn;
        }
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
    }
}
