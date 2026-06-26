package de.rhaeus.wearsync;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class PhoneSyncAppPicker {

    private static final String PREF = "wearsync_prefs";
    private static final String KEY_PACKAGE = "alarm_package";
    private static final String KEY_NAME = "alarm_name";

    public static void show(Context context) {

        PackageManager pm = context.getPackageManager();
        List<AppInfo> apps = new ArrayList<>();

        // 常见时钟优先
        String[] defaultClock = {
                "com.google.android.deskclock",
                "com.coloros.alarmclock",
                "com.android.deskclock"
        };

        for (String pkg : defaultClock) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                apps.add(new AppInfo(
                        ai.loadLabel(pm).toString(),
                        pkg
                ));
            } catch (Exception ignored) {}
        }

        // 其它包含 clock / alarm 的应用
        List<ApplicationInfo> installed =
                pm.getInstalledApplications(PackageManager.GET_META_DATA);

        for (ApplicationInfo ai : installed) {

            String pkg = ai.packageName.toLowerCase();

            if ((pkg.contains("clock") || pkg.contains("alarm"))
                    && !containsPackage(apps, ai.packageName)) {

                apps.add(new AppInfo(
                        ai.loadLabel(pm).toString(),
                        ai.packageName
                ));
            }
        }

        Collections.sort(apps, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        apps.add(new AppInfo(
                "浏览更多应用",
                "MORE"
        ));

        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            names[i] = apps.get(i).name;
        }


        new AlertDialog.Builder(context)
                .setTitle("选择闹钟应用")
                .setItems(names, (dialog, which) -> {

                    AppInfo item = apps.get(which);

                    if ("MORE".equals(item.pkg)) {

                        try {
                            Intent intent = new Intent(
                                    Intent.ACTION_MAIN
                            );
                            intent.addCategory(
                                    Intent.CATEGORY_LAUNCHER
                            );
                            context.startActivity(intent);
                        } catch (Exception e) {
                            Toast.makeText(
                                    context,
                                    "无法打开应用列表",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }

                        return;
                    }


                    context.getSharedPreferences(
                            PREF,
                            Context.MODE_PRIVATE
                    )
                    .edit()
                    .putString(KEY_PACKAGE,item.pkg)
                    .putString(KEY_NAME,item.name)
                    .apply();


                    Toast.makeText(
                            context,
                            "已选择: " + item.name,
                            Toast.LENGTH_SHORT
                    ).show();


                })
                .show();
    }


    private static boolean containsPackage(
            List<AppInfo> list,
            String pkg
    ){

        for(AppInfo a:list){

            if(a.pkg.equals(pkg)){
                return true;
            }

        }

        return false;
    }



    private static class AppInfo {

        String name;
        String pkg;

        AppInfo(
                String name,
                String pkg
        ){
            this.name=name;
            this.pkg=pkg;
        }
    }
}