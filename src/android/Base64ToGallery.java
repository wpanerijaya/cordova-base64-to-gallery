package it.nexxa.base64ToGallery;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;

/**
 * Base64ToGallery.java
 *
 * Android implementation of the Base64ToGallery for iOS.
 * Inspirated by Joseph's "Save HTML5 Canvas Image to Gallery" plugin
 * http://jbkflex.wordpress.com/2013/06/19/save-html5-canvas-image-to-gallery-phonegap-android-plugin/
 *
 * @author Vegard Løkken <vegard@headspin.no>
 */
public class Base64ToGallery extends CordovaPlugin {

  // Consts
  public static final String EMPTY_STR = "";
  private final String WRITE_EXTERNAL_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;
  public static int SECURITY_ERR = 2;
  
  private PendingRequests pendingRequests;

  @Override
  public void initialize(CordovaInterface cordova, CordovaWebView webView) {
    this.pendingRequests = new PendingRequests();
  }
  
  /*
   * Handle the response
   */

  public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
    final PendingRequests.Request req = pendingRequests.getAndRemove(requestCode);
		
    if (req != null) {
      for(int r:grantResults) {
        if(r == PackageManager.PERMISSION_DENIED) {
          req.getCallbackContext().sendPluginResult(new PluginResult(PluginResult.Status.ERROR, SECURITY_ERR));
          return;
        }
      }
	  this.execute(req.getAction(), req.getRawArgs(), req.getCallbackContext());
	}
  }
  
  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    String base64               = args.optString(0);
    String filePrefix           = args.optString(1);
    boolean mediaScannerEnabled = args.optBoolean(2);

    // isEmpty() requires API level 9
    if (base64.equals(EMPTY_STR)) {
      callbackContext.error("Missing base64 string");
	  return true;
    }
	
	if (!PermissionHelper.hasPermission(this, WRITE_EXTERNAL_STORAGE)) {
	  int requestCode = pendingRequests.createRequest(args, action, callbackContext);
      PermissionHelper.requestPermission(this, requestCode, WRITE_EXTERNAL_STORAGE);
	  return true;
    }      

    // Create the bitmap from the base64 string
    byte[] decodedString = Base64.decode(base64, Base64.DEFAULT);
    Bitmap bmp           = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);

    if (bmp == null) {
      callbackContext.error("The image could not be decoded");
	  return true;
    } else {
	  File imageFile = null;
	  try {
        // Save the image
        imageFile = savePhoto(bmp, filePrefix);
  	  }
	  catch (Exception e) {
		callbackContext.error("An exception occured while saving image: " + e.toString());
		return true;
	  } 

      if (imageFile == null) {
        callbackContext.error("Error while saving image");
		return true;
      }
	  
      if (mediaScannerEnabled) {
	    try {
		  // Update image gallery
          scanPhoto(imageFile);
        }
    	catch (Exception e) {
    	  callbackContext.error("An exception occured while media scanning: " + e.toString());
		  return true;
    	}
      }
 
      callbackContext.success(imageFile.toString());
    }

    return true;
  }

  private File savePhoto(Bitmap bmp, String prefix) throws Exception {
    File retVal = null;

    try {
      String deviceVersion = Build.VERSION.RELEASE;
      Calendar c           = Calendar.getInstance();
      String date          = EMPTY_STR
                              + c.get(Calendar.YEAR)
                              + c.get(Calendar.MONTH)
                              + c.get(Calendar.DAY_OF_MONTH)
                              + c.get(Calendar.HOUR_OF_DAY)
                              + c.get(Calendar.MINUTE)
                              + c.get(Calendar.SECOND);

      int check = deviceVersion.compareTo("2.3.3");

      File folder;

      /*
       * File path = Environment.getExternalStoragePublicDirectory(
       * Environment.DIRECTORY_PICTURES ); //this throws error in Android
       * 2.2
       */
      if (check >= 1) {
        folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);

        if (!folder.exists()) {
          folder.mkdirs();
        }

      } else {
        folder = Environment.getExternalStorageDirectory();
      }

      File imageFile = new File(folder, prefix + date + ".png");

      FileOutputStream out = new FileOutputStream(imageFile);
      bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
      out.flush();
      out.close();

      retVal = imageFile;

    } catch (Exception e) {
      Log.e("Base64ToGallery", "An exception occured while saving image: " + e.toString());
	  throw e;
    }

    return retVal;
  }

  /**
   * Invoke the system's media scanner to add your photo to the Media Provider's database,
   * making it available in the Android Gallery application and to other apps.
   */
  private void scanPhoto(File imageFile) {
    Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
    Uri contentUri         = Uri.fromFile(imageFile);

    mediaScanIntent.setData(contentUri);

    cordova.getActivity().sendBroadcast(mediaScanIntent);
  }
}

/**
 * Holds pending runtime permission requests
 */
class PendingRequests {
    private int currentReqId = 0;
    private SparseArray<Request> requests = new SparseArray<Request>();

    /**
     * Creates a request and adds it to the array of pending requests. Each created request gets a
     * unique result code for use with requestPermission()
     * @param rawArgs           The raw arguments passed to the plugin
     * @param action            The action this request corresponds to (get file, etc.)
     * @param callbackContext   The CallbackContext for this plugin call
     * @return                  The request code that can be used to retrieve the Request object
     */
    public synchronized int createRequest(JSONArray rawArgs, String action, CallbackContext callbackContext)  {
        Request req = new Request(rawArgs, action, callbackContext);
        requests.put(req.requestCode, req);
        return req.requestCode;
    }

    /**
     * Gets the request corresponding to this request code and removes it from the pending requests
     * @param requestCode   The request code for the desired request
     * @return              The request corresponding to the given request code or null if such a
     *                      request is not found
     */
    public synchronized Request getAndRemove(int requestCode) {
        Request result = requests.get(requestCode);
        requests.remove(requestCode);
        return result;
    }

    /**
     * Holds the options and CallbackContext for a call made to the plugin.
     */
    public class Request {

        // Unique int used to identify this request in any Android permission callback
        private int requestCode;

        // Action to be performed after permission request result
        private String action;

        // Raw arguments passed to plugin
        private JSONArray rawArgs;

        // The callback context for this plugin request
        private CallbackContext callbackContext;

        private Request(JSONArray rawArgs, String action, CallbackContext callbackContext) {
            this.rawArgs = rawArgs;
            this.action = action;
            this.callbackContext = callbackContext;
            this.requestCode = currentReqId ++;
        }

        public String getAction() {
            return this.action;
        }

        public JSONArray getRawArgs() {
            return rawArgs;
        }

        public CallbackContext getCallbackContext() {
            return callbackContext;
        }
    }
}
