package com.cradle.iitc_mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.text.Html;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.webkit.WebResourceResponse;
import android.widget.Toast;

import com.cradle.iitc_mobile.IITC_Mobile.ResponseHandler;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.HashMap;

public class IITC_FileManager {
    private static final WebResourceResponse EMPTY =
            new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream("".getBytes()));
    private static final String WRAPPER_NEW = "wrapper(info);";
    private static final String WRAPPER_OLD =
            "script.appendChild(document.createTextNode('('+ wrapper +')('+JSON.stringify(info)+');'));\n"
                    + "(document.body || document.head || document.documentElement).appendChild(script);";

    public static final String DOMAIN = ".iitcm.localhost";

    /**
     * copies the contents of a stream into another stream and (optionally) closes the output stream afterwards
     *
     * @param inStream
     *            the stream to read from
     * @param outStream
     *            the stream to write to
     * @param closeOutput
     *            whether to close the output stream when finished
     *
     * @throws IOException
     */
    public static void copyStream(final InputStream inStream, final OutputStream outStream, final boolean closeOutput)
            throws IOException
    {
        // in case Android includes Apache commons IO in the future, this function should be replaced by IOUtils.copy
        final int bufferSize = 4096;
        final byte[] buffer = new byte[bufferSize];
        int len = 0;

        try {
            while ((len = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, len);
            }
        } finally {
            if (outStream != null && closeOutput)
                outStream.close();
        }
    }

    public static HashMap<String, String> getScriptInfo(final String js) {
        final HashMap<String, String> map = new HashMap<String, String>();
        String header = "";
        if (js != null && js.contains("==UserScript==") && js.contains("==/UserScript==")) {
            header = js.substring(js.indexOf("==UserScript=="),
                    js.indexOf("==/UserScript=="));
        }
        // remove new line comments
        header = header.replace("\n//", " ");
        // get a list of key-value
        final String[] attributes = header.split("  +");
        // add default values
        map.put("id", "unknown");
        map.put("version", "not found");
        map.put("name", "unknown");
        map.put("description", "");
        map.put("category", "Misc");
        // add parsed values
        for (int i = 0; i < attributes.length; i++) {
            // search for attributes and use the value
            if (attributes[i].equals("@id")) {
                map.put("id", attributes[i + 1]);
            }
            if (attributes[i].equals("@version")) {
                map.put("version", attributes[i + 1]);
            }
            if (attributes[i].equals("@name")) {
                map.put("name", attributes[i + 1]);
            }
            if (attributes[i].equals("@description")) {
                map.put("description", attributes[i + 1]);
            }
            if (attributes[i].equals("@category")) {
                map.put("category", attributes[i + 1]);
            }
        }
        return map;
    }

    public static String readStream(final InputStream stream) {
        final ByteArrayOutputStream os = new ByteArrayOutputStream();

        try {
            copyStream(stream, os, true);
        } catch (final IOException e) {
            Log.w(e);
            return "";
        }
        return os.toString();
    }

    private final AssetManager mAssetManager;
    private final Activity mActivity;
    private final String mIitcPath;
    private final SharedPreferences mPrefs;
    public static final String PLUGINS_PATH = Environment.getExternalStorageDirectory().getPath()
            + "/IITC_Mobile/plugins/";

    public IITC_FileManager(final Activity activity) {
        mActivity = activity;
        mIitcPath = Environment.getExternalStorageDirectory().getPath() + "/Activity/";
        mPrefs = PreferenceManager.getDefaultSharedPreferences(activity);
        mAssetManager = mActivity.getAssets();
    }

    private InputStream getAssetFile(final String filename) throws IOException {
        if (mPrefs.getBoolean("pref_dev_checkbox", false)) {
            final File file = new File(mIitcPath + "dev/" + filename);
            try {
                return new FileInputStream(file);
            } catch (final FileNotFoundException e) {
                mActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(mActivity, "File " + mIitcPath +
                                "dev/" + filename + " not found. " +
                                "Disable developer mode or add iitc files to the dev folder.",
                                Toast.LENGTH_SHORT).show();
                    }
                });
                Log.w(e);
            }
        }

        // load plugins from asset folder
        return mAssetManager.open(filename);
    }

    private WebResourceResponse getFileRequest(final Uri uri) {
        return new FileRequest(uri);
    }

    private WebResourceResponse getScript(final Uri uri) {
        InputStream stream;
        try {
            stream = getAssetFile(uri.getPath().substring(1));
        } catch (final IOException e) {
            Log.w(e);
            return EMPTY;
        }

        final InputStream data = prepareUserScript(stream);

        return new WebResourceResponse("application/x-javascript", "UTF-8", data);
    }

    private HashMap<String, String> getScriptInfo(final InputStream stream) {
        return getScriptInfo(readStream(stream));
    }

    private WebResourceResponse getUserPlugin(final Uri uri) {
        if (!mPrefs.getBoolean(uri.getPath(), false)) {
            Log.e("Attempted to inject user script that is not enabled by user: " + uri.getPath());
            return EMPTY;
        }

        InputStream stream;
        try {
            stream = new FileInputStream(new File(uri.getPath()));
        } catch (final IOException e) {
            Log.w(e);
            return EMPTY;
        }

        final InputStream data = prepareUserScript(stream);

        return new WebResourceResponse("application/x-javascript", "UTF-8", data);
    }

    private InputStream prepareUserScript(final InputStream stream) {
        String content = readStream(stream);
        final HashMap<String, String> info = getScriptInfo(content);

        final JSONObject jObject = new JSONObject(info);
        final String gmInfo = "var GM_info={\"script\":" + jObject.toString() + "}";

        content = content.replace(WRAPPER_OLD, WRAPPER_NEW);

        return new ByteArrayInputStream((gmInfo + content).getBytes());
    }

    public String getFileRequestPrefix() {
        return "//file-request" + DOMAIN + "/";
    }

    public String getIITCVersion() throws IOException {
        final InputStream stream = getAssetFile("total-conversion-build.user.js");

        return getScriptInfo(stream).get("version");
    }

    public WebResourceResponse getResponse(final Uri uri) {
        String host = uri.getHost();
        if (!host.endsWith(DOMAIN))
            return EMPTY;

        host = host.substring(0, host.length() - DOMAIN.length());

        if ("script".equals(host))
            return getScript(uri);
        if ("user-plugin".equals(host))
            return getUserPlugin(uri);
        if ("file-request".equals(host))
            return getFileRequest(uri);

        Log.e("could not generate response for url: " + uri);
        return EMPTY;
    }

    public void installPlugin(final Uri uri, final boolean invalidateHeaders) {
        if (uri != null) {
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(mActivity);

            // set title
            alertDialogBuilder.setTitle(mActivity.getString(R.string.install_dialog_top));

            // set dialog message
            String text = mActivity.getString(R.string.install_dialog_msg);
            text = String.format(text, uri);
            alertDialogBuilder
                    .setMessage(Html.fromHtml(text))
                    .setCancelable(true)
                    .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            copyPlugin(uri, invalidateHeaders);
                        }
                    })
                    .setNegativeButton("No", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                        }
                    });

            // create alert dialog
            AlertDialog alertDialog = alertDialogBuilder.create();

            // show it
            alertDialog.show();
        }
    }

    private void copyPlugin(final Uri uri, final boolean invalidateHeaders) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String url = uri.toString();
                    String fileName = url.substring( url.lastIndexOf('/')+1, url.length() );
                    // we need 2 stream since an inputStream is useless after read once
                    // we read it twice because we first need the script ID for the fileName and
                    // afterwards reading it again while copying
                    InputStream is, isCopy;
                    if (uri.getScheme().contains("http")) {
                        URLConnection conn = new URL(url).openConnection();
                        URLConnection connCopy = new URL(url).openConnection();
                        is = conn.getInputStream();
                        isCopy = connCopy.getInputStream();
                    } else {
                        is = mActivity.getContentResolver().openInputStream(uri);
                        isCopy = mActivity.getContentResolver().openInputStream(uri);
                    }
                    fileName = getScriptInfo(isCopy).get("id") + ".user.js";
                    // create IITCm external plugins directory if it doesn't already exist
                    final File pluginsDirectory = new File(PLUGINS_PATH);
                    pluginsDirectory.mkdirs();

                    // create in and out streams and copy plugin
                    File outFile = new File(pluginsDirectory + "/" + fileName);
                    OutputStream os = new FileOutputStream(outFile);
                    IITC_FileManager.copyStream(is, os, true);
                } catch (IOException e) {
                    Log.w(e);
                }
            }
        });
        thread.start();
        if (invalidateHeaders) {
            try {
                thread.join();
                ((IITC_PluginPreferenceActivity) mActivity).invalidateHeaders();
            } catch (InterruptedException e) {
                Log.w(e);
            }
        }
    }

    private class FileRequest extends WebResourceResponse implements ResponseHandler, Runnable {
        private Intent mData;
        private final String mFunctionName;
        private int mResultCode;
        private PipedOutputStream mStreamOut;

        private FileRequest(final Uri uri) {
            // create two connected streams we can write to after the file has been read
            super("application/x-javascript", "UTF-8", new PipedInputStream());

            try {
                mStreamOut = new PipedOutputStream((PipedInputStream) getData());
            } catch (final IOException e) {
                Log.w(e);
            }

            // the function to call
            mFunctionName = uri.getPathSegments().get(0);

            // create the chooser Intent
            final Intent target = new Intent(Intent.ACTION_GET_CONTENT);
            target.setType("file/*");
            target.addCategory(Intent.CATEGORY_OPENABLE);

            try {
                final IITC_Mobile iitc = (IITC_Mobile) mActivity;
                iitc.startActivityForResult(Intent.createChooser(target, "Choose file"), this);
            } catch (final ActivityNotFoundException e) {
                Toast.makeText(mActivity, "No activity to select a file found." +
                        "Please install a file browser of your choice!", Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onActivityResult(final int resultCode, final Intent data) {
            final IITC_Mobile iitc = (IITC_Mobile) mActivity;
            iitc.deleteResponseHandler(this); // to enable garbage collection

            mResultCode = resultCode;
            mData = data;

            // read file in new thread using Runnable interface, see run()
            new Thread(this, "FileRequestReader").start();
        }

        @Override
        public void run() {
            try {
                if (mResultCode == Activity.RESULT_OK && mData != null) {
                    final Uri uri = mData.getData();
                    final File file = new File(uri.getPath());

                    // now create a resource that basically looks like:
                    // someFunctionName('<url encoded filename>', '<base64 encoded content>');

                    mStreamOut.write(
                            (mFunctionName + "('" + URLEncoder.encode(file.getName(), "UTF-8") + "', '").getBytes());

                    final Base64OutputStream encoder =
                            new Base64OutputStream(mStreamOut, Base64.NO_CLOSE | Base64.NO_WRAP | Base64.DEFAULT);

                    final FileInputStream fileinput = new FileInputStream(file);

                    copyStream(fileinput, encoder, true);

                    mStreamOut.write("');".getBytes());
                }

            } catch (final IOException e) {
                Log.w(e);
            } finally {
                // try to close stream, but ignore errors
                try {
                    mStreamOut.close();
                } catch (final IOException e1) {
                }
            }
        }
    }
}
