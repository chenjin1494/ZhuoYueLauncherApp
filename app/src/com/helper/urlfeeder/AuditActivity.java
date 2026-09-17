package com.helper.urlfeeder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Programmatic viewer and export utility for {@link AuditLog}. */
public class AuditActivity extends LoggedActivity {
    private static final int MAX_RECORDS = 5000;
    private static final String ALL = "全部分类";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<AuditLog.Entry> entries = new ArrayList<AuditLog.Entry>();
    private final ArrayList<AuditLog.Entry> visible = new ArrayList<AuditLog.Entry>();

    private Spinner category;
    private EditText search;
    private TextView body;
    private TextView subtitle;
    private boolean stopped;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiStyle.systemBars(this);
        setContentView(buildContent());
        load();
    }

    @Override protected void onDestroy() {
        stopped = true;
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
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        heading.addView(back, backParams);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, dp(8), 0);
        TextView title = text("完整操作日志", 18, UiStyle.TEXT);
        title.setTypeface(Fonts.nerd(this), Typeface.BOLD);
        subtitle = text("正在读取…", 11, UiStyle.TEXT_3);
        titles.addView(title);
        titles.addView(subtitle);
        heading.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView actionScroll = new HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, dp(8));
        actions.addView(action("↻", "刷新", new View.OnClickListener() {
            public void onClick(View view) { load(); }
        }), actionParams());
        actions.addView(action("复制", "复制当前结果", new View.OnClickListener() {
            public void onClick(View view) { copyVisible(); }
        }), actionParams());
        actions.addView(action("导出", "导出日志", new View.OnClickListener() {
            public void onClick(View view) { exportLog(); }
        }), actionParams());
        Button clearAction = action("清空", "清空日志", new View.OnClickListener() {
            public void onClick(View view) { confirmClear(); }
        });
        clearAction.setTextColor(UiStyle.DANGER);
        clearAction.setBackground(UiStyle.button(this,0xFF34201F));
        actions.addView(clearAction, actionParams());
        actionScroll.addView(actions, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(actionScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView privacy = text("完整 URL 可能含查询凭据/令牌；命令输出可能含个人、账户和系统数据，分享前请检查。", 11, UiStyle.AMBER);
        privacy.setPadding(dp(4), 0, dp(4), dp(8));
        root.addView(privacy);

        LinearLayout filters = new LinearLayout(this);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        category = new Spinner(this);
        category.setBackground(UiStyle.field(this));
        category.setPadding(dp(8), 0, dp(8), 0);
        category.setAdapter(new CategoryAdapter(singleton(ALL)));
        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { render(); }
            public void onNothingSelected(AdapterView<?> parent) { render(); }
        });
        filters.addView(category, new LinearLayout.LayoutParams(0, dp(48), 0.38f));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索内容");
        search.setHintTextColor(UiStyle.TEXT_3);
        search.setTextColor(UiStyle.TEXT);
        search.setTextSize(13);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(UiStyle.field(this));
        Fonts.apply(search);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(0, dp(48), 0.62f);
        searchParams.leftMargin = dp(8);
        filters.addView(search, searchParams);
        root.addView(filters, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render(); }
            public void afterTextChanged(Editable s) {}
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(UiStyle.panel(this,0xF2101719));
        body = text("", 12, UiStyle.TEXT_2);
        body.setTextIsSelectable(true);
        body.setGravity(Gravity.TOP | Gravity.START);
        body.setLineSpacing(dp(2), 1f);
        body.setPadding(dp(12), dp(12), dp(12), dp(16));
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(10);
        root.addView(scroll, scrollParams);
        return root;
    }

    private void load() {
        subtitle.setText("正在读取…");
        worker.execute(new Runnable() {
            public void run() {
                final List<AuditLog.Entry> loaded = AuditLog.read(AuditActivity.this, MAX_RECORDS);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (stopped) return;
                        entries.clear();
                        entries.addAll(loaded);
                        rebuildCategories();
                        render();
                    }
                });
            }
        });
    }

    private void rebuildCategories() {
        String selected = category.getSelectedItem() == null ? ALL : category.getSelectedItem().toString();
        ArrayList<String> values = new ArrayList<String>();
        values.add(ALL);
        Set<String> found = AuditLog.categories(entries);
        for (String value : found) if (value.length() > 0) values.add(value);
        category.setAdapter(new CategoryAdapter(values));
        int position = values.indexOf(selected);
        category.setSelection(position < 0 ? 0 : position);
    }

    private void render() {
        if (body == null || category == null || search == null) return;
        String selected = category.getSelectedItem() == null ? ALL : category.getSelectedItem().toString();
        String needle = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        visible.clear();
        StringBuilder output = new StringBuilder();
        for (AuditLog.Entry entry : entries) {
            if (!ALL.equals(selected) && !selected.equals(entry.category)) continue;
            String haystack = (entry.category + "\n" + entry.action + "\n" + entry.result + "\n"
                    + entry.thread + "\n" + entry.durationMs + "\n" + entry.detail)
                    .toLowerCase(Locale.getDefault());
            if (needle.length() > 0 && !haystack.contains(needle)) continue;
            visible.add(entry);
            if (output.length() > 0) output.append("\n────────────────────────\n");
            output.append(entry.displayText());
        }
        body.setText(output.length() == 0 ? "没有匹配的审计记录" : output.toString());
        subtitle.setText("显示 " + visible.size() + " 条 · 共读取 " + entries.size() + " 条 · 含动作/结果/耗时/线程/完整输出");
    }

    private void copyVisible() {
        if (visible.isEmpty()) {
            toast("没有可复制的记录");
            return;
        }
        StringBuilder text = new StringBuilder();
        for (AuditLog.Entry entry : visible) {
            if (text.length() > 0) text.append("\n\n");
            text.append(entry.displayText());
        }
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            toast("剪贴板不可用");
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText("operation log", text));
        OperationLog.event("LOG","COPY_VISIBLE","OK","records="+visible.size());
        toast("已复制 " + visible.size() + " 条记录");
    }

    private void exportLog() {
        subtitle.setText("正在导出…");
        worker.execute(new Runnable() {
            public void run() {
                String location = null;
                String error = null;
                try { location = AuditLog.export(AuditActivity.this); OperationLog.event("LOG","EXPORT","OK","location="+location); }
                catch (Exception e) { error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); OperationLog.event("LOG","EXPORT","FAIL",OperationLog.stack(e)); }
                final String finalLocation = location;
                final String finalError = error;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (stopped) return;
                        render();
                        if (finalError == null) toast("已导出到\n" + finalLocation);
                        else toast("导出失败: " + finalError);
                    }
                });
            }
        });
    }

    private void confirmClear() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("清空审计日志？")
                .setMessage("此操作无法撤销。建议先导出需要保留的记录。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface value, int which) { clearLog(); }
                }).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            public void onShow(DialogInterface value) {
                AlertDialog shown = (AlertDialog) value;
                applyFonts(shown.getWindow() == null ? null : shown.getWindow().getDecorView());
            }
        });
        dialog.show();
    }

    private void clearLog() {
        subtitle.setText("正在清空…");
        worker.execute(new Runnable() {
            public void run() {
                final boolean cleared = AuditLog.clear(AuditActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (stopped) return;
                        if (cleared) {
                            OperationLog.event("LOG","CLEAR","OK","previous records removed");
                            load();
                            toast("操作日志已清空，已保留本次清空记录");
                        } else {
                            OperationLog.event("LOG","CLEAR","FAIL","AuditLog.clear returned false");
                            render();
                            toast("清空失败");
                        }
                    }
                });
            }
        });
    }

    private void applyFonts(View view) {
        if (view == null) return;
        if (view instanceof TextView) Fonts.apply((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyFonts(group.getChildAt(i));
        }
    }

    private Button action(String label, final String description, final View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setContentDescription(description);
        button.setTextColor(UiStyle.TEXT_2);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinHeight(dp(48));
        button.setBackground(UiStyle.button(this,UiStyle.SURFACE_2));
        button.setOnClickListener(new View.OnClickListener() { public void onClick(View view) {
            OperationLog.Span span=OperationLog.begin("UI","BUTTON_CLICK","activity=AuditActivity action="+description);
            try { listener.onClick(view); OperationLog.result(span,"DISPATCHED","handler returned"); }
            catch (Throwable error) { OperationLog.fail(span,error); if(error instanceof RuntimeException) throw (RuntimeException)error; if(error instanceof Error) throw (Error)error; throw new RuntimeException(error); }
        }});
        Fonts.apply(button);
        return button;
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        params.rightMargin = dp(7);
        return params;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLetterSpacing(0f);
        Fonts.apply(view);
        return view;
    }

    private GradientDrawable panel(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(6));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private ArrayList<String> singleton(String value) {
        ArrayList<String> values = new ArrayList<String>();
        values.add(value);
        return values;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private final class CategoryAdapter extends ArrayAdapter<String> {
        CategoryAdapter(List<String> values) {
            super(AuditActivity.this, android.R.layout.simple_spinner_item, values);
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            return categoryView(position, convertView, false);
        }

        @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return categoryView(position, convertView, true);
        }

        private View categoryView(int position, View convertView, boolean dropdown) {
            TextView view = convertView instanceof TextView ? (TextView) convertView : new TextView(AuditActivity.this);
            view.setText(getItem(position));
            view.setTextColor(UiStyle.TEXT);
            view.setTextSize(13);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(12), 0, dp(12), 0);
            view.setSingleLine(true);
            view.setEllipsize(android.text.TextUtils.TruncateAt.END);
            view.setBackgroundColor(dropdown ? UiStyle.SURFACE_2 : Color.TRANSPARENT);
            view.setMinHeight(dp(48));
            Fonts.apply(view);
            return view;
        }
    }
}
