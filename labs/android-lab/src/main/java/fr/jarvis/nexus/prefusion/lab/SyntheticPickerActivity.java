package fr.jarvis.nexus.prefusion.lab;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;

/** OFF by default. Session-only URI, no saved state, persisted grant or production caller. */
public final class SyntheticPickerActivity extends Activity {
    public volatile Uri selected;
    public volatile int resultFlags;
    public volatile String status="OFF";
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        Button button=new Button(this);button.setText("LOT3 OFF");setContentView(button);
        if(!getIntent().getBooleanExtra("prefusionSyntheticLab",false)) return;
        status="LAB_ONLY";button.setText("CHOOSE SYNTHETIC DOCUMENT");
        button.setOnClickListener(v->{
            Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("text/plain");
            pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(pick,4);
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=4)return;
        if(result!=RESULT_OK||data==null||data.getData()==null){status="CANCELLED";return;}
        Uri uri=data.getData();
        // Restrict this laboratory consumer to its separate synthetic provider.
        if(!"fr.jarvis.nexus.prefusion.provider.documents".equals(uri.getAuthority())){status="REFUSED_NON_FIXTURE";return;}
        if((data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0){status="DENIED";return;}
        selected=uri;resultFlags=data.getFlags();status="SELECTED_READ_ONLY";
    }
}
