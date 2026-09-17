package com.helper.urlfeeder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full device-status overview with privacy-safe diagnostic report actions. */
public class StatusActivity extends Activity {
    private static final int REQ_EXPORT_REPORT = 701;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final java.util.ArrayList<Button> reportActions = new java.util.ArrayList<Button>();
    private LinearLayout content;
    private TextView subtitle;
    private DiagnosticReport.Snapshot snapshot;
    private String pendingExportText;
    private boolean stopped;
    private boolean collecting;
    private int refreshGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) pendingExportText = state.getString("pending_export_text");
        UiStyle.systemBars(this);
        setContentView(buildContent());
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (pendingExportText != null) out.putString("pending_export_text", pendingExportText);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        stopped = true;
        refreshGeneration++;
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiStyle.appBackground());
        root.setPadding(dp(12), dp(8), dp(12), dp(12));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        Button back = action("‹", "返回", new View.OnClickListener() {
            public void onClick(View view) { finish(); }
        });
        heading.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, 0, 0);
        TextView title = text("状态总览", 18, UiStyle.TEXT, true);
        subtitle = text("正在采集设备状态…", 11, UiStyle.TEXT_3, false);
        titles.addView(title); titles.addView(subtitle);
        heading.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(heading);

        HorizontalScrollView actionScroll = new HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, dp(8));
        Button refreshAction = action("↻", "刷新状态", new View.OnClickListener() { public void onClick(View view) { refresh(); } });
        Button previewAction = action("报告", "预览诊断报告", new View.OnClickListener() { public void onClick(View view) { preview(); } });
        Button copyAction = action("复制", "复制诊断报告", new View.OnClickListener() { public void onClick(View view) { copy(); } });
        Button exportAction = action("导出", "导出诊断报告", new View.OnClickListener() { public void onClick(View view) { export(); } });
        Button shareAction = action("分享", "分享诊断报告文件", new View.OnClickListener() { public void onClick(View view) { share(); } });
        reportActions.add(refreshAction); reportActions.add(previewAction); reportActions.add(copyAction); reportActions.add(exportAction); reportActions.add(shareAction);
        actions.addView(refreshAction, actionParams()); actions.addView(previewAction, actionParams()); actions.addView(copyAction, actionParams());
        actions.addView(exportAction, actionParams()); actions.addView(shareAction, actionParams());
        actionScroll.addView(actions);
        root.addView(actionScroll, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private void refresh() {
        if (collecting) return;
        collecting = true;
        final int generation = ++refreshGeneration;
        setActionsEnabled(false);
        subtitle.setText("正在采集设备状态…");
        content.removeAllViews();
        TextView loading = text("正在读取权限、服务、网络和数据状态", 13, UiStyle.TEXT_3, false);
        loading.setGravity(Gravity.CENTER); loading.setPadding(dp(12), dp(40), dp(12), dp(40)); content.addView(loading);
        worker.execute(new Runnable() {
            public void run() {
                DiagnosticReport.Snapshot result = null;
                try { result = DiagnosticReport.collect(StatusActivity.this); } catch (Throwable ignored) {}
                final DiagnosticReport.Snapshot loaded = result;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (stopped || generation != refreshGeneration) return;
                        collecting = false;
                        setActionsEnabled(true);
                        if (loaded == null) {
                            subtitle.setText("状态采集失败");
                            content.removeAllViews();
                            TextView error = text("无法读取设备状态，请稍后重试", 13, UiStyle.AMBER, false);
                            error.setGravity(Gravity.CENTER); error.setPadding(dp(12), dp(40), dp(12), dp(40)); content.addView(error);
                            return;
                        }
                        snapshot = loaded;
                        render();
                    }
                });
            }
        });
    }

    private void render() {
        content.removeAllViews();
        if (snapshot == null) return;
        subtitle.setText("正常 " + snapshot.okCount + " · 需关注 " + snapshot.warningCount + " · 共 " + snapshot.items.size() + " 项");

        LinearLayout summary = panel();
        TextView summaryTitle = text(snapshot.warningCount == 0 ? "关键状态正常" : snapshot.warningCount + " 项需要关注",
                16, snapshot.warningCount == 0 ? UiStyle.ACCENT : UiStyle.AMBER, true);
        summary.addView(summaryTitle);
        TextView privacy = text("报告不包含网址历史、剪贴板、密钥、规则正文、设置值或审计详情。", 11, UiStyle.TEXT_3, false);
        privacy.setPadding(0, dp(5), 0, 0); summary.addView(privacy);
        content.addView(summary);

        Map<String, List<DiagnosticReport.Item>> groups = DiagnosticReport.grouped(snapshot);
        for (Map.Entry<String, List<DiagnosticReport.Item>> entry : groups.entrySet()) {
            LinearLayout section = panel();
            section.addView(text(entry.getKey(), 14, UiStyle.TEXT, true));
            for (DiagnosticReport.Item item : entry.getValue()) section.addView(statusRow(item));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(10);
            content.addView(section, params);
        }
    }

    private View statusRow(DiagnosticReport.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        int tone = item.level == DiagnosticReport.OK ? UiStyle.ACCENT
                : (item.level == DiagnosticReport.WARN ? UiStyle.AMBER : UiStyle.TEXT_3);
        TextView mark = text(item.level == DiagnosticReport.OK ? "●" : (item.level == DiagnosticReport.WARN ? "!" : "·"), 12, tone, true);
        mark.setGravity(Gravity.CENTER); row.addView(mark, new LinearLayout.LayoutParams(dp(25), dp(28)));

        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(item.label, 13, UiStyle.TEXT_2, true));
        if (item.detail != null && item.detail.length() > 0) words.addView(text(item.detail, 10, UiStyle.TEXT_3, false));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView value = text(item.value, 12, tone, true);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); value.setMaxWidth(dp(210));
        row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private void preview() {
        if (!ready()) return;
        ScrollView scroll = new ScrollView(this);
        TextView report = text(DiagnosticReport.text(snapshot), 12, UiStyle.TEXT_2, false);
        report.setTypeface(Typeface.MONOSPACE); report.setTextIsSelectable(true); report.setPadding(dp(14), dp(12), dp(14), dp(16));
        scroll.addView(report);
        new AlertDialog.Builder(this).setTitle("诊断报告预览").setView(scroll).setPositiveButton("关闭", null).show();
    }

    private void copy() {
        if (!ready()) return;
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) throw new IllegalStateException("clipboard unavailable");
            manager.setPrimaryClip(ClipData.newPlainText("万能转发器诊断报告", DiagnosticReport.text(snapshot)));
            Toast.makeText(this, "诊断报告已复制", Toast.LENGTH_SHORT).show();
            AuditLog.record(this, "DIAGNOSTIC", "OK", "诊断报告已复制");
        } catch (Exception e) { Toast.makeText(this, "复制诊断报告失败", Toast.LENGTH_LONG).show(); }
    }

    private void export() {
        if (!ready()) return;
        if (Build.VERSION.SDK_INT < 29) {
            pendingExportText = DiagnosticReport.text(snapshot);
            try {
                Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                create.addCategory(Intent.CATEGORY_OPENABLE);
                create.setType("text/plain");
                create.putExtra(Intent.EXTRA_TITLE, DiagnosticReport.reportFileName());
                startActivityForResult(create, REQ_EXPORT_REPORT);
            } catch (Exception e) {
                pendingExportText = null;
                Toast.makeText(this, "无法打开系统文件保存器", Toast.LENGTH_LONG).show();
            }
            return;
        }
        subtitle.setText("正在导出诊断报告…");
        setActionsEnabled(false);
        final DiagnosticReport.Snapshot target = snapshot;
        worker.execute(new Runnable() {
            public void run() {
                try {
                    final String path = DiagnosticReport.export(StatusActivity.this, target);
                    AuditLog.record(StatusActivity.this, "DIAGNOSTIC", "OK", "诊断报告已导出");
                    postToast("诊断报告已导出\n" + path);
                } catch (final Exception e) {
                    AuditLog.record(StatusActivity.this, "DIAGNOSTIC", "FAIL", "诊断报告导出失败");
                    postToast("导出失败：" + e.getClass().getSimpleName());
                }
            }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT_REPORT) return;
        final String reportText = pendingExportText != null ? pendingExportText
                : (snapshot == null ? null : DiagnosticReport.text(snapshot));
        pendingExportText = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || reportText == null) {
            Toast.makeText(this, "未选择导出位置", Toast.LENGTH_SHORT).show(); return;
        }
        final Uri destination = data.getData();
        setActionsEnabled(false); subtitle.setText("正在写入诊断报告…");
        worker.execute(new Runnable() { public void run() {
            try {
                DiagnosticReport.writeTextUri(StatusActivity.this, destination, reportText);
                AuditLog.record(StatusActivity.this, "DIAGNOSTIC", "OK", "诊断报告已导出");
                postToast("诊断报告已保存到所选位置");
            } catch (Exception e) {
                AuditLog.record(StatusActivity.this, "DIAGNOSTIC", "FAIL", "诊断报告导出失败");
                postToast("导出失败：" + e.getClass().getSimpleName());
            }
        }});
    }

    private void share() {
        if (!ready()) return;
        setActionsEnabled(false); subtitle.setText("正在生成分享文件…");
        final DiagnosticReport.Snapshot target = snapshot;
        worker.execute(new Runnable() { public void run() {
            try {
                final File file = DiagnosticReport.writeCache(StatusActivity.this, target);
                runOnUiThread(new Runnable() { public void run() {
                    if (stopped) return;
                    setActionsEnabled(true); restoreSubtitle();
                    try {
                        Uri uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + file.getName());
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("text/plain"); send.putExtra(Intent.EXTRA_STREAM, uri);
                        send.putExtra(Intent.EXTRA_SUBJECT, "万能转发器诊断报告");
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        send.setClipData(ClipData.newRawUri("诊断报告", uri));
                        startActivity(Intent.createChooser(send, "分享诊断报告"));
                        AuditLog.record(StatusActivity.this, "DIAGNOSTIC", "OK", "诊断报告已打开分享面板");
                    } catch (Exception e) { Toast.makeText(StatusActivity.this, "无法打开分享面板", Toast.LENGTH_LONG).show(); }
                }});
            } catch (Exception e) { postToast("生成分享文件失败"); }
        }});
    }

    private boolean ready() {
        if (snapshot != null && !collecting) return true;
        Toast.makeText(this, "状态尚未采集完成", Toast.LENGTH_SHORT).show(); return false;
    }

    private void setActionsEnabled(boolean enabled) {
        for (Button button : reportActions) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private void restoreSubtitle() {
        if (snapshot != null) subtitle.setText("正常 " + snapshot.okCount + " · 需关注 " + snapshot.warningCount + " · 共 " + snapshot.items.size() + " 项");
    }

    private void postToast(final String message) {
        runOnUiThread(new Runnable() { public void run() {
            if (stopped) return;
            setActionsEnabled(true);
            Toast.makeText(StatusActivity.this, message, Toast.LENGTH_LONG).show();
            restoreSubtitle();
        }});
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12)); panel.setBackground(UiStyle.panel(this)); return panel;
    }

    private Button action(String label, String description, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(label); button.setTextSize(12); button.setTextColor(UiStyle.TEXT);
        button.setAllCaps(false); button.setGravity(Gravity.CENTER); button.setContentDescription(description);
        button.setBackground(UiStyle.button(this, UiStyle.SURFACE_2)); button.setOnClickListener(listener); Fonts.apply(button); return button;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(88), dp(44)); params.rightMargin = dp(7); return params;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        Fonts.apply(view); if (bold) view.setTypeface(Fonts.nerd(this), Typeface.BOLD); return view;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
