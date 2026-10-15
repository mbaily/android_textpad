package org.hopto.git23069.textpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.method.KeyListener;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import org.hopto.git23069.textpad.core.DocumentBuffer;
import org.hopto.git23069.textpad.core.TextCodec;

final class CodeEditor extends EditText {
    private final Paint gutter = new Paint(), numbers = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final KeyListener editingKeys;
    DocumentBuffer buffer;
    Runnable selectionChanged, scrolled;
    private boolean lineNumbers = true;
    private int gutterWidth;

    CodeEditor(Context context) {
        super(context);
        setId(R.id.editor);
        setGravity(Gravity.TOP | Gravity.START);
        setBackgroundColor(0xff101313);
        setTextColor(0xffe3e8df);
        setHintTextColor(0xff65736b);
        setHint("Start writing…");
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        setTypeface(Typeface.MONOSPACE);
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editingKeys = getKeyListener();
        setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        setSaveEnabled(false);
        setFreezesText(false);
        setIncludeFontPadding(false);
        setLineSpacing(dp(3), 1f);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        setVerticalScrollBarEnabled(true);
        setHorizontalScrollBarEnabled(true);
        gutter.setColor(0xff141918); numbers.setColor(0xff758477);
        numbers.setTextAlign(Paint.Align.RIGHT); numbers.setTypeface(Typeface.MONOSPACE);
        setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            if (dest.length() - (dend - dstart) + (end - start) > TextCodec.MAX_BYTES) return "";
            for (int i = start; i < end; i++) if (source.charAt(i) == '\r') return TextCodec.normalize(source.subSequence(start, end).toString());
            return null;
        }});
    }

    void configure(EditorSettings settings, boolean writable) {
        lineNumbers = settings.lineNumbers();
        setTypeface(Typeface.create(settings.font(), Typeface.NORMAL));
        setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.fontSize());
        numbers.setTextSize(getTextSize() * .78f);
        setHorizontallyScrolling(!settings.wrap());
        setKeyListener(writable ? editingKeys : null);
        setShowSoftInputOnFocus(writable);
        if (!writable) setTextIsSelectable(true);
        updateGutter();
        requestLayout(); invalidate();
    }

    void updateGutter() {
        int digits = String.valueOf(buffer == null ? 1 : buffer.lineCount()).length();
        int width = lineNumbers ? Math.max(dp(44), (int) numbers.measureText("9".repeat(digits)) + dp(24)) : 0;
        if (width != gutterWidth || getPaddingTop() == 0) {
            gutterWidth = width;
            setPadding(gutterWidth + dp(12), dp(18), dp(20), dp(80));
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Layout layout = getLayout();
        if (!lineNumbers || layout == null || buffer == null) return;
        canvas.drawRect(getScrollX(), getScrollY(), getScrollX() + gutterWidth, getScrollY() + getHeight(), gutter);
        int first = layout.getLineForVertical(Math.max(0, getScrollY() - getTotalPaddingTop()));
        int last = layout.getLineForVertical(getScrollY() + getHeight());
        for (int i = first; i <= last; i++) {
            int offset = layout.getLineStart(i), logical = buffer.lineAt(offset);
            if (offset == buffer.lineStart(logical)) {
                canvas.drawText(String.valueOf(logical + 1), getScrollX() + gutterWidth - dp(10),
                        layout.getLineBaseline(i) + getTotalPaddingTop(), numbers);
            }
        }
    }

    @Override protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        if (selectionChanged != null) selectionChanged.run();
    }
    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if (scrolled != null) scrolled.run();
        if (lineNumbers) invalidate();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
