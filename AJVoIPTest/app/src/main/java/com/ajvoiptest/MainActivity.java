package com.ajvoiptest;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.util.Calendar;
import java.util.TimeZone;
import com.mizuvoip.jvoip.SipStack; //import the Mizu SIP SDK!

//IMPORTANT: You must copy the AJVoIP.aar into the \AJVoIPTest\AJVoIP folder!

public class MainActivity extends Activity
{
    public static String LOGTAG = "AJVoIP";
    EditText mParams = null;
    EditText mDestNumber = null;
    Button mBtnStart = null;
    Button mBtnCall = null;
    Button mBtnHangup = null;
    TextView mStatus = null;
    TextView mNotifications = null;
    SipStack mysipclient = null;
    Context ctx = null;
    public static MainActivity instance = null;


    boolean terminateNotifThread = false;
    GetNotificationsThread notifThread = null;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ctx = this;
        instance = this;

        mParams = (EditText) findViewById(R.id.parameters_view);
        mDestNumber = (EditText) findViewById(R.id.dest_number);
        mBtnStart = (Button) findViewById(R.id.btn_start);
        mBtnCall = (Button) findViewById(R.id.btn_call);
        mBtnHangup = (Button) findViewById(R.id.btn_hangup);
        mStatus = (TextView) findViewById(R.id.status);
        mNotifications = (TextView) findViewById(R.id.notifications);
        mNotifications.setMovementMethod(new ScrollingMovementMethod());

        DisplayLogs("oncreate");

        //SIP stack parameters separated by CRLF. Will be passed to AJVoIP with the SetParameters API call.
        //Add other settings after your needs. See the documentation for the full list of available parameters.
        String demoparams = "serveraddress=voip.mizu-voip.com\r\nusername=ajvoiptest\r\npassword=ajvoip1234\r\nloglevel=5";
        mParams.setText(demoparams);
        mDestNumber.setText("testivr3"); //default call-to number for our test (testivr3 is a music IVR access number on our test server at voip.mizu-voip.com)

        DisplayStatus("Ready.");

        mBtnStart.setOnClickListener(new View.OnClickListener()
        {
            public void onClick(View v)
            {
                DisplayLogs("Start on click");

                try{
                    // start SipStack if it's not already running
                    if (mysipclient == null)
                    {
                        DisplayLogs("Start SipStack");

                        //initialize the SIP engine
                        mysipclient = new SipStack();
                        mysipclient.Init(ctx);
                        SetParameters();

                        //start my event listener thread
                        notifThread = new GetNotificationsThread();
                        notifThread.start();

                        //start the SIP engine
                        mysipclient.Start();
                        //mysipclient.Register();

                    }else
                    {
                        DisplayLogs("SipStack already started");
                    }
                }catch (Exception e) { DisplayLogs("ERROR, StartSipStack"); }
            }
        });


        mBtnCall.setOnClickListener(new View.OnClickListener()
        {
            public void onClick(View v)
            {
                DisplayLogs("Call on click");

                String number = mDestNumber.getText().toString().trim();
                if (number == null || number.length() < 1)
                {
                    DisplayStatus("ERROR, Invalid destination number");
                    return;
                }

                if (mysipclient == null)
                    DisplayStatus("ERROR, cannot initiate call because SipStack is not started");
                else
                    mysipclient.Call(-1, number);
            }
        });

        mBtnHangup.setOnClickListener(new View.OnClickListener()
        {
            public void onClick(View v)
            {
                DisplayLogs("Hangup on click");

                if (mysipclient == null)
                    DisplayStatus("ERROR, cannot hangup because SipStack is not started");
                else
                    mysipclient.Hangup();
            }
        });
    }

    public class GetNotificationsThread extends Thread
    {
        String sipnotifications = "";
        public void run()
        {
            try{
                try { Thread.currentThread().setPriority(4); } catch (Throwable e) { }  //we are lowering this thread priority a bit to give more chance for our main GUI thread

                while (!terminateNotifThread)
                {

                    try{
                        sipnotifications = "";
                        if (mysipclient != null)
                        {
                            //get notifications from the SIP stack
                            sipnotifications = mysipclient.GetNotificationsSync();

                            if (sipnotifications != null && sipnotifications.length() > 0)
                            {
                                // send notifications to Main thread using a Handler
                                Message messageToMainThread = new Message();
                                Bundle messageData = new Bundle();
                                messageToMainThread.what = 0;
                                messageData.putString("notifmessages", sipnotifications);
                                messageToMainThread.setData(messageData);

                                NotifThreadHandler.sendMessage(messageToMainThread);
                            }
                        }

                        if (sipnotifications.length() < 1 && !terminateNotifThread)
                        {
                            //some error occured. sleep a bit just to be sure to avoid busy loop
                            GetNotificationsThread.sleep(1);
                        }

                        continue;
                    }catch(Throwable e){  Log.e(LOGTAG, "ERROR, WorkerThread on run()intern", e); }
                    if(!terminateNotifThread)
                    {
                        GetNotificationsThread.sleep(10);
                    }
                }
            }catch(Throwable e){ Log.e(LOGTAG, "WorkerThread on run()"); }
        }
    }

    //get the notifications from the GetNotificationsThread thread
    public static Handler NotifThreadHandler = new Handler()
    {
        public void handleMessage(android.os.Message msg)
        {
            try {
                if (msg == null || msg.getData() == null) return;;
                Bundle resBundle = msg.getData();

                String receivedNotif =  msg.getData().getString("notifmessages");

                if (receivedNotif != null && receivedNotif.length() > 0)
                    instance.ReceiveNotifications(receivedNotif);

            } catch (Throwable e) { Log.e(LOGTAG, "NotifThreadHandler handle Message"); }
        }
    };

    //process notificatins phrase 1: split by line (we can receive multiple notifications separated by \r\n)
    public void ReceiveNotifications(String notifs)
    {
        if (notifs == null || notifs.length() < 1) return;
        String[] notarray = notifs.split("\r\n");

        if (notarray == null || notarray.length < 1) return;

        for (int i = 0; i < notarray.length; i++)
        {
            if (notarray[i] != null && notarray[i].length() > 0)
            {
                ProcessNotifications(notarray[i]);
            }
        }
    }

    //process notificatins phrase 2: processing notification strings
    public void ProcessNotifications(String line)
    {
        DisplayStatus(line); //we just display them in this simple test application
        //see the Notifications section in the documentation about the possible messages (parse the line string and process them after your needs)
    }

    public void SetParameters()
    {
        String params = mParams.getText().toString();
        if (params == null || mysipclient == null) return;
        params = params.trim();

        DisplayLogs("SetParameters: " + params);

        mysipclient.SetParameters(params);
    }

    public void DisplayStatus(String stat)
    {
        if (stat == null) return;;
        if (mStatus != null) mStatus.setText(stat);
        DisplayLogs("Status: " + stat);
    }

    public void DisplayLogs(String logmsg)
    {
        if (logmsg == null || logmsg.length() < 1) return;

        if ( logmsg.length() > 2500) logmsg = logmsg.substring(0,300)+"...";
        logmsg = "["+ new java.text.SimpleDateFormat("HH:mm:ss:SSS").format(Calendar.getInstance(TimeZone.getTimeZone("GMT")).getTime()) +  "] " + logmsg + "\r\n";

        Log.v(LOGTAG, logmsg);
        if (mNotifications != null) mNotifications.append(logmsg);
    }

    @Override
    public void onResume()
    {
        super.onResume();
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        DisplayLogs("ondestroy");
		terminateNotifThread = true;
        if (mysipclient != null)
        {
            DisplayLogs("Stop SipStack");
            mysipclient.Stop(true);
        }

        mysipclient = null;        
        notifThread = null;
    }
}
