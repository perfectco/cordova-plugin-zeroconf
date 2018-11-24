package net.becvert.cordova;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Queue;
import java.util.ArrayDeque;

import android.net.nsd.NsdServiceInfo;
import android.net.nsd.NsdManager;

import static android.content.Context.WIFI_SERVICE;

public class ZeroConf extends CordovaPlugin {

    private static final String TAG = "ZeroConf";

    private NsdManager _nsdManager;
    private String hostname;

    private class ServiceIdentifier {
      private String _domain, _name, _type;

      public ServiceIdentifier(String domain, String name, String type) {
        _domain = domain;
        _name = name;
        _type = type;
      }

      public boolean equals(ServiceIdentifier other) {
        return (other._domain == _domain) && (other._name == _name) && (other._type == _type);
      }

      public int hashCode() {
        return (_domain + _name + _type).hashCode();
      }
    }

    private class ServiceListener implements NsdManager.RegistrationListener {
      private CallbackContext _callback;

      public void setCallback(final CallbackContext cb) {
        _callback = cb;
      }

      @Override
      public void onServiceRegistered(NsdServiceInfo service) {

        try {

          JSONObject status = new JSONObject();
          status.put("action", "registered");
          status.put("service", service.getServiceName());

          Log.d(TAG, "Sending result: " + status.toString());

          PluginResult result = new PluginResult(PluginResult.Status.OK, status);

          if (_callback != null)
            _callback.sendPluginResult(result);
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage(), e);

            if (_callback != null)
              _callback.error("Error: " + e.getMessage());
        }
      }

      @Override
      public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
        if (_callback != null)
          _callback.error("Failed to register");
        return;
      }

      @Override
      public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
        if (_callback != null)
          _callback.success();
      }

      @Override
      public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
        if (_callback != null)
          _callback.error("Failed to unregister");
      }
    }

    private class WatchListener implements NsdManager.DiscoveryListener {
      private CallbackContext _callback, _eventCallback;
      private Queue<NsdServiceInfo> _toBeResolved = new ArrayDeque<NsdServiceInfo>(5);
      private NsdManager.ResolveListener _resolver = new NsdManager.ResolveListener() {
          @Override
          public void onServiceResolved(NsdServiceInfo service) {
              Log.d(TAG, "Resolved");

              sendCallback("resolved", service);
          }

          @Override
          public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
              Log.i(TAG, "Resolve failed: " + serviceInfo.getServiceName() + errorCode);
          }

          private void resolveNext() {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                  try {
                    Thread.sleep(1000);
                  } catch (InterruptedException e) {}

                  NsdServiceInfo next = _toBeResolved.poll();
                  if (next != null) {
                    _nsdManager.resolveService(next, _resolver);
                  }
                }
            });
          }
      };

      public WatchListener(CallbackContext eventCallback) {
        _eventCallback = eventCallback;
      }

      private void sendCallback(String action, NsdServiceInfo service) {
          JSONObject status = new JSONObject();
          try {
              status.put("action", action);
              status.put("service", jsonifyService(service));

              Log.d(TAG, "Sending result: " + status.toString());

              PluginResult result = new PluginResult(PluginResult.Status.OK, status);
              result.setKeepCallback(true);
              _eventCallback.sendPluginResult(result);

          } catch (JSONException e) {
              Log.e(TAG, e.getMessage(), e);
              _eventCallback.error("Error: " + e.getMessage());
          }
      }

      @Override
      public void onDiscoveryStarted(String regType) {
          Log.d(TAG, "Service discovery started");
      }

      @Override
      public void onServiceFound(NsdServiceInfo service) {
          Log.d(TAG, "Added");
          sendCallback("added", service);

          _toBeResolved.add(service);
          if (_toBeResolved.size() == 1) {
            _nsdManager.resolveService(_toBeResolved.poll(), _resolver);
          }
      }

      @Override
      public void onServiceLost(NsdServiceInfo service) {
          Log.d(TAG, "Removed");
          sendCallback("removed", service);
      }

      @Override
      public void onDiscoveryStopped(String serviceType) {
          Log.i(TAG, "Discovery stopped: " + serviceType);
      }

      @Override
      public void onStartDiscoveryFailed(String serviceType, int errorCode) {
          Log.e(TAG, "Discovery failed: Error code:" + errorCode);
          _nsdManager.stopServiceDiscovery(this);
      }

      @Override
      public void onStopDiscoveryFailed(String serviceType, int errorCode) {
          Log.e(TAG, "Discovery failed: Error code:" + errorCode);
          _nsdManager.stopServiceDiscovery(this);
      }
    }

    private Map<ServiceIdentifier, ServiceListener> registeredServices = new HashMap<ServiceIdentifier, ServiceListener>();
    private Map<ServiceIdentifier, WatchListener> registeredWatches = new HashMap<ServiceIdentifier, WatchListener>();

    public static final String ACTION_GET_HOSTNAME = "getHostname";
    // publisher
    public static final String ACTION_REGISTER = "register";
    public static final String ACTION_UNREGISTER = "unregister";
    public static final String ACTION_STOP = "stop";
    // browser
    public static final String ACTION_WATCH = "watch";
    public static final String ACTION_UNWATCH = "unwatch";
    public static final String ACTION_CLOSE = "close";
    // Re-initialize
    public static final String ACTION_REINIT = "reInit";

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);

        Context context = this.cordova.getActivity().getApplicationContext();
        WifiManager wifi = (WifiManager) context.getSystemService(WIFI_SERVICE);
        _nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);

        try {
            hostname = getHostName(cordova);
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
        }

        Log.d(TAG, "Hostname " + hostname);

        Log.v(TAG, "Initialized");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // TODO need to stop all watchers
        // TODO need to stop all services

        Log.v(TAG, "Destroyed");
    }

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) {

        if (ACTION_GET_HOSTNAME.equals(action)) {

            if (hostname != null) {

                Log.d(TAG, "Hostname: " + hostname);

                callbackContext.success(hostname);

            } else {
                callbackContext.error("Error: undefined hostname");
                return false;
            }

        } else if (ACTION_REGISTER.equals(action)) {
            NsdServiceInfo serviceInfo = new NsdServiceInfo();

            final String type = args.optString(0);
            final String domain = args.optString(1);
            final String name = args.optString(2);
            final int port = args.optInt(3);
            final JSONObject props = args.optJSONObject(4);
            final String addressFamily = args.optString(5);

            serviceInfo.setServiceType(type);
            serviceInfo.setServiceName(name);
            serviceInfo.setPort(port);

            Log.d(TAG, "Register " + type + domain);

            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        ServiceListener listener = new ServiceListener();
                        listener.setCallback(callbackContext);

                        _nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener);
                        registeredServices.put(new ServiceIdentifier(type, domain, name), listener);

                    } catch (RuntimeException e) {
                        Log.e(TAG, e.getMessage(), e);
                        callbackContext.error("Error: " + e.getMessage());
                    }
                }
            });

        } else if (ACTION_UNREGISTER.equals(action)) {

            final String type = args.optString(0);
            final String domain = args.optString(1);
            final String name = args.optString(2);

            cordova.getThreadPool().execute(new Runnable() {

                @Override
                public void run() {

                    Log.d(TAG, "Unregister " + type + domain);

                    ServiceListener listener = registeredServices.remove(new ServiceIdentifier(type, domain, name));
                    listener.setCallback(callbackContext);
                    _nsdManager.unregisterService(listener);

                    callbackContext.success();
                }
            });

        } else if (ACTION_STOP.equals(action)) {

            Log.d(TAG, "Stop");

            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    Iterator<ServiceIdentifier> iter = registeredServices.keySet().iterator();
                    while (iter.hasNext()) {
                      ServiceListener listener = registeredServices.remove(iter.next());
                      listener.setCallback(null);
                      _nsdManager.unregisterService(listener);
                    }

                    callbackContext.success();
                }
            });

        } else if (ACTION_WATCH.equals(action)) {

            final String type = args.optString(0);
            final String domain = args.optString(1);
            final String addressFamily = args.optString(2);

            Log.d(TAG, "Watch " + type + domain);

            cordova.getThreadPool().execute(new Runnable() {

                @Override
                public void run() {
                    try {
                        WatchListener listener = new WatchListener(callbackContext);
                        _nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);

                        registeredWatches.put(new ServiceIdentifier(type, domain, ""), listener);

                    } catch (RuntimeException e) {
                        Log.e(TAG, e.getMessage(), e);
                        callbackContext.error("Error: " + e.getMessage());
                    }
                }
            });

            PluginResult result = new PluginResult(Status.NO_RESULT);
            result.setKeepCallback(true);
            callbackContext.sendPluginResult(result);

        } else if (ACTION_UNWATCH.equals(action)) {

            final String type = args.optString(0);
            final String domain = args.optString(1);

            Log.d(TAG, "Unwatch " + type + domain);

            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    WatchListener listener = registeredWatches.remove(new ServiceIdentifier(type, domain, ""));
                    _nsdManager.stopServiceDiscovery(listener);

                    callbackContext.success();
                }
            });

        } else if (ACTION_CLOSE.equals(action)) {

            Log.d(TAG, "Close");

            cordova.getThreadPool().execute(new Runnable() {

                @Override
                public void run() {
                    Iterator<ServiceIdentifier> iter = registeredWatches.keySet().iterator();

                    while (iter.hasNext()) {
                      WatchListener listener = registeredWatches.remove(iter.next());
                      _nsdManager.stopServiceDiscovery(listener);
                    }

                    callbackContext.success();
                }
            });

        } else if (ACTION_REINIT.equals(action)) {
            Log.e(TAG, "Re-Initializing");

            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    onDestroy();
                    initialize(cordova, webView);
                    callbackContext.success();

                    Log.e(TAG, "Re-Initialization complete");
                }
            });

        } else {
            Log.e(TAG, "Invalid action: " + action);
            callbackContext.error("Invalid action: " + action);
            return false;
        }

        return true;
    }

    private static JSONObject jsonifyService(NsdServiceInfo service) throws JSONException {
        JSONObject obj = new JSONObject();

        String domain = "local.";
        obj.put("domain", domain);
        obj.put("type", service.getServiceType().replace(domain, ""));
        obj.put("name", service.getServiceName());
        obj.put("port", service.getPort());

        if (service.getHost() != null) {
            String[] hostComponents = service.getHost().toString().split("\\/");
            obj.put("hostname", hostComponents[hostComponents.length - 1]);
        }

        JSONObject props = new JSONObject();
        Map<String, byte[]> attributes = service.getAttributes();
        Iterator<String> names = attributes.keySet().iterator();

        while (names.hasNext()) {
            String name = names.next();
            try {
                props.put(name, new String(attributes.get(name), "UTF-8"));
            } catch (UnsupportedEncodingException e) {}
        }
        obj.put("txtRecord", props);

        return obj;
    }

    // http://stackoverflow.com/questions/21898456/get-android-wifi-net-hostname-from-code
    public static String getHostName(CordovaInterface cordova) throws NoSuchMethodException, SecurityException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        Method getString = Build.class.getDeclaredMethod("getString", String.class);
        getString.setAccessible(true);
        String hostName = getString.invoke(null, "net.hostname").toString();
        if (TextUtils.isEmpty(hostName)) {
            // API 26+ :
            // Querying the net.hostname system property produces a null result
            String id = Settings.Secure.getString(cordova.getActivity().getContentResolver(), Settings.Secure.ANDROID_ID);
            hostName = "android-" + id;
        }
        return hostName;
    }

}
