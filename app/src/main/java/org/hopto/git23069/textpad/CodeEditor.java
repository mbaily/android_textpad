package org.hopto.git23069.textpad;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CodeEditor extends EditText {
    private final Paint gutter = new Paint(), numbers = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint matchPaint = new Paint(Paint.ANTI_ALIAS_FLAG), selectedMatchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path highlightPath = new Path();
    private final KeyListener editingKeys;
    DocumentBuffer buffer;
    Runnable selectionChanged, scrolled;
    boolean tasksOnly;
    int[] lineMap;
    Runnable onBlockedLineAction;
    private boolean lineNumbers = true;
    private int gutterWidth;
    private Pattern searchPattern;

    CodeEditor(Context context) {
        super(context);
        setId(R.id.editor);
        setGravity(Gravity.TOP | Gravity.START);
        setBackgroundColor(0xff101313);
        setTextColor(0xffe3e8df);
        setHighlightColor(0x66a9d98b);
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
        matchPaint.setColor(0x668c7031); matchPaint.setStyle(Paint.Style.FILL);
        selectedMatchPaint.setColor(0xaa8c7031); selectedMatchPaint.setStyle(Paint.Style.FILL);
        updateInputFilter();
    }

    void setTasksOnly(boolean enabled, int[] map, Runnable onBlocked) {
        tasksOnly = enabled;
        lineMap = map;
        onBlockedLineAction = onBlocked;
        updateInputFilter();
        updateGutter();
        invalidate();
    }

    private void updateInputFilter() {
        setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            if (tasksOnly) {
                boolean altersLines = false;
                for (int i = start; i < end; i++) {
                    if (source.charAt(i) == '\n') {
                        altersLines = true;
                        break;
                    }
                }
                if (!altersLines) {
                    for (int i = dstart; i < dend; i++) {
                        if (dest.charAt(i) == '\n') {
                            altersLines = true;
                            break;
                        }
                    }
                }
                if (altersLines) {
                    if (onBlockedLineAction != null) onBlockedLineAction.run();
                    return dest.subSequence(dstart, dend);
                }
            }
            if (dest.length() - (dend - dstart) + (end - start) > TextCodec.MAX_BYTES) return "";
            for (int i = start; i < end; i++) if (source.charAt(i) == '\r') return TextCodec.normalize(source.subSequence(start, end).toString());
            return null;
        }});
    }

    void setSearchPattern(Pattern pattern) {
        searchPattern = pattern;
        invalidate();
    }

    Pattern getSearchPattern() {
        return searchPattern;
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
        int maxLine = buffer == null ? 1 : buffer.lineCount();
        if (lineMap != null && lineMap.length > 0) {
            maxLine = lineMap[lineMap.length - 1] + 1;
        }
        int digits = String.valueOf(maxLine).length();
        int width = lineNumbers ? Math.max(dp(44), (int) numbers.measureText("9".repeat(digits)) + dp(24)) : 0;
        if (width != gutterWidth || getPaddingTop() == 0) {
            gutterWidth = width;
            setPadding(gutterWidth + dp(12), dp(18), dp(20), dp(80));
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (layout != null) {
            int first = layout.getLineForVertical(Math.max(0, getScrollY() - getTotalPaddingTop()));
            int last = layout.getLineForVertical(getScrollY() + getHeight());
            int minOffset = Math.max(0, layout.getLineStart(first) - 256);
            int maxOffset = Math.min(length(), layout.getLineEnd(last) + 256);
            int selStart = Math.max(0, getSelectionStart()), selEnd = Math.max(selStart, getSelectionEnd());
            canvas.save();
            canvas.translate(getTotalPaddingLeft(), getTotalPaddingTop());
            if (searchPattern != null && maxOffset > minOffset) {
                Matcher matcher = searchPattern.matcher(getText());
                matcher.region(minOffset, maxOffset);
                while (matcher.find()) {
                    int mStart = matcher.start(), mEnd = matcher.end();
                    if (mEnd > mStart) {
                        highlightPath.reset();
                        layout.getSelectionPath(mStart, mEnd, highlightPath);
                        canvas.drawPath(highlightPath, mStart == selStart && mEnd == selEnd ? selectedMatchPaint : matchPaint);
                    }
                }
            } else if (!isFocused() && selEnd > selStart && selStart <= maxOffset && selEnd >= minOffset) {
                highlightPath.reset();
                layout.getSelectionPath(selStart, selEnd, highlightPath);
                canvas.drawPath(highlightPath, selectedMatchPaint);
            }
            canvas.restore();
        }
        super.onDraw(canvas);
        if (!lineNumbers || layout == null || buffer == null) return;
        canvas.drawRect(getScrollX(), getScrollY(), getScrollX() + gutterWidth, getScrollY() + getHeight(), gutter);
        int first = layout.getLineForVertical(Math.max(0, getScrollY() - getTotalPaddingTop()));
        int last = layout.getLineForVertical(getScrollY() + getHeight());
        for (int i = first; i <= last; i++) {
            int offset = layout.getLineStart(i), logical = buffer.lineAt(offset);
            if (offset == buffer.lineStart(logical)) {
                int displayLine = (lineMap != null && logical < lineMap.length) ? lineMap[logical] + 1 : logical + 1;
                canvas.drawText(String.valueOf(displayLine), getScrollX() + gutterWidth - dp(10),
                        layout.getLineBaseline(i) + getTotalPaddingTop(), numbers);
            }
        }
    }

    @Override protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        if (selectionChanged != null) selectionChanged.run();
        if (searchPattern != null) invalidate();
    }
    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if (scrolled != null) scrolled.run();
        if (lineNumbers) invalidate();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
