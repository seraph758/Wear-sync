package de.rhaeus.wearsync;


import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;


import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentTransaction;



public class PhoneSyncMainActivity extends AppCompatActivity {


    private static final String TAG =
            "WearSync_PhoneMain";


    private static PhoneSyncMainActivity instance;



    public static PhoneSyncMainActivity getInstance(){

        return instance;

    }





    @Override
    protected void onCreate(Bundle savedInstanceState){


        com.google.android.material.color.DynamicColors
                .applyToActivityIfAvailable(this);


        super.onCreate(savedInstanceState);



        instance=this;



        Log.d(TAG,"PhoneSyncMainActivity启动");



        setContentView(R.layout.activity_main);



        if(savedInstanceState==null){


            FragmentTransaction ft =
                    getSupportFragmentManager()
                            .beginTransaction();


            ft.replace(
                    R.id.settings,
                    new PhoneSyncMainFragment()
            );


            ft.commit();

        }



        handleIncomingCommand(getIntent());

    }






    @Override
    protected void onNewIntent(Intent intent){


        super.onNewIntent(intent);


        setIntent(intent);


        handleIncomingCommand(intent);

    }







    private void handleIncomingCommand(Intent intent){



        if(intent==null)
            return;



        String cmd =
                intent.getStringExtra(
                        "INTERNAL_CMD"
                );



        if(
                "LAUNCH_CAMERA_SERVICE_FROM_FOREGROUND"
                        .equals(cmd)
        ){



            Log.d(
                    TAG,
                    "收到RemoteActivity拍照请求"
            );



            Intent serviceIntent =
                    new Intent(
                            this,
                            PhoneSyncCameraService.class
                    );



            serviceIntent.setAction(
                    PhoneSyncCameraService.ACTION_START_CAMERA
            );



            if(Build.VERSION.SDK_INT>=26){


                startForegroundService(
                        serviceIntent
                );


            }else{


                startService(
                        serviceIntent
                );

            }


        }



    }






    @Override
    protected void onDestroy(){


        if(instance==this)
            instance=null;


        super.onDestroy();

    }


}