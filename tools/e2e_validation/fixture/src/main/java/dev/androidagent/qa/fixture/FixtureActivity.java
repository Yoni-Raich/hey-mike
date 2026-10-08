package dev.androidagent.qa.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;

/** Synthetic native UI, with no account, personal data, network, send or payment. */
public class FixtureActivity extends Activity {
    private int generation = 0;
    @Override public void onCreate(Bundle state) { super.onCreate(state); render(); }
    private void record(String key, String value) { getSharedPreferences("qa",0).edit().putString(key,value).apply(); }
    private void render() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(24,36,24,24);
        scroll.addView(layout); setContentView(scroll);
        TextView title = new TextView(this); title.setText("Mike QA fixture generation " + generation); title.setTextSize(24); layout.addView(title);
        EditText input = new EditText(this); input.setSingleLine(false); input.setHint("QA input"); input.setContentDescription("QA input"); layout.addView(input);
        CheckBox flag = new CheckBox(this); flag.setText("QA toggle"); layout.addView(flag);
        flag.setOnCheckedChangeListener((button,checked) -> record("checked",Boolean.toString(checked)));
        Button save = new Button(this); save.setText("Save QA input"); layout.addView(save);
        save.setOnClickListener(v -> { record("text",input.getText().toString()); title.setText("QA input saved"); });
        Button rebuild = new Button(this); rebuild.setText("Rebuild controls"); layout.addView(rebuild);
        rebuild.setOnClickListener(v -> { generation++; record("generation",Integer.toString(generation)); render(); });
        Button delay = new Button(this); delay.setText("Delay 1500 ms"); layout.addView(delay);
        delay.setOnClickListener(v -> { title.setText("QA waiting"); new Handler(Looper.getMainLooper()).postDelayed(() -> { title.setText("QA delay done"); record("delay","done"); },1500); });
        Button denied = new Button(this); denied.setText("Show simulated permission denial"); layout.addView(denied);
        denied.setOnClickListener(v -> title.setText("QA simulated permission_denied; no system permission changed"));
        Button error = new Button(this); error.setText("Show simulated error"); layout.addView(error);
        error.setOnClickListener(v -> title.setText("QA simulated error; process alive"));
        for(int i=0;i<40;i++) { TextView row=new TextView(this); row.setText("QA row "+i); row.setPadding(0,20,0,20); layout.addView(row); }
    }
}
