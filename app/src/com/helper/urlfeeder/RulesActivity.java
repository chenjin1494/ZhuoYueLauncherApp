package com.helper.urlfeeder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A compact editor for the system firewall's IP/host rules. */
public class RulesActivity extends LoggedActivity {
    private static final String PREFS = "pf";
    private static final String SNAP_BLACK = "rules_snap_black";
    private static final String SNAP_WHITE = "rules_snap_white";
    private static final String SNAP_TIME = "rules_snap_time";

    private final Handler ui = new Handler();
    private SharedPreferences prefs;
    private LinearLayout blackList;
    private LinearLayout whiteList;
    private TextView blackCount;
    private TextView whiteCount;
    private TextView status;
    private TextView warning;
    private TextView restoreButton;
    private EditText input;
    private volatile boolean busy;

    private interface Change {
        void apply(List<String> black, List<String> white) throws Exception;
    }

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, 0);
        UiStyle.systemBars(this);
        buildUi();
        refreshRules(true);
    }

    protected void onResume() {
        super.onResume();
        updateWarning();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiStyle.appBackground());

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bar.setBackgroundColor(UiStyle.BG_TOP);
        TextView back = action("‹", "返回", new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        TextView title = text("规则管理", 18, UiStyle.TEXT);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(7), 0, 0, 0);
        TextView refresh = action("↻", "刷新规则", new View.OnClickListener() {
            public void onClick(View v) { refreshRules(false); }
        });
        bar.addView(back, lp(dp(48), dp(48)));
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        bar.addView(refresh, lp(dp(48), dp(48)));
        root.addView(bar, lp(-1, -2));

        warning = text("", 12, UiStyle.AMBER);
        warning.setPadding(dp(12), dp(9), dp(12), dp(9));
        warning.setBackground(UiStyle.button(this,0xFF332817));
        root.addView(warning, lp(-1, -2));

        LinearLayout addRow = new LinearLayout(this);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setPadding(dp(10), dp(10), dp(10), dp(7));
        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setHint("IP 或主机名");
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setTextColor(UiStyle.TEXT); input.setHintTextColor(UiStyle.TEXT_3);
        input.setBackground(UiStyle.field(this));
        Fonts.apply(input);
        addRow.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView addBlack = compactButton("加入黑名单", 0xFFB53C3C, new View.OnClickListener() {
            public void onClick(View v) { addRule(true); }
        });
        LinearLayout.LayoutParams abp = lp(-2, dp(48));
        abp.leftMargin = dp(6);
        addRow.addView(addBlack, abp);
        TextView addWhite = compactButton("加入白名单", 0xFF28704B, new View.OnClickListener() {
            public void onClick(View v) { addRule(false); }
        });
        LinearLayout.LayoutParams awp = lp(-2, dp(48));
        awp.leftMargin = dp(6);
        addRow.addView(addWhite, awp);
        root.addView(addRow, lp(-1, -2));

        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(dp(10), 0, dp(10), dp(8));
        restoreButton = compactButton("恢复快照", 0xFF4C5967, new View.OnClickListener() {
            public void onClick(View v) { restoreSnapshot(); }
        });
        tools.addView(restoreButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView preset = compactButton("加入上报拦截", 0xFF405D85, new View.OnClickListener() {
            public void onClick(View v) { addBlockingPreset(); }
        });
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, dp(48), 1);
        pp.leftMargin = dp(6);
        tools.addView(preset, pp);
        TextView clear = compactButton("清空全部", 0xFF8F3434, new View.OnClickListener() {
            public void onClick(View v) { confirmClear(); }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(48), 1);
        cp.leftMargin = dp(6);
        tools.addView(clear, cp);
        root.addView(tools, lp(-1, -2));

        status = text("正在读取规则…", 12, UiStyle.TEXT_3);
        status.setPadding(dp(12), dp(5), dp(12), dp(7));
        root.addView(status, lp(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(10), dp(2), dp(10), dp(14));
        blackCount = sectionTitle("阻止规则", UiStyle.DANGER);
        body.addView(blackCount, lp(-1, -2));
        blackList = new LinearLayout(this);
        blackList.setOrientation(LinearLayout.VERTICAL);
        body.addView(blackList, lp(-1, -2));
        whiteCount = sectionTitle("允许规则", UiStyle.ACCENT);
        LinearLayout.LayoutParams wtp = lp(-1, -2);
        wtp.topMargin = dp(10);
        body.addView(whiteCount, wtp);
        whiteList = new LinearLayout(this);
        whiteList.setOrientation(LinearLayout.VERTICAL);
        body.addView(whiteList, lp(-1, -2));
        scroll.addView(body, lp(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        updateWarning();
        updateSnapshotLabel();
    }

    private void refreshRules(final boolean initial) {
        if (!beginWork("正在读取规则…")) return;
        new Thread(new Runnable() {
            public void run() {
                FwRules.Res b = FwRules.getBlackRules(RulesActivity.this);
                FwRules.Res w = FwRules.getWhiteRules(RulesActivity.this);
                if (!b.good() || !w.good()) {
                    finishWork(false, "读取失败，管控防火墙服务可能未启用");
                    audit("rules_refresh", "failure", errors(b, w));
                    return;
                }
                final List<String> black = cleanCopy(b.data);
                final List<String> white = cleanCopy(w.data);
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        render(black, white);
                        status.setText("已读取：黑 " + black.size() + " / 白 " + white.size());
                        updateSnapshotLabel();
                        if (!initial) Toast.makeText(RulesActivity.this, "规则已刷新", Toast.LENGTH_SHORT).show();
                    }
                });
                audit("rules_refresh", "success", "black=" + black.size() + ", white=" + white.size());
            }
        }, "rules-refresh").start();
    }

    private void addRule(final boolean black) {
        final String rule = input.getText().toString();
        String invalid = validate(rule);
        if (invalid != null) {
            input.setError(invalid);
            audit(black ? "rules_add_black" : "rules_add_white", "failure", invalid);
            return;
        }
        mutate(black ? "rules_add_black" : "rules_add_white", black ? "添加黑名单" : "添加白名单", new Change() {
            public void apply(List<String> blacks, List<String> whites) {
                List<String> target = black ? blacks : whites;
                if (!target.contains(rule)) target.add(rule);
            }
        }, new Runnable() {
            public void run() {
                input.setText("");
                try {
                    ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
                } catch (Exception ignored) {}
            }
        });
    }

    private void deleteRule(final boolean black, final String rule) {
        mutate(black ? "rules_delete_black" : "rules_delete_white", "删除规则", new Change() {
            public void apply(List<String> blacks, List<String> whites) {
                (black ? blacks : whites).remove(rule);
            }
        }, null);
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
            .setTitle("清空全部规则")
            .setMessage("黑名单和白名单都会被清空。操作前会保存快照，可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    mutate("rules_clear", "清空全部", new Change() {
                        public void apply(List<String> black, List<String> white) {
                            black.clear();
                            white.clear();
                        }
                    }, null);
                }
            }).show();
    }

    private void addBlockingPreset() {
        mutate("rules_preset", "加入上报拦截", new Change() {
            public void apply(List<String> black, List<String> white) throws Exception {
                Set<String> ips = new LinkedHashSet<String>();
                for (String host : BlockVpnService.BLOCK_HOSTS) {
                    try {
                        InetAddress[] addresses = InetAddress.getAllByName(host);
                        for (InetAddress address : addresses) ips.add(address.getHostAddress());
                    } catch (Exception ignored) {}
                }
                if (ips.isEmpty()) throw new Exception("未能解析任何拦截主机");
                for (String ip : ips) if (!black.contains(ip)) black.add(ip);
            }
        }, null);
    }

    private void restoreSnapshot() {
        if (!prefs.contains(SNAP_TIME)) {
            Toast.makeText(this, "没有可恢复的快照", Toast.LENGTH_SHORT).show();
            audit("rules_restore", "failure", "snapshot missing");
            return;
        }
        final String savedBlack = prefs.getString(SNAP_BLACK, "");
        final String savedWhite = prefs.getString(SNAP_WHITE, "");
        mutate("rules_restore", "恢复快照", new Change() {
            public void apply(List<String> black, List<String> white) {
                black.clear();
                black.addAll(FwRules.split(savedBlack));
                white.clear();
                white.addAll(FwRules.split(savedWhite));
            }
        }, null);
    }

    private void mutate(final String category, final String label, final Change change, final Runnable successUi) {
        if (!beginWork(label + "…")) return;
        new Thread(new Runnable() {
            public void run() {
                FwRules.Res oldBlackRes = FwRules.getBlackRules(RulesActivity.this);
                FwRules.Res oldWhiteRes = FwRules.getWhiteRules(RulesActivity.this);
                if (!oldBlackRes.good() || !oldWhiteRes.good()) {
                    finishWork(false, label + "失败：无法读取当前规则");
                    audit(category, "failure", "read current: " + errors(oldBlackRes, oldWhiteRes));
                    return;
                }
                List<String> oldBlack = cleanCopy(oldBlackRes.data);
                List<String> oldWhite = cleanCopy(oldWhiteRes.data);
                if (!snapshot(oldBlack, oldWhite)) {
                    finishWork(false, label + "失败：无法保存快照");
                    audit(category, "failure", "snapshot commit failed");
                    return;
                }
                List<String> nextBlack = new ArrayList<String>(oldBlack);
                List<String> nextWhite = new ArrayList<String>(oldWhite);
                try {
                    change.apply(nextBlack, nextWhite);
                } catch (Exception e) {
                    finishWork(false, label + "失败：" + message(e));
                    audit(category, "failure", message(e));
                    return;
                }
                String failure = rebuild(nextBlack, nextWhite);
                if (failure != null) {
                    String rollback = rebuild(oldBlack, oldWhite);
                    String detail = failure + (rollback == null ? "; rolled back" : "; rollback failed: " + rollback);
                    finishWork(false, label + "失败，" + (rollback == null ? "已回退" : "回退也失败"));
                    audit(category, "failure", detail);
                    return;
                }
                final List<String> shownBlack = cleanCopy(nextBlack);
                final List<String> shownWhite = cleanCopy(nextWhite);
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        render(shownBlack, shownWhite);
                        status.setText(label + "完成：黑 " + shownBlack.size() + " / 白 " + shownWhite.size());
                        updateSnapshotLabel();
                        if (successUi != null) successUi.run();
                        Toast.makeText(RulesActivity.this, label + "完成", Toast.LENGTH_SHORT).show();
                    }
                });
                audit(category, "success", "black=" + shownBlack.size() + ", white=" + shownWhite.size());
            }
        }, "rules-mutate").start();
    }

    private String rebuild(List<String> black, List<String> white) {
        FwRules.Res clear = FwRules.clearIpHostRules(this);
        if (!clear.applied()) return "clear failed: " + error(clear);
        for (String rule : white) {
            FwRules.Res result = FwRules.addWhiteRule(this, rule);
            if (!result.applied()) return "white rule failed: " + rule + ": " + error(result);
        }
        for (String rule : black) {
            FwRules.Res result = FwRules.addBlackRule(this, rule);
            if (!result.applied()) return "black rule failed: " + rule + ": " + error(result);
        }
        FwRules.Res write = FwRules.writeToFile(this);
        return write.applied() ? null : "write failed: " + error(write);
    }

    private boolean snapshot(List<String> black, List<String> white) {
        return prefs.edit()
            .putString(SNAP_BLACK, FwRules.join(black))
            .putString(SNAP_WHITE, FwRules.join(white))
            .putLong(SNAP_TIME, System.currentTimeMillis())
            .commit();
    }

    private void render(List<String> black, List<String> white) {
        blackList.removeAllViews();
        whiteList.removeAllViews();
        blackCount.setText("黑名单  " + black.size());
        whiteCount.setText("白名单  " + white.size());
        renderList(blackList, black, true);
        renderList(whiteList, white, false);
    }

    private void renderList(LinearLayout parent, List<String> rules, final boolean black) {
        if (rules.isEmpty()) {
            TextView empty = text("暂无规则", 13, UiStyle.TEXT_3);
            empty.setPadding(dp(9), dp(10), dp(9), dp(10));
            parent.addView(empty, lp(-1, -2));
            return;
        }
        for (final String rule : rules) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(11), dp(5), dp(5), dp(5));
            row.setBackground(UiStyle.panel(this,UiStyle.SURFACE));
            TextView name = text(rule, 13, UiStyle.TEXT);
            name.setGravity(Gravity.CENTER_VERTICAL);
            name.setSingleLine(false);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView del = action("×", "删除 " + rule, new View.OnClickListener() {
                public void onClick(View v) { deleteRule(black, rule); }
            });
            del.setTextColor(UiStyle.DANGER);
            row.addView(del, lp(dp(48), dp(48)));
            LinearLayout.LayoutParams rp = lp(-1, -2);
            rp.bottomMargin = dp(1);
            parent.addView(row, rp);
        }
    }

    private boolean beginWork(String message) {
        if (busy) {
            Toast.makeText(this, "请等待当前操作完成", Toast.LENGTH_SHORT).show();
            return false;
        }
        busy = true;
        status.setText(message);
        return true;
    }

    private void finishWork(final boolean success, final String message) {
        ui.post(new Runnable() {
            public void run() {
                busy = false;
                status.setText(message);
                Toast.makeText(RulesActivity.this, message, success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                updateSnapshotLabel();
            }
        });
    }

    private void updateWarning() {
        boolean active = prefs.getBoolean("fw_block_zy", false);
        warning.setVisibility(active ? View.VISIBLE : View.GONE);
        warning.setText("注意：卓越防火墙拦截 fw_block_zy 正在生效，手动编辑可能影响其一键复原状态。");
    }

    private void updateSnapshotLabel() {
        long time = prefs.getLong(SNAP_TIME, 0);
        restoreButton.setEnabled(time > 0 && !busy);
        restoreButton.setAlpha(time > 0 ? 1f : .45f);
        restoreButton.setText(time > 0
            ? "恢复快照 " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(time))
            : "恢复快照");
    }

    private String validate(String value) {
        if (value == null || value.length() == 0) return "规则不能为空";
        if (value.length() > 255) return "规则不能超过 255 个字符";
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return "规则不能包含空白字符";
        }
        return null;
    }

    private List<String> cleanCopy(List<String> source) {
        ArrayList<String> copy = new ArrayList<String>();
        if (source != null) {
            for (String rule : source) if (rule != null && rule.length() > 0) copy.add(rule);
        }
        return copy;
    }

    private void audit(String category, String result, String detail) {
        try { OperationLog.event(category, "RULE_ACTION", result, detail); } catch (Throwable ignored) {}
    }

    private String errors(FwRules.Res black, FwRules.Res white) {
        return "black=" + error(black) + ", white=" + error(white);
    }

    private String error(FwRules.Res result) {
        return result == null ? "null result" : (result.err == null ? "operation rejected" : result.err);
    }

    private String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private TextView sectionTitle(String label, int color) {
        TextView view = text(label, 13, color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(4), dp(6), dp(4), dp(6));
        return view;
    }

    private TextView action(String label, final String description, final View.OnClickListener listener) {
        TextView view = text(label, 25, 0xFFF4F6F8);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setBackground(selectableBackground(0x00000000));
        view.setOnClickListener(loggedClick(description,listener));
        return view;
    }

    private TextView compactButton(final String label, int color, final View.OnClickListener listener) {
        TextView view = text(label, 12, Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(9), 0, dp(9), 0);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.setMinWidth(dp(48));
        view.setBackground(UiStyle.button(this,color));
        view.setOnClickListener(loggedClick(label,listener));
        return view;
    }

    private View.OnClickListener loggedClick(final String action, final View.OnClickListener listener) {
        return new View.OnClickListener() { public void onClick(View view) {
            OperationLog.Span span=OperationLog.begin("UI","BUTTON_CLICK","activity=RulesActivity action="+action);
            try { listener.onClick(view); OperationLog.result(span,"DISPATCHED","handler returned"); }
            catch (Throwable error) { OperationLog.fail(span,error); if(error instanceof RuntimeException) throw (RuntimeException)error; if(error instanceof Error) throw (Error)error; throw new RuntimeException(error); }
        }};
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLetterSpacing(0);
        Fonts.apply(view);
        return view;
    }

    private GradientDrawable selectableBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(4));
        return drawable;
    }

    private GradientDrawable fieldBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        drawable.setStroke(dp(1), 0xFFCBD0D6);
        drawable.setCornerRadius(dp(4));
        return drawable;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + .5f);
    }
}
