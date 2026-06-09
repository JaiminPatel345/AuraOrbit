package dev.jaimin.auraorbit;

import android.os.Bundle;
import android.app.Activity;
import android.app.AlertDialog;

public class DeletedWidgetActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Group Deleted")
            .setMessage("This group has been deleted. Android does not allow apps to delete widgets automatically.\n\nPlease long-press this widget and drag it to the trash to remove it from your home screen.")
            .setPositiveButton("OK", (dialog, which) -> {
                finish();
            })
            .setOnDismissListener(dialog -> finish())
            .setCancelable(false)
            .show();
    }
}
