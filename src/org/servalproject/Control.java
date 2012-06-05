package org.servalproject;

import java.io.File;
import java.io.IOException;

import org.servalproject.ServalBatPhoneApplication.State;
import org.servalproject.system.WiFiRadio;
import org.servalproject.system.WifiMode;
import org.sipdroid.sipua.SipdroidEngine;
import org.zoolu.net.IpAddress;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

public class Control extends Service {
	private ServalBatPhoneApplication app;
	private boolean radioOn = false;
	private boolean everythingRunning = false;
	private boolean serviceRunning = false;
	private SimpleWebServer webServer;
	private PowerManager powerManager;
	private int lastPeerCount = -1;

	public static final String ACTION_RESTART = "org.servalproject.restart";

	private BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action.equals(WiFiRadio.WIFI_MODE_ACTION)) {
				String newMode = intent
						.getStringExtra(WiFiRadio.EXTRA_NEW_MODE);
				radioOn = !(newMode == null || newMode.equals("Off"));

				if (serviceRunning) {
					new AsyncTask<Object, Object, Object>() {
						@Override
						protected Object doInBackground(Object... params) {
							modeChanged();
							return null;
						}
					}.execute();
				}
			}
		}
	};

	private Handler handler = new Handler();

	private Runnable notification = new Runnable() {
		@Override
		public void run() {
			if (powerManager.isScreenOn()) {
				int peerCount = app.wifiRadio.getPeerCount();
				if (peerCount != lastPeerCount)
					updateNotification();
			}
			handler.postDelayed(notification, 1000);
		}
	};

	private synchronized void modeChanged() {
		boolean wifiOn = radioOn;

		// if the software is disabled, or the radio has cycled to sleeping,
		// make sure everything is turned off.
		if (!serviceRunning)
			wifiOn = false;

		if (wifiOn == everythingRunning)
			return;

		this.handler.removeCallbacks(notification);

		if (wifiOn) {
			try {
				startDna();

				if (!app.coretask.isProcessRunning("sbin/asterisk")) {
					Log.v("BatPhone", "Starting asterisk");
					app.coretask.runCommand(app.coretask.DATA_FILE_PATH
							+ "/asterisk/sbin/asterisk");
				}

				IpAddress.localIpAddress = "127.0.0.1";

				if (!SipdroidEngine.isRegistered()) {
					Log.v("BatPhone", "Starting SIP client");
					SipdroidEngine.getEngine().StartEngine();
				}

				if (webServer == null)
					webServer = new SimpleWebServer(new File(
							app.coretask.DATA_FILE_PATH + "/htdocs"), 8080);

			} catch (IOException e) {
				Log.e("BatPhone", e.toString(), e);
			}

			updateNotification();
			handler.postDelayed(this.notification, 1000);

		} else {
			try {

				stopDna();

				if (SipdroidEngine.isRegistered()) {
					Log.v("BatPhone", "Halting SIP client");
					SipdroidEngine.getEngine().halt();
				}

				if (app.coretask.isProcessRunning("sbin/asterisk")) {
					Log.v("BatPhone", "Stopping asterisk");
					app.coretask.killProcess("sbin/asterisk", false);
				}

				if (webServer != null) {
					webServer.interrupt();
					webServer = null;
				}

			} catch (IOException e) {
				Log.e("BatPhone", e.toString(), e);
			}

			this.stopForeground(true);
		}
		everythingRunning = wifiOn;
	}

	private void updateNotification() {
		int peerCount = app.wifiRadio.getPeerCount();

		Notification notification = new Notification(
				R.drawable.start_notification, "Serval Mesh",
				System.currentTimeMillis());

		Intent intent = new Intent(app, Main.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

		notification.setLatestEventInfo(Control.this, "Serval Mesh", peerCount
				+ " Phone(s)", PendingIntent.getActivity(app, 0, intent,
				PendingIntent.FLAG_UPDATE_CURRENT));

		notification.flags = Notification.FLAG_ONGOING_EVENT;
		notification.number = peerCount;
		this.startForeground(-1, notification);

		lastPeerCount = peerCount;
	}

	public static void stopDna() throws IOException {
		ServalBatPhoneApplication app = ServalBatPhoneApplication.context;
		if (app.coretask.isProcessRunning("bin/dna")) {
			Log.v("BatPhone", "Stopping dna");
			app.coretask.killProcess("bin/dna", false);
		}
	}

	public static void restartDna() throws IOException {
		stopDna();
		startDna();
	}

	public static void startDna() throws IOException {
		ServalBatPhoneApplication app = ServalBatPhoneApplication.context;
		if (!app.coretask.isProcessRunning("bin/dna")) {
			Log.v("BatPhone", "Starting DNA");
			boolean instrumentation = app.settings.getBoolean("instrument_rec",
					false);
			Boolean gateway = app.settings.getBoolean("gatewayenable", false);

			app.coretask.runCommand(app.coretask.DATA_FILE_PATH
					+ "/bin/dna "
					+ (instrumentation ? "-L "
							+ app.getStorageFolder().getAbsolutePath()
							+ "/instrumentation.log " : "")
					+ (gateway ? "-G android " : "") + "-S 1 -f "
					+ app.coretask.DATA_FILE_PATH + "/var/hlr.dat");
		}
	}

	private synchronized void startService() {
		app.setState(State.Starting);
		try {
			app.wifiRadio.turnOn();

			app.setState(State.On);
		} catch (Exception e) {
			app.setState(State.Off);
			Log.e("BatPhone", e.getMessage(), e);
			app.displayToastMessage(e.getMessage());
		}
	}

	private synchronized void stopService() {
		app.setState(State.Stopping);
		try {
			WifiMode mode = app.wifiRadio.getCurrentMode();

			// If the current mode is Ap or Adhoc, the user will
			// probably want us to
			// turn off the radio.
			// If client mode, we'll ask them
			switch (mode) {
			case Adhoc:
			case Ap:
				app.wifiRadio.setWiFiMode(WifiMode.Off);
				break;
			}
			app.wifiRadio.checkAlarm();
		} catch (Exception e) {
			Log.e("BatPhone", e.getMessage(), e);
			app.displayToastMessage(e.getMessage());
		} finally {
			app.setState(State.Off);
		}
	}

	class Task extends AsyncTask<State, Object, Object> {
		@Override
		protected Object doInBackground(State... params) {
			if (params[0] == null) {
				if (app.getState() != State.Off)
					stopService();
				startService();
				return null;
			}

			if (app.getState() == params[0])
				return null;

			if (params[0] == State.Off) {
				stopService();
			} else {
				startService();
			}
			return null;
		}
	}

	@Override
	public void onCreate() {
		this.app = (ServalBatPhoneApplication) this.getApplication();

		powerManager = (PowerManager) app
				.getSystemService(Context.POWER_SERVICE);

		IntentFilter filter = new IntentFilter();
		filter.addAction(WiFiRadio.WIFI_MODE_ACTION);
		registerReceiver(receiver, filter);

		super.onCreate();
	}

	@Override
	public void onDestroy() {
		new Task().execute(State.Off);
		unregisterReceiver(receiver);
		serviceRunning = false;
		super.onDestroy();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		serviceRunning = true;
		if (intent == null) {
			new Task().execute(State.On);
			return START_STICKY;
		}
		String action = intent.getAction();
		if (ACTION_RESTART.equals(action))
			new Task().execute((State) null);
		else
			new Task().execute(State.On);
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

}
