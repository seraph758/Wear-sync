package de.rhaeus.wearsync;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class PhoneSyncAppPicker {

    private static final String PREF = "wearsync_prefs";


    public interface Callback {
        void onSelected(String pkg, String name);
    }



    private static class AppItem {

        String name;
        String pkg;


        AppItem(String name,String pkg){

            this.name=name;
            this.pkg=pkg;

        }
    }




    public static void show(Context context, Callback callback){


        if(!(context instanceof android.app.Activity)){

            PhoneLog.e(
                    "PhoneSyncAppPicker",
                    "Context不是Activity，无法显示窗口"
            );

            return;
        }



        List<AppItem> apps = getClockApps(context);



        apps.add(
                new AppItem(
                        "🔍 浏览更多应用",
                        "MORE"
                )
        );



        String[] names =
                new String[apps.size()];



        for(int i=0;i<apps.size();i++){

            names[i]=apps.get(i).name;

        }



        new AlertDialog.Builder(context)
                .setTitle("选择闹钟应用")
                .setItems(
                        names,
                        (dialog,which)->{


                            AppItem item =
                                    apps.get(which);



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






    private static List<AppItem> getClockApps(Context context){


        PackageManager pm =
                context.getPackageManager();


        List<AppItem> result =
                new ArrayList<>();



        String[] defaults = {


                "com.google.android.deskclock",

                "com.coloros.alarmclock",

                "com.android.deskclock"

        };




        for(String pkg:defaults){


            try{


                ApplicationInfo info =
                        pm.getApplicationInfo(
                                pkg,
                                0
                        );



                result.add(
                        new AppItem(
                                info.loadLabel(pm).toString(),
                                pkg
                        )
                );


            }catch(Exception ignored){}


        }







        for(ApplicationInfo info :
                pm.getInstalledApplications(
                        PackageManager.GET_META_DATA
                )){


            String pkg =
                    info.packageName.toLowerCase();



            if(
                    (pkg.contains("clock")
                    ||
                    pkg.contains("alarm"))
                    &&
                    !contains(
                            result,
                            info.packageName
                    )
            ){



                result.add(
                        new AppItem(
                                info.loadLabel(pm).toString(),
                                info.packageName
                        )
                );


            }

        }





        Collections.sort(
                result,
                new Comparator<AppItem>() {


                    @Override
                    public int compare(
                            AppItem a,
                            AppItem b
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



        List<AppItem> list =
                new ArrayList<>();




        for(ApplicationInfo info :
                pm.getInstalledApplications(
                        PackageManager.GET_META_DATA
                )){


            list.add(
                    new AppItem(
                            info.loadLabel(pm).toString(),
                            info.packageName
                    )
            );


        }






        Collections.sort(
                list,
                (a,b)->

                        a.name.compareToIgnoreCase(
                                b.name
                        )

        );






        String[] names =
                new String[list.size()];



        for(int i=0;i<list.size();i++){


            names[i]=list.get(i).name;


        }






        new AlertDialog.Builder(context)

                .setTitle("全部应用")

                .setItems(
                        names,
                        (dialog,which)->{


                            AppItem item =
                                    list.get(which);



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
            AppItem item
    ){


        SharedPreferences prefs =
                context.getSharedPreferences(
                        PREF,
                        Context.MODE_PRIVATE
                );



        prefs.edit()

                .putString(
                        "selected_alarm_package",
                        item.pkg
                )

                .putString(
                        "selected_alarm_name",
                        item.name
                )

                .apply();



        PhoneLog.d(
                "PhoneSyncAppPicker",
                "已保存闹钟应用: "
                        + item.name
                        +" / "
                        +item.pkg
        );


    }






    private static boolean contains(
            List<AppItem> list,
            String pkg
    ){


        for(AppItem item:list){


            if(item.pkg.equals(pkg)){

                return true;

            }

        }


        return false;


    }


}