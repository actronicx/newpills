package it.davide.pillole;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Schermata della sveglia, visibile anche a telefono bloccato. Il suono è quello della notifica. */
public class AlarmActivity extends Activity {

    private String id;
    private String name;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        read(getIntent());
        build();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        read(intent);
        build();
    }

    private void read(Intent i) {
        id = i.getStringExtra("id");
        name = i.getStringExtra("name");
        if (name == null) name = "Pillola";
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private Button button(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(20);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(16));
        b.setBackground(d);
        b.setPadding(dp(16), dp(18), dp(16), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        b.setLayoutParams(lp);
        return b;
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0F6B54"));
        root.setPadding(dp(28), dp(48), dp(28), dp(48));

        TextView time = new TextView(this);
        time.setText(new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date()));
        time.setTextColor(Color.WHITE);
        time.setTextSize(72);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setGravity(Gravity.CENTER);

        TextView label = new TextView(this);
        label.setText("È ora di prendere");
        label.setTextColor(Color.parseColor("#C4E6D9"));
        label.setTextSize(20);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(12), 0, dp(4));

        TextView pill = new TextView(this);
        pill.setText(name);
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(32);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(0, 0, 0, dp(32));

        Button open = button("Ferma e apri l'app", Color.WHITE, Color.parseColor("#0F6B54"));
        open.setOnClickListener(v -> {
            if (id != null) AlarmScheduler.cancelNotification(this, id);
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
        });

        Button later = button("Posticipa " + AlarmScheduler.SNOOZE_MINUTES + " minuti", Color.parseColor("#1F4A3D"), Color.WHITE);
        later.setOnClickListener(v -> {
            if (id != null) {
                AlarmScheduler.cancelNotification(this, id);
                AlarmScheduler.snooze(this, id, name);
            }
            finish();
        });

        root.addView(time);
        root.addView(label);
        root.addView(pill);
        root.addView(open);
        root.addView(later);
        setContentView(root);
    }

    @Override
    public void onBackPressed() {
        // il tasto indietro non chiude la sveglia: serve una scelta esplicita
    }
}
