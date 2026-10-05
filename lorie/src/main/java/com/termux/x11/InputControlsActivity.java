package com.termux.x11;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 虚拟控件配置界面宿主。
 *
 * <p>承载 {@link InputControlsFragment}（配置方案选择、导入导出、外接手柄绑定）。
 * winlator 里该 Fragment 由主界面的抽屉容器托管，termux-x11 用独立 Activity 承载。
 */
public class InputControlsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout container = new FrameLayout(this);
        container.setId(android.R.id.content + 1);
        setContentView(container);

        Prefs prefs = ((LorieApp) getApplication()).getPrefs(this);
        int selectedProfileId = prefs.activeControlsProfile.get();
        getSupportFragmentManager().beginTransaction()
                .replace(container.getId(), new InputControlsFragment(selectedProfileId))
                .commit();
    }
}
