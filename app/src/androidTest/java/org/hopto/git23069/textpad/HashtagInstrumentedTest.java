package org.hopto.git23069.textpad;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.hopto.git23069.textpad.core.Hashtags;
import org.junit.Before;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class HashtagInstrumentedTest {
    private TextpadApplication app;
    private AlertDialog dialog;

    private void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    @Before public void reset() throws Exception {
        app = (TextpadApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        app.io.submit(() -> {}).get(30, TimeUnit.SECONDS);
        main(() -> {
            app.tabs.clear(); app.selectedTab = -1; app.events.clear();
            app.exiting = false; app.readOnlyMode = false;
            app.settings.prefs.edit().clear().commit();
            app.sync.prefs.edit().clear().commit(); app.sync.testApi = null;
            app.getSharedPreferences("files", 0).edit().remove("recent").commit();
        });
    }

    private ActivityScenario<MainActivity> launch() {
        return ActivityScenario.launch(new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    private void waitFor(BooleanSupplier ready) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicBoolean done = new AtomicBoolean();
            main(() -> done.set(ready.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(30);
        }
        fail("Timed out waiting for hashtag UI");
    }

    private RecyclerView list(int id) { return dialog.findViewById(id); }

    private TextView text(View view, String value) {
        if (view instanceof TextView label && value.contentEquals(label.getText())) return label;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = text(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void click(String value) { text(dialog.getWindow().getDecorView(), value).performClick(); }

    @Test public void largeErrandsListRecyclesChipsAndFullLinesAndOpensScrolledResults() throws Exception {
        List<String> tags = new ArrayList<>();
        StringBuilder contents = new StringBuilder();
        for (int i = 0; i < 950; i++) {
            tags.add("tag_" + i);
            contents.append("[ ] Task ").append(i).append(" #tag_").append(i).append('\n');
        }
        String longLine = "[ ] " + "Full matching line ".repeat(40) + "#tag_949";
        contents.append(longLine).append("\n[X] Done #tag_0\n  - [c] Cancelled #tag_949");
        try (var scenario = launch()) {
            scenario.onActivity(activity -> {
                app.newDocument();
                ((CodeEditor) activity.findViewById(R.id.editor)).getText().append(contents);
                app.settings.setErrandsHashtags(tags, "", false);
                long start = SystemClock.uptimeMillis();
                dialog = new HashtagUi(activity, app).show(true);
                android.util.Log.i("HashtagTest", "950-tag dialog opened in " + (SystemClock.uptimeMillis() - start) + "ms");
                assertTrue(((CheckBox) dialog.findViewById(R.id.hashtag_hide_completed)).isChecked());
            });
            // One header and 951 open lines; both closed lines are hidden by default.
            waitFor(() -> list(R.id.hashtag_results).getAdapter().getItemCount() == 952
                    && list(R.id.hashtag_results).getChildCount() > 0);
            main(() -> {
                RecyclerView chips = list(R.id.hashtag_chips), results = list(R.id.hashtag_results);
                assertEquals(950, chips.getAdapter().getItemCount());
                assertTrue(chips.getChildCount() > 0 && chips.getChildCount() < 50);
                assertTrue(results.getChildCount() < 50);
                chips.scrollToPosition(949);
                results.scrollToPosition(951);
            });
            waitFor(() -> text(list(R.id.hashtag_chips), "#tag_949") != null
                    && text(list(R.id.hashtag_results), "Ln 951  " + longLine) != null);
            main(() -> {
                TextView fullLine = text(list(R.id.hashtag_results), "Ln 951  " + longLine);
                assertNull(fullLine.getEllipsize());
                assertTrue(fullLine.getLineCount() > 1);
                ((CheckBox) dialog.findViewById(R.id.hashtag_hide_completed)).setChecked(false);
            });
            waitFor(() -> list(R.id.hashtag_results).getAdapter().getItemCount() == 954);
            main(() -> click("File matching"));
            waitFor(() -> list(R.id.hashtag_results).getAdapter().getItemCount() == 1
                    && text(list(R.id.hashtag_results), "Searching files…") == null);
            main(() -> click("Line matching"));
            main(() -> list(R.id.hashtag_results).scrollToPosition(953));
            waitFor(() -> text(list(R.id.hashtag_results), "Ln 953    - [c] Cancelled #tag_949") != null);
            main(() -> text(list(R.id.hashtag_results), "Ln 953    - [c] Cancelled #tag_949").performClick());
            waitFor(() -> !dialog.isShowing());
            scenario.onActivity(activity -> assertEquals(app.current().buffer.lineStart(952),
                    ((CodeEditor) activity.findViewById(R.id.editor)).getSelectionStart()));
        }
    }

    @Test public void editingAndSearchingLargeTagListsRetainsTagsAndFilterChoice() throws Exception {
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < 950; i++) tags.add("tag_" + i);
        try (var scenario = launch()) {
            scenario.onActivity(activity -> {
                app.newDocument();
                ((CodeEditor) activity.findViewById(R.id.editor)).getText().append(Hashtags.formatTagList(tags));
                app.settings.setErrandsHashtags(tags, "", false);
                dialog = new HashtagUi(activity, app).show(true);
            });
            waitFor(() -> list(R.id.hashtag_results).getAdapter().getItemCount() == 2);
            // Wait for the initial settings sync so it cannot refresh controls during the edit.
            app.io.submit(() -> {}).get(30, TimeUnit.SECONDS);
            main(() -> click("Edit"));
            main(() -> list(R.id.hashtag_chips).scrollToPosition(949));
            waitFor(() -> text(list(R.id.hashtag_chips), "#tag_949") != null);
            main(() -> {
                assertTrue(list(R.id.hashtag_chips).getChildCount() < 50);
                text(list(R.id.hashtag_chips), "#tag_949").performClick();
                click("Save Errands");
                assertEquals(tags.subList(0, 949), app.settings.errandsHashtags());
                assertEquals(949, list(R.id.hashtag_chips).getAdapter().getItemCount());
                ((CheckBox) dialog.findViewById(R.id.hashtag_hide_completed)).setChecked(false);
                click("Search");
            });
            waitFor(() -> list(R.id.hashtag_chips).getAdapter().getItemCount() == 950
                    && list(R.id.hashtag_chips).getChildCount() > 0);
            main(() -> list(R.id.hashtag_chips).scrollToPosition(949));
            waitFor(() -> text(list(R.id.hashtag_chips), "#tag_949") != null);
            main(() -> text(list(R.id.hashtag_chips), "#tag_949").performClick());
            waitFor(() -> list(R.id.hashtag_results).getAdapter().getItemCount() == 2);
            main(() -> {
                assertNotNull(text(dialog.getWindow().getDecorView(), "#tag_949"));
                click("Errands (949)");
                assertFalse(((CheckBox) dialog.findViewById(R.id.hashtag_hide_completed)).isChecked());
                dialog.dismiss();
            });
        }
    }
}
