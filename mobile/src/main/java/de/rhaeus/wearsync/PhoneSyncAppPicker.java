package de.rhaeus.wearsync;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class PhoneSyncAppPicker {

    private static final String PREF = "wearsync_prefs";

    public interface Callback {
        void onSelected(String pkg,String name);
    }


    private static class AppInfo {

        String name;
        String pkg;

        AppInfo(String name,String pkg){
            this.name=name;
            this.pkg=pkg;
        }
    }



    public static void show(
            Context context,
            Callback callback
    ){

        List<AppInfo> list =
                getClockApps(context);


        list.add(
                new AppInfo(
                        "🔍 浏览更多应用",
                        "MORE"
                )
        );


        String[] names =
                new String[list.size()];


        for(int i=0;i<list.size();i++){

            names[i]=list.get(i).name;

        }



        new AlertDialog.Builder(context)
                .setTitle("选择闹钟应用")
                .setItems(
                        names,
                        (dialog,which)->{


                            AppInfo item =
                                    list.get(which);


                            if(item.pkg.equals("MORE")){

                                showAllApps(
                                        context,
                                        callback
                                );

                                return;
                            }



                            save(
                                    context,
                                    item
                            );


                            if(callback!=null){

                                callback.onSelected(
                                        item.pkg,
                                        item.name
                                );

                            }

                        }
                )
                .show();

    }




    private static List<AppInfo> getClockApps(
            Context context
    ){

        PackageManager pm =
                context.getPackageManager();


        List<AppInfo> result =
                new ArrayList<>();


        String[] defaultApps = {

                "com.google.android.deskclock",
                "com.coloros.alarmclock",
                "com.android.deskclock"

        };


        for(String pkg:defaultApps){

            try{

                ApplicationInfo info =
                        pm.getApplicationInfo(
                                pkg,
                                0
                        );


                result.add(
                        new AppInfo(
                                info.loadLabel(pm).toString(),
                                pkg
                        )
                );


            }catch(Exception ignored){}

        }




        List<ApplicationInfo> apps =
                pm.getInstalledApplications(
                        PackageManager.GET_META_DATA
                );


        for(ApplicationInfo info:apps){

            String pkg =
                    info.packageName.toLowerCase();



            if(
                    (pkg.contains("clock")
                    ||pkg.contains("alarm"))
                    &&
                    !hasPackage(
                            result,
                            info.packageName
                    )
            ){

                result.add(
                        new AppInfo(
                                info.loadLabel(pm).toString(),
                                info.packageName
                        )
                );

            }

        }



        Collections.sort(
                result,
                new Comparator<AppInfo>() {

                    @Override
                    public int compare(
                            AppInfo a,
                            AppInfo b
                    ){

                        return a.name.compareToIgnoreCase(
                                b.name
                        );

                    }

                }
        );


        return result;

    }




    private static void showAllApps(
            Context context,
            Callback callback
    ){

        PackageManager pm =
                context.getPackageManager();


        List<AppInfo> apps =
                new ArrayList<>();



        for(ApplicationInfo info:
                pm.getInstalledApplications(
                        PackageManager.GET_META_DATA
                )){


            apps.add(
                    new AppInfo(
                            info.loadLabel(pm).toString(),
                            info.packageName
                    )
            );

        }



        Collections.sort(
                apps,
                (a,b)->a.name.compareToIgnoreCase(b.name)
        );



        String[] names =
                new String[apps.size()];


        for(int i=0;i<apps.size();i++){

            names[i]=apps.get(i).name;

        }



        new AlertDialog.Builder(context)
                .setTitle("全部应用")
                .setItems(
                        names,
                        (dialog,which)->{


                            AppInfo item =
                                    apps.get(which);



                            save(
                                    context,
                                    item
                            );


                            if(callback!=null){

                                callback.onSelected(
                                        item.pkg,
                                        item.name
                                );

                            }

                        }
                )
                .show();

    }





    private static void save(
            Context context,
            AppInfo info
    ){

        context.getSharedPreferences(
                PREF,
                Context.MODE_PRIVATE
        )
        .edit()
        .putString(
                "alarm_package",
                info.pkg
        )
        .putString(
                "alarm_name",
                info.name
        )
        .apply();

    }




    private static boolean hasPackage(
            List<AppInfo> list,
            String pkg
    ){

        for(AppInfo info:list){

            if(info.pkg.equals(pkg)){
                return true;
            }

        }

        return false;

    }

}