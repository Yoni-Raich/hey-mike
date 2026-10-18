package dev.androidagent.qa.fixture;

import android.content.*;
import org.json.JSONObject;

/** Independent read-only oracle, permission-gated to ADB's DUMP grant. */
public class EvidenceReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        JSONObject result=new JSONObject(context.getSharedPreferences("qa",0).getAll());
        setResultData(result.toString()); setResultCode(1);
    }
}
