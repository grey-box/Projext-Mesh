package com.example.myapplication;

import androidx.appcompat.app.AppCompatActivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

//JSGARVEY 03/03/23 - US#206 Citations Sarthi Technology
// https://www.youtube.com/playlist?list=PLFh8wpMiEi88SIJ-PnJjDxktry4lgBtN3
public class MainActivity extends AppCompatActivity {

    //JSGARVEY  Add Button Objects
    Button btnOnOff, btnDiscover, btnSend;
    //JSGARVEY  Add list view for available peer list
    ListView listView;
    //JSGARVEY  message text view for read message and connection status
    TextView read_msg_box, connectionStatus;
    //JSGARVEY message text field to enter message to send to peers
    EditText writeMSg;

    //JSGARVEY  Wifi Managers and Channel
    WifiManager wifiManager;
    WifiP2pManager mManager;
    WifiP2pManager.Channel mChannel;

    //JSGARVEY Broadcast Receiver and intent filter T#204
    BroadcastReceiver mReceiver;
    IntentFilter mIntentFilter;

    List<WifiP2pDevice> peers = new ArrayList<WifiP2pDevice>();
    String[] deviceNameArray;
    WifiP2pDevice[] deviceArray;

    //JSGARVEY imported override method onCreate
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        //JSGARVEY
        initialWork();
        exListener();

    }

    //JSGARVEY implemented method for app object actoin listeners
    private void exListener(){
        /*
        JSGARVEY Wifi Enabled: button to turn wifi on and off when clicked if wifi is enabled
        turn wifi off and switch button label. If wifi is disabled already, turn wifi on.
        */
        btnOnOff.setOnClickListener(new View.OnClickListener() {
             /*
             !!!!!!Android no longer allows app automation to turn wifi on or off for Android 10+ SDK29+
             sdk and android must be Android Pie 9 SDK 28 or less!!!!!!
             - setWifiEnabled() is Depricated
             */
            @Override
            public void onClick(View view) {
                if(wifiManager.isWifiEnabled()){
                    wifiManager.setWifiEnabled(false);
                    btnOnOff.setText("WIFI Enabled: " + wifiManager.isWifiEnabled());
                }else{
                    wifiManager.setWifiEnabled(true);
                    btnOnOff.setText("WIFI Enabled: "+ wifiManager.isWifiEnabled());
                }
            }
        });

        //JSGARVEY
        btnDiscover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mManager.discoverPeers(mChannel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        connectionStatus.setText("Discovery Started");
                    }

                    @Override
                    public void onFailure(int i) {
                        connectionStatus.setText("Discovery Failed");
                    }
                });
            }
        });

    }

    //JSGARVEY initial work for creating objects from onCreate()
    private void initialWork() {
        //JSGARVEY create objects
        btnOnOff=(Button) findViewById(R.id.onOff);
        btnDiscover=(Button) findViewById(R.id.discover);
        btnSend=(Button) findViewById(R.id.sendButton);
        listView=(ListView) findViewById(R.id.peerListView);
        read_msg_box=(TextView) findViewById(R.id.readMsg);
        connectionStatus=(TextView) findViewById(R.id.connectionStatus);
        writeMSg=(EditText) findViewById(R.id.writeMsg);

        //JSGARVEY create wifi manager from the android app context system wifi services
        wifiManager= (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        //JSGARVEY create wifi p2p manager from the android app context p2p services
        mManager = (WifiP2pManager) getApplicationContext().getSystemService(Context.WIFI_P2P_SERVICE);
        mChannel = mManager.initialize(this, getMainLooper(),null);

        //JSGARVEY create wifi broadcast receiver to receive events from the wifi manager
        mReceiver = new WifiDirectBroadcastReceiver(mManager, mChannel, this);


        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

    }

    WifiP2pManager.PeerListListener peerListListener = new WifiP2pManager.PeerListListener() {
        @Override
        public void onPeersAvailable(WifiP2pDeviceList peerList) {
            if(!peerList.getDeviceList().equals(peers)){
                peers.clear();
                peers.addAll(peerList.getDeviceList());

                deviceNameArray = new String[peerList.getDeviceList().size()];
                deviceArray = new WifiP2pDevice[peerList.getDeviceList().size()];
                int index = 0;

                for(WifiP2pDevice device : peerList.getDeviceList()){
                    deviceNameArray[index] = device.deviceName;
                    deviceArray[index] = device;
                    index++;
                }

                ArrayAdapter<String> adapter = new ArrayAdapter<String>(getApplicationContext(), android.R.layout.simple_list_item_1,deviceNameArray);
                listView.setAdapter(adapter);
            }

            if(peers.size() == 0){
                Toast.makeText(getApplicationContext(), "No Device Found", Toast.LENGTH_SHORT).show();

            }

        }
    };

    @Override
    protected  void onResume(){
        super.onResume();
        registerReceiver(mReceiver,mIntentFilter);
    }

    @Override
    protected void onPause(){
        super.onPause();
        unregisterReceiver(mReceiver);
    }

}